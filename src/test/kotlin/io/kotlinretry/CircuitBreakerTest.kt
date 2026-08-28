package io.kotlinretry

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class CircuitBreakerTest : DescribeSpec({

    describe("CircuitBreaker state transitions") {

        it("starts CLOSED and passes calls through") {
            val breaker = circuitBreaker("test") { failureThreshold = 3 }
            breaker.currentState shouldBe CircuitBreaker.State.CLOSED

            val result = breaker.execute { "ok" }
            result shouldBe "ok"
        }

        it("transitions to OPEN after failureThreshold failures") {
            val breaker = circuitBreaker("test") { failureThreshold = 3 }

            repeat(3) {
                runCatching { breaker.execute { throw IOException("fail") } }
            }

            breaker.currentState shouldBe CircuitBreaker.State.OPEN
        }

        it("rejects calls with CircuitBreakerOpenException when OPEN") {
            val breaker = circuitBreaker("test") { failureThreshold = 2 }

            repeat(2) { runCatching { breaker.execute { throw IOException("fail") } } }

            shouldThrow<CircuitBreakerOpenException> {
                breaker.execute { "should be rejected" }
            }.name shouldBe "test"
        }

        it("resets to CLOSED after manual reset") {
            val breaker = circuitBreaker("test") { failureThreshold = 1 }

            runCatching { breaker.execute { throw IOException("fail") } }
            breaker.currentState shouldBe CircuitBreaker.State.OPEN

            breaker.reset()
            breaker.currentState shouldBe CircuitBreaker.State.CLOSED

            val result = breaker.execute { "recovered" }
            result shouldBe "recovered"
        }

        it("invokes onStateChange callback on transitions") {
            val transitions = mutableListOf<Triple<String, CircuitBreaker.State, CircuitBreaker.State>>()
            val breaker = circuitBreaker("tracked") {
                failureThreshold = 2
                onStateChange    = { name, from, to -> transitions += Triple(name, from, to) }
            }

            repeat(2) { runCatching { breaker.execute { throw IOException("fail") } } }

            transitions.any { it.third == CircuitBreaker.State.OPEN } shouldBe true
        }

        it("announces OPEN -> HALF_OPEN once, however often the state is observed") {
            val clock = TestClock()
            val transitions = ConcurrentLinkedQueue<Pair<CircuitBreaker.State, CircuitBreaker.State>>()
            val breaker = circuitBreaker("probe-once") {
                failureThreshold = 1
                openDuration     = 100.milliseconds
                onStateChange    = { _, from, to -> transitions += from to to }
                timeSource       = clock
            }

            runCatching { breaker.execute { throw IOException("fail") } }
            breaker.currentState shouldBe CircuitBreaker.State.OPEN

            clock.advance(200.milliseconds)

            repeat(20) { breaker.currentState }

            transitions.count {
                it == CircuitBreaker.State.OPEN to CircuitBreaker.State.HALF_OPEN
            } shouldBe 1
        }

        it("announces OPEN -> HALF_OPEN once when many threads observe it at once") {
            val transitions = ConcurrentLinkedQueue<Pair<CircuitBreaker.State, CircuitBreaker.State>>()
            val clock = TestClock()
            val breaker = circuitBreaker("probe-once-concurrent") {
                failureThreshold = 1
                openDuration     = 100.milliseconds
                onStateChange    = { _, from, to -> transitions += from to to }
                timeSource       = clock
            }

            runCatching { breaker.execute { throw IOException("fail") } }
            clock.advance(200.milliseconds)

            withContext(Dispatchers.Default) {
                coroutineScope {
                    List(64) { async { repeat(50) { breaker.currentState } } }.awaitAll()
                }
            }

            transitions.count {
                it == CircuitBreaker.State.OPEN to CircuitBreaker.State.HALF_OPEN
            } shouldBe 1
        }

        it("restarts openDuration when a probe fails and reopens the circuit") {
            val clock = TestClock()
            val breaker = circuitBreaker("reopen") {
                failureThreshold = 1
                openDuration     = 300.milliseconds
                timeSource       = clock
            }

            runCatching { breaker.execute { throw IOException("fail") } }
            clock.advance(400.milliseconds)
            breaker.currentState shouldBe CircuitBreaker.State.HALF_OPEN

            // Failed probe -> OPEN again, and the open window must start over rather than
            // inheriting the timestamp from the first cycle.
            runCatching { breaker.execute { throw IOException("probe failed") } }
            breaker.currentState shouldBe CircuitBreaker.State.OPEN

            // 200ms into the second window: still open, because the stamp was refreshed.
            clock.advance(200.milliseconds)
            breaker.currentState shouldBe CircuitBreaker.State.OPEN
            clock.advance(200.milliseconds)
            breaker.currentState shouldBe CircuitBreaker.State.HALF_OPEN
        }

        it("does not report a transition for calls it rejects") {
            val transitions = ConcurrentLinkedQueue<Pair<CircuitBreaker.State, CircuitBreaker.State>>()
            val breaker = circuitBreaker("rejections") {
                failureThreshold = 1
                openDuration     = 10.seconds
                onStateChange    = { _, from, to -> transitions += from to to }
            }

            runCatching { breaker.execute { throw IOException("fail") } }
            transitions.size shouldBe 1

            repeat(20) {
                shouldThrow<CircuitBreakerOpenException> { breaker.execute { "rejected" } }
            }

            // Still just the one CLOSED -> OPEN move; shedding load announces nothing.
            transitions.size shouldBe 1
            transitions.none { it.first == it.second } shouldBe true
        }

        it("releases its lock before onStateChange, so a callback may re-enter the breaker") {
            var breakerRef: CircuitBreaker? = null
            val reentered = AtomicBoolean(false)

            val breaker = circuitBreaker("reentrant") {
                failureThreshold = 1
                onStateChange    = { _, from, to ->
                    if (from == CircuitBreaker.State.CLOSED && to == CircuitBreaker.State.OPEN) {
                        // reset() takes the same mutex the transition just held.
                        runBlocking { breakerRef!!.reset() }
                        reentered.set(true)
                    }
                }
            }
            breakerRef = breaker

            // Daemon thread + bounded get(): if the callback ever deadlocks under the lock again
            // the test fails on the timeout, and the stuck thread cannot stop the JVM exiting.
            val executor = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "cb-reentrancy-probe").apply { isDaemon = true }
            }
            try {
                executor.submit {
                    runBlocking { runCatching { breaker.execute { throw IOException("fail") } } }
                }.get(10, TimeUnit.SECONDS)
            } finally {
                executor.shutdownNow()
            }

            reentered.get() shouldBe true
            breaker.currentState shouldBe CircuitBreaker.State.CLOSED
        }

        it("lets only one probe through at a time while HALF_OPEN") {
            val clock = TestClock()
            val breaker = circuitBreaker("one-probe") {
                failureThreshold = 1
                successThreshold = 1
                openDuration     = 100.milliseconds
                timeSource       = clock
            }

            runCatching { breaker.execute { throw IOException("fail") } }
            clock.advance(200.milliseconds)
            breaker.currentState shouldBe CircuitBreaker.State.HALF_OPEN

            // 32 callers arrive together the moment the window expires. Exactly one may reach
            // the dependency; flooding a service that just came back is how it goes down again.
            val admitted = AtomicInteger(0)
            val rejected = AtomicInteger(0)
            val release  = CompletableDeferred<Unit>()

            withTimeout(30.seconds) { withContext(Dispatchers.Default) {
                coroutineScope {
                    val calls = List(32) {
                        async {
                            runCatching {
                                breaker.execute {
                                    admitted.incrementAndGet()
                                    release.await()      // hold the slot until every caller has tried
                                }
                            }.onFailure { if (it is CircuitBreakerOpenException) rejected.incrementAndGet() }
                        }
                    }
                    // Let the losers pile up on the permit before the winner finishes.
                    while (rejected.get() < 31) delay(1.milliseconds)
                    release.complete(Unit)
                    calls.awaitAll()
                }
            } }

            admitted.get() shouldBe 1
            rejected.get() shouldBe 31
        }

        it("frees the probe slot between attempts, so successThreshold probes can run") {
            val clock = TestClock()
            val breaker = circuitBreaker("sequential-probes") {
                failureThreshold = 1
                successThreshold = 3
                openDuration     = 100.milliseconds
                timeSource       = clock
            }

            runCatching { breaker.execute { throw IOException("fail") } }
            clock.advance(200.milliseconds)

            // One permit, but it is released after each probe - otherwise the first success
            // would hold the only slot and the circuit could never reach its threshold.
            breaker.execute { "probe 1" } shouldBe "probe 1"
            breaker.currentState shouldBe CircuitBreaker.State.HALF_OPEN
            breaker.execute { "probe 2" } shouldBe "probe 2"
            breaker.currentState shouldBe CircuitBreaker.State.HALF_OPEN
            breaker.execute { "probe 3" } shouldBe "probe 3"
            breaker.currentState shouldBe CircuitBreaker.State.CLOSED
        }

        it("honours a raised permittedCallsInHalfOpen") {
            val clock = TestClock()
            val breaker = circuitBreaker("three-probes") {
                failureThreshold = 1
                successThreshold = 10          // high enough that no probe closes the circuit
                openDuration     = 100.milliseconds
                permittedCallsInHalfOpen = 3
                timeSource       = clock
            }

            runCatching { breaker.execute { throw IOException("fail") } }
            clock.advance(200.milliseconds)

            val admitted = AtomicInteger(0)
            val rejected = AtomicInteger(0)
            val release  = CompletableDeferred<Unit>()

            withTimeout(30.seconds) { withContext(Dispatchers.Default) {
                coroutineScope {
                    val calls = List(20) {
                        async {
                            runCatching {
                                breaker.execute { admitted.incrementAndGet(); release.await() }
                            }.onFailure { if (it is CircuitBreakerOpenException) rejected.incrementAndGet() }
                        }
                    }
                    while (rejected.get() < 17) delay(1.milliseconds)
                    release.complete(Unit)
                    calls.awaitAll()
                }
            } }

            admitted.get() shouldBe 3
            rejected.get() shouldBe 17
        }

        it("does not let a surviving probe undo a sibling probe's reopen") {
            val clock = TestClock()
            val breaker = circuitBreaker("concurrent-probes") {
                failureThreshold = 1
                successThreshold = 1
                permittedCallsInHalfOpen = 2
                openDuration     = 100.milliseconds
                timeSource       = clock
            }

            runCatching { breaker.execute { throw IOException("fail") } }
            clock.advance(200.milliseconds)
            breaker.currentState shouldBe CircuitBreaker.State.HALF_OPEN

            val slowProbeAdmitted = CompletableDeferred<Unit>()
            val fastProbeFailed   = CompletableDeferred<Unit>()

            coroutineScope {
                // Admitted while HALF_OPEN, finishes after the circuit has already reopened.
                val slowProbe = async {
                    breaker.execute {
                        slowProbeAdmitted.complete(Unit)
                        fastProbeFailed.await()
                        "slow probe succeeded"
                    }
                }

                slowProbeAdmitted.await()
                runCatching { breaker.execute { throw IOException("fast probe failed") } }
                breaker.currentState shouldBe CircuitBreaker.State.OPEN
                fastProbeFailed.complete(Unit)

                slowProbe.await() shouldBe "slow probe succeeded"
            }

            // Its success is stale news: a sibling probe already proved the dependency is down.
            breaker.currentState shouldBe CircuitBreaker.State.OPEN
        }

        it("returns the probe slot when a probe is cancelled") {
            val clock = TestClock()
            val breaker = circuitBreaker("cancelled-probe") {
                failureThreshold = 1
                successThreshold = 1
                openDuration     = 100.milliseconds
                timeSource       = clock
            }

            runCatching { breaker.execute { throw IOException("fail") } }
            clock.advance(200.milliseconds)

            // A withdrawn probe decides nothing, so it must not strand the only slot.
            shouldThrow<CancellationException> {
                breaker.execute { throw CancellationException("caller gave up") }
            }

            breaker.currentState shouldBe CircuitBreaker.State.HALF_OPEN
            breaker.execute { "next probe runs" } shouldBe "next probe runs"
            breaker.currentState shouldBe CircuitBreaker.State.CLOSED
        }

        it("does not count non-recorded failures") {
            val breaker = circuitBreaker("test") {
                failureThreshold = 2
                recordFailure    = { it is IOException }
            }

            repeat(5) {
                runCatching { breaker.execute { throw IllegalStateException("not counted") } }
            }

            breaker.currentState shouldBe CircuitBreaker.State.CLOSED
        }
    }
})

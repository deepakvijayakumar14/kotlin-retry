package io.kotlinretry

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
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
            val transitions = ConcurrentLinkedQueue<Pair<CircuitBreaker.State, CircuitBreaker.State>>()
            val breaker = circuitBreaker("probe-once") {
                failureThreshold = 1
                openDuration     = 100.milliseconds
                onStateChange    = { _, from, to -> transitions += from to to }
            }

            runCatching { breaker.execute { throw IOException("fail") } }
            breaker.currentState shouldBe CircuitBreaker.State.OPEN

            delay(200.milliseconds)

            repeat(20) { breaker.currentState }

            transitions.count {
                it == CircuitBreaker.State.OPEN to CircuitBreaker.State.HALF_OPEN
            } shouldBe 1
        }

        it("announces OPEN -> HALF_OPEN once when many threads observe it at once") {
            val transitions = ConcurrentLinkedQueue<Pair<CircuitBreaker.State, CircuitBreaker.State>>()
            val breaker = circuitBreaker("probe-once-concurrent") {
                failureThreshold = 1
                openDuration     = 100.milliseconds
                onStateChange    = { _, from, to -> transitions += from to to }
            }

            runCatching { breaker.execute { throw IOException("fail") } }
            delay(200.milliseconds)

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
            val breaker = circuitBreaker("reopen") {
                failureThreshold = 1
                openDuration     = 300.milliseconds
            }

            runCatching { breaker.execute { throw IOException("fail") } }
            delay(400.milliseconds)
            breaker.currentState shouldBe CircuitBreaker.State.HALF_OPEN

            // Failed probe -> OPEN again, and the open window must start over rather than
            // inheriting the timestamp from the first cycle.
            runCatching { breaker.execute { throw IOException("probe failed") } }
            breaker.currentState shouldBe CircuitBreaker.State.OPEN
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

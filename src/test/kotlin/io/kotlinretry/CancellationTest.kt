package io.kotlinretry

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

/**
 * Cancellation is the caller withdrawing the work, not a failure of it. No layer may retry it,
 * count it as a circuit failure, or convert it into a fallback value.
 */
class CancellationTest : DescribeSpec({

    describe("cancellation") {

        it("is not retried") {
            val attempts = AtomicInteger(0)
            shouldThrow<CancellationException> {
                retry(attempts = 5, delay = 0.milliseconds) {
                    attempts.incrementAndGet()
                    throw CancellationException("withdrawn")
                }
            }
            attempts.get() shouldBe 1
        }

        it("propagates out of retryOrNull instead of becoming null") {
            shouldThrow<CancellationException> {
                retryOrNull(attempts = 3, delay = 0.milliseconds) {
                    throw CancellationException("withdrawn")
                }
            }
        }

        it("propagates out of retryOrDefault instead of becoming the default") {
            shouldThrow<CancellationException> {
                retryOrDefault(default = -1, attempts = 3, delay = 0.milliseconds) {
                    throw CancellationException("withdrawn")
                }
            }
        }

        it("does not count towards opening the circuit") {
            val breaker = circuitBreaker("cancelled") { failureThreshold = 2 }
            repeat(5) {
                runCatching { breaker.execute { throw CancellationException("withdrawn") } }
            }
            breaker.currentState shouldBe CircuitBreaker.State.CLOSED
        }

        it("is not converted into a fallback value") {
            val policy = resiliencePolicy("cancelled") { retry { attempts = 3; delay = 0.milliseconds } }
            shouldThrow<CancellationException> {
                policy.executeOrElse(fallback = { "fallback" }) {
                    throw CancellationException("withdrawn")
                }
            }
        }

        it("stops a running retry loop when the enclosing scope is cancelled") {
            val attempts = AtomicInteger(0)
            val started = CompletableDeferred<Unit>()

            withContext(Dispatchers.Default) {
                coroutineScope {
                    val job = launch {
                        retry(attempts = Int.MAX_VALUE, delay = 10.milliseconds) {
                            attempts.incrementAndGet()
                            started.complete(Unit)
                            throw IOException("keeps failing")
                        }
                    }
                    started.await()
                    delay(50.milliseconds)
                    job.cancel()
                    job.join()
                }
            }

            val settled = attempts.get()
            delay(100.milliseconds)
            // No further attempts after cancellation: the loop actually stopped.
            attempts.get() shouldBe settled
        }
    }
})

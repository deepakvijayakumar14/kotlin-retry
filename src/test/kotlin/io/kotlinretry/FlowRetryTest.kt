package io.kotlinretry

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

class FlowRetryTest : DescribeSpec({

    describe("Flow.retryWith") {

        it("re-collects the upstream until it succeeds") {
            val collections = AtomicInteger(0)
            val source = flow {
                val run = collections.incrementAndGet()
                emit("a")
                if (run < 3) throw IOException("upstream died")
                emit("b")
            }

            val values = source.retryWith(attempts = 5, delay = 0.milliseconds).toList()

            collections.get() shouldBe 3
            // Each failed run re-emits from the start, so "a" appears once per attempt.
            values shouldBe listOf("a", "a", "a", "b")
        }

        it("gives up after `attempts` collections and rethrows the original cause") {
            val collections = AtomicInteger(0)
            val source = flow<String> {
                collections.incrementAndGet()
                throw IOException("always down")
            }

            val ex = shouldThrow<IOException> {
                source.retryWith(attempts = 3, delay = 0.milliseconds).toList()
            }

            ex.message shouldBe "always down"
            collections.get() shouldBe 3
        }

        it("hands the real cause to a downstream catch") {
            val source = flow<String> { throw IOException("io failed") }

            val values = source
                .retryWith(attempts = 2, delay = 0.milliseconds)
                .catch { cause -> emit(if (cause is IOException) "saw-io" else "saw-other") }
                .toList()

            values shouldBe listOf("saw-io")
        }

        it("does not retry a cause retryOn rejects") {
            val collections = AtomicInteger(0)
            val source = flow<String> {
                collections.incrementAndGet()
                throw IllegalArgumentException("not retryable")
            }

            shouldThrow<IllegalArgumentException> {
                source.retryWith(
                    attempts = 5,
                    delay    = 0.milliseconds,
                    retryOn  = { it is IOException },
                ).toList()
            }
            collections.get() shouldBe 1
        }

        it("reports each retry through onRetry with 1-based attempt numbers") {
            val seen = mutableListOf<Pair<Int, Int>>()
            val source = flow<String> { throw IOException("down") }

            runCatching {
                source.retryWith(
                    attempts = 4,
                    delay    = 0.milliseconds,
                    onRetry  = { ctx, _ -> seen += ctx.attempt to ctx.maxAttempts },
                ).toList()
            }

            // Three retries follow the first of four attempts.
            seen shouldBe listOf(1 to 4, 2 to 4, 3 to 4)
        }

        it("never retries cancellation") {
            val collections = AtomicInteger(0)
            val source = flow<String> {
                collections.incrementAndGet()
                throw CancellationException("withdrawn")
            }

            shouldThrow<CancellationException> {
                source.retryWith(attempts = 5, delay = 0.milliseconds).toList()
            }
            collections.get() shouldBe 1
        }

        it("passes a healthy flow straight through") {
            flowOf(1, 2, 3).retryWith(attempts = 3).toList() shouldBe listOf(1, 2, 3)
        }

        it("accepts a reusable RetryPolicy") {
            val policy = retryPolicy { attempts = 2; delay = 0.milliseconds }
            val collections = AtomicInteger(0)
            val source = flow<String> {
                collections.incrementAndGet()
                throw IOException("down")
            }

            shouldThrow<IOException> { source.retryWith(policy).toList() }
            collections.get() shouldBe 2
        }

        it("applies the configured backoff between attempts") {
            val delays = mutableListOf<Int>()
            val recording = Backoff { attempt, _ -> delays += attempt; 0.milliseconds }
            val source = flow<String> { throw IOException("down") }

            runCatching {
                source.retryWith(attempts = 4, delay = 0.milliseconds, backoff = recording).toList()
            }

            delays shouldBe listOf(1, 2, 3)
        }
    }
})

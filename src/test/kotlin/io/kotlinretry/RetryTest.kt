package io.kotlinretry

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds

class RetryTest : DescribeSpec({

    describe("retry { }") {

        it("returns result on first success") {
            var calls = 0
            val result = retry(attempts = 3) {
                calls++
                "ok"
            }
            result shouldBe "ok"
            calls shouldBe 1
        }

        it("retries on failure and succeeds") {
            var calls = 0
            val result = retry(attempts = 3, delay = 0.milliseconds) {
                calls++
                if (calls < 3) throw IOException("transient")
                "recovered"
            }
            result shouldBe "recovered"
            calls shouldBe 3
        }

        it("throws RetryExhaustedException when all attempts fail") {
            var calls = 0
            val ex = shouldThrow<RetryExhaustedException> {
                retry(attempts = 3, delay = 0.milliseconds) {
                    calls++
                    throw IOException("always fails")
                }
            }
            ex.attempts shouldBe 3
            ex.cause?.message shouldBe "always fails"
            calls shouldBe 3
        }

        it("does not retry on non-retryable exception") {
            var calls = 0
            shouldThrow<IllegalArgumentException> {
                retry(attempts = 5, delay = 0.milliseconds, retryOn = { it is IOException }) {
                    calls++
                    throw IllegalArgumentException("not retryable")
                }
            }
            calls shouldBe 1
        }

        it("calls onRetry callback on each retry") {
            val events = mutableListOf<Pair<Int, String>>()
            runCatching {
                retry(
                    attempts = 3,
                    delay    = 0.milliseconds,
                    onRetry  = { ctx, ex -> events += ctx.attempt to (ex.message ?: "") },
                ) {
                    throw IOException("boom")
                }
            }
            events.size shouldBe 2
            events[0].first shouldBe 1
            events[1].first shouldBe 2
        }

        it("exposes RetryContext correctly") {
            val contexts = mutableListOf<RetryContext>()
            runCatching {
                retry(attempts = 3, delay = 0.milliseconds) { ctx ->
                    contexts += ctx
                    throw IOException("fail")
                }
            }
            contexts.size shouldBe 3
            contexts[0].isFirstAttempt shouldBe true
            contexts[0].isLastAttempt  shouldBe false
            contexts[2].isLastAttempt  shouldBe true
            contexts[0].retriesRemaining shouldBe 2
        }
    }

    describe("retryPolicy") {

        it("is reusable across calls") {
            val policy = retryPolicy { attempts = 2; delay = 0.milliseconds }

            var first = 0
            runCatching { policy.execute { first++; throw IOException("a") } }
            var second = 0
            runCatching { policy.execute { second++; throw IOException("b") } }

            first shouldBe 2
            second shouldBe 2
        }
    }

    describe("retryOrDefault") {

        it("returns result on success") {
            val result = retryOrDefault(default = -1) { 42 }
            result shouldBe 42
        }

        it("returns default when all attempts fail") {
            val result = retryOrDefault(default = -1, attempts = 2, delay = 0.milliseconds) {
                throw IOException("fail")
            }
            result shouldBe -1
        }
    }

    describe("retryOrNull") {

        it("returns null when all attempts fail") {
            val result = retryOrNull<String>(attempts = 2, delay = 0.milliseconds) {
                throw IOException("fail")
            }
            result shouldBe null
        }
    }
})

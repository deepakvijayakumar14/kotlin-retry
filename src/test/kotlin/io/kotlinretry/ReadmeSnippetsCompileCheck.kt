package io.kotlinretry

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Compiles (and runs) the snippets published in README.md so the documented API
 * can never drift away from the real one again.
 */
class ReadmeSnippetsCompileCheck : DescribeSpec({

    describe("README snippets") {

        it("headline: resilient composing retry, breaker, timeout, fallback") {
            val result = resilient<String>(
                "payment-service",
                configure = {
                    retry { attempts = 3; backoff = Backoff.exponential() }
                    circuitBreaker { failureThreshold = 5; openDuration = 30.seconds }
                    timeout(5.seconds)
                    fallback { "cached-result" }
                },
            ) {
                throw IOException("gateway down")
            }
            result shouldBe "cached-result"
        }

        it("retry: basic, filtered, and context-aware forms") {
            val basic = retry(
                configure = {
                    attempts = 3
                    delay    = 0.milliseconds
                    backoff  = Backoff.exponential()
                },
            ) { "fetched" }
            basic shouldBe "fetched"

            val filtered = retry(
                configure = {
                    attempts = 5
                    delay    = 0.milliseconds
                    retryOn  = { it is IOException }
                },
            ) { "called" }
            filtered shouldBe "called"

            val withContext = retry(configure = { attempts = 3 }) { ctx -> ctx.attempt }
            withContext shouldBe 1
        }

        it("retryOrDefault / retryOrNull") {
            retryOrDefault(default = 1.0) { 42.0 } shouldBe 42.0
            retryOrNull { "data" } shouldBe "data"
        }

        it("shared circuit breaker via use()") {
            val breaker = circuitBreaker("inventory-service") { failureThreshold = 5 }

            val stock = resilient<Int>(
                "get-stock",
                configure = {
                    use(breaker)
                    retry { attempts = 2 }
                },
            ) { 7 }
            stock shouldBe 7
        }
    }
})

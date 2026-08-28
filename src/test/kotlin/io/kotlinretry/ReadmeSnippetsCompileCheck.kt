package io.kotlinretry

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Compiles (and runs) the snippets published in README.md so the documented API
 * can never drift away from the real one again.
 */
class ReadmeSnippetsCompileCheck : DescribeSpec({

    describe("README snippets") {

        it("headline: a policy composing retry, breaker and timeout") {
            val payments = resiliencePolicy("payment-service") {
                retry          { attempts = 3; delay = 0.milliseconds; backoff = Backoff.exponential() }
                circuitBreaker { failureThreshold = 5; openDuration = 30.seconds }
                timeout(5.seconds)
            }

            val result = payments.executeOrElse(fallback = { "cached-result" }) {
                throw IOException("gateway down")
            }
            result shouldBe "cached-result"
        }

        it("composing everything: the fallback distinguishes the failure it was given") {
            val payments = resiliencePolicy("payment-processor") {
                retry {
                    attempts = 3
                    delay    = 0.milliseconds
                    backoff  = Backoff.jitter()
                    retryOn  = { it is IOException }
                }
                // Threshold matches the attempt count, so all three attempts run and the third
                // failure is what trips the breaker.
                circuitBreaker { failureThreshold = 3; openDuration = 30.seconds }
                timeout(8.seconds)
            }

            fun charge(): suspend (RetryContext) -> String = { throw IOException("gateway down") }

            fun handle(ex: Throwable): String = when (ex) {
                is CircuitBreakerOpenException -> "service-unavailable"
                is OperationTimeoutException   -> "timed-out"
                else                           -> "exhausted"
            }

            payments.executeOrElse(fallback = ::handle, block = charge()) shouldBe "exhausted"

            // The breaker is open now, and the README's `is CircuitBreakerOpenException` branch
            // has to actually match - it would not if the rejection were wrapped by the retry.
            payments.executeOrElse(fallback = ::handle) { "live" } shouldBe "service-unavailable"
        }

        it("retry: basic, filtered, and context-aware forms") {
            val basic = retry(attempts = 3, delay = 0.milliseconds, backoff = Backoff.exponential()) {
                "fetched"
            }
            basic shouldBe "fetched"

            val filtered = retry(attempts = 5, delay = 0.milliseconds, retryOn = { it is IOException }) {
                "called"
            }
            filtered shouldBe "called"

            val withContext = retry(attempts = 3) { ctx -> ctx.attempt }
            withContext shouldBe 1
        }

        it("retryOrDefault / retryOrNull / retryPolicy") {
            retryOrDefault(default = 1.0) { 42.0 } shouldBe 42.0
            retryOrNull { "data" } shouldBe "data"

            val flaky = retryPolicy { attempts = 5; delay = 0.milliseconds; backoff = Backoff.jitter() }
            flaky.execute { "via policy" } shouldBe "via policy"
        }

        it("one policy serves call sites returning different types") {
            val search = resiliencePolicy("search") {
                retry { attempts = 3; delay = 0.milliseconds }
                timeout(10.seconds)
            }

            search.execute { "text" } shouldBe "text"
            search.execute { 7 } shouldBe 7
        }

        it("flow: retryWith, inline and with a policy") {
            val prices = flow<String> { throw IOException("feed down") }
                .retryWith(attempts = 5, delay = 0.milliseconds, backoff = Backoff.jitter())
                .catch { emit("unavailable") }
                .toList()
            prices shouldBe listOf("unavailable")

            val streamingPolicy = retryPolicy { attempts = 2; delay = 0.milliseconds }
            flow { emit("event") }.retryWith(streamingPolicy).toList() shouldBe listOf("event")
        }

        it("sharing one circuit breaker across policies") {
            val breaker = circuitBreaker("inventory-service") { failureThreshold = 5 }

            val getStock = resiliencePolicy("get-stock") {
                use(breaker)
                retry { attempts = 2; delay = 0.milliseconds }
            }
            val reserveStock = resiliencePolicy("reserve-stock") { use(breaker) }

            getStock.execute { 7 } shouldBe 7
            reserveStock.execute { true } shouldBe true
            getStock.circuitBreaker shouldBe breaker
            reserveStock.circuitBreaker shouldBe breaker
        }
    }
})

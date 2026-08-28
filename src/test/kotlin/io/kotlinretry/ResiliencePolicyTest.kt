package io.kotlinretry

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ResiliencePolicyTest : DescribeSpec({

    describe("ResiliencePolicy") {

        it("keeps its circuit breaker across calls, so an inline breaker actually opens") {
            val policy = resiliencePolicy("inline-breaker") {
                circuitBreaker { failureThreshold = 2; openDuration = 60.seconds }
            }

            var blockInvocations = 0
            var rejections = 0
            repeat(10) {
                runCatching {
                    policy.execute<String> {
                        blockInvocations++
                        throw IOException("down")
                    }
                }.exceptionOrNull()?.let { if (it is CircuitBreakerOpenException) rejections++ }
            }

            // Two failures trip the breaker; the remaining eight never reach the block.
            blockInvocations shouldBe 2
            rejections shouldBe 8
            policy.circuitBreaker?.currentState shouldBe CircuitBreaker.State.OPEN
        }

        it("shares one breaker between policies via use()") {
            val breaker = circuitBreaker("shared") { failureThreshold = 2; openDuration = 60.seconds }
            val a = resiliencePolicy("a") { use(breaker) }
            val b = resiliencePolicy("b") { use(breaker) }

            runCatching { a.execute<String> { throw IOException("1") } }
            runCatching { b.execute<String> { throw IOException("2") } }

            // One failure through each policy is enough: the count is shared.
            breaker.currentState shouldBe CircuitBreaker.State.OPEN
            shouldThrow<CircuitBreakerOpenException> { a.execute { "rejected" } }
        }

        it("counts every retry attempt as a breaker failure, not one per execute") {
            val policy = resiliencePolicy("attempt-counting") {
                retry          { attempts = 3; delay = 0.milliseconds }
                circuitBreaker { failureThreshold = 3; openDuration = 60.seconds }
            }

            var calls = 0
            shouldThrow<RetryExhaustedException> {
                policy.execute<String> { calls++; throw IOException("down") }
            }

            // The breaker sits inside the retry loop, so one logical call can trip it. Counting
            // a single failure per execute would take three times as long to spot the outage.
            calls shouldBe 3
            policy.circuitBreaker?.currentState shouldBe CircuitBreaker.State.OPEN
        }

        it("does not spend retry attempts on an already-open circuit") {
            val retries = mutableListOf<Int>()
            val policy = resiliencePolicy("no-retry-when-open") {
                retry {
                    attempts = 5
                    delay    = 10.seconds          // any retry at all would hang the test
                    onRetry  = { ctx, _ -> retries += ctx.attempt }
                }
                circuitBreaker { failureThreshold = 1; openDuration = 60.seconds }
            }

            runCatching { policy.execute<String> { throw IOException("down") } }
            policy.circuitBreaker?.currentState shouldBe CircuitBreaker.State.OPEN
            retries.clear()

            var calls = 0
            shouldThrow<CircuitBreakerOpenException> {
                policy.execute { calls++; "never reached" }
            }

            // Rejections are not failures to retry: no attempt reached the block, and no backoff
            // delay was served for a question the breaker had already answered.
            calls shouldBe 0
            retries.shouldBeEmpty()
        }

        it("fails fast when the breaker opens partway through a retry loop") {
            val policy = resiliencePolicy("trips-midway") {
                retry          { attempts = 5; delay = 0.milliseconds }
                circuitBreaker { failureThreshold = 2; openDuration = 60.seconds }
            }

            var calls = 0
            // Attempts 1 and 2 fail and open the circuit; attempt 3 is rejected, which ends the
            // loop there. The caller learns the circuit opened rather than that retries ran out.
            shouldThrow<CircuitBreakerOpenException> {
                policy.execute<String> { calls++; throw IOException("down") }
            }
            calls shouldBe 2
        }

        it("hands an open circuit to the fallback unwrapped") {
            val policy = resiliencePolicy("fallback-sees-rejection") {
                retry          { attempts = 3; delay = 0.milliseconds }
                circuitBreaker { failureThreshold = 1; openDuration = 60.seconds }
            }

            runCatching { policy.execute<String> { throw IOException("down") } }

            // The shape used in the README: matching on CircuitBreakerOpenException only works
            // if the rejection is not buried inside RetryExhaustedException.
            val result = policy.executeOrElse(
                fallback = { ex ->
                    when (ex) {
                        is CircuitBreakerOpenException -> "service-unavailable"
                        else                           -> throw ex
                    }
                },
            ) { "live-value" }

            result shouldBe "service-unavailable"
        }

        it("retries, then reports exhaustion") {
            val policy = resiliencePolicy("retrying") {
                retry { attempts = 3; delay = 0.milliseconds }
            }
            var calls = 0
            shouldThrow<RetryExhaustedException> {
                policy.execute<String> { calls++; throw IOException("always") }
            }
            calls shouldBe 3
        }

        it("applies the timeout across the whole execution, retries included") {
            val policy = resiliencePolicy("slow") {
                retry { attempts = 5; delay = 0.milliseconds }
                timeout(150.milliseconds)
            }
            shouldThrow<OperationTimeoutException> {
                policy.execute { delay(100.milliseconds); throw IOException("slow") }
            }
        }

        it("hands the failure to the fallback given to executeOrElse") {
            val policy = resiliencePolicy("falling-back") {
                retry { attempts = 2; delay = 0.milliseconds }
            }
            val seen = mutableListOf<String>()

            val result = policy.executeOrElse(fallback = { ex -> seen += ex.message ?: ""; "default" }) {
                throw IOException("boom")
            }

            result shouldBe "default"
            seen.single().shouldBe("Operation failed after 2 attempt(s): boom")
        }

        it("serves call sites with different result types from one instance") {
            val policy = resiliencePolicy("multi") { retry { attempts = 2; delay = 0.milliseconds } }
            policy.execute { "text" } shouldBe "text"
            policy.execute { 7 } shouldBe 7
        }

        it("passes calls straight through when nothing is configured") {
            resiliencePolicy("bare").execute { "ok" } shouldBe "ok"
        }
    }
})

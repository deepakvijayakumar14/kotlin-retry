package io.kotlinretry

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Every invalid setting must be rejected where it is written, with a message naming the knob.
 *
 * The alternative is what these settings used to do: `attempts = 0` skipped the retry loop and
 * surfaced as a NullPointerException from deep inside the library, hours or days after the
 * mistake was made.
 */
class ConfigValidationTest : DescribeSpec({

    describe("RetryPolicy.Builder validation") {

        it("rejects a non-positive attempt count instead of failing with an NPE later") {
            listOf(0, -1).forEach { invalid ->
                shouldThrow<IllegalArgumentException> {
                    retryPolicy { attempts = invalid }
                }.message shouldContain "attempts must be at least 1"
            }
        }

        it("rejects a negative delay") {
            shouldThrow<IllegalArgumentException> {
                retryPolicy { delay = (-1).milliseconds }
            }.message shouldContain "delay must not be negative"
        }

        it("accepts a single attempt and a zero delay") {
            val policy = retryPolicy { attempts = 1; delay = Duration.ZERO }
            policy.maxAttempts shouldBe 1
        }
    }

    describe("CircuitBreaker.Builder validation") {

        it("rejects a non-positive failureThreshold") {
            listOf(0, -3).forEach { invalid ->
                shouldThrow<IllegalArgumentException> {
                    circuitBreaker("cb") { failureThreshold = invalid }
                }.message shouldContain "failureThreshold must be at least 1"
            }
        }

        it("rejects a non-positive successThreshold") {
            shouldThrow<IllegalArgumentException> {
                circuitBreaker("cb") { successThreshold = 0 }
            }.message shouldContain "successThreshold must be at least 1"
        }

        it("rejects a non-positive permittedCallsInHalfOpen") {
            // Zero permits would half-open a circuit that can never be probed, and so never close.
            shouldThrow<IllegalArgumentException> {
                circuitBreaker("cb") { permittedCallsInHalfOpen = 0 }
            }.message shouldContain "permittedCallsInHalfOpen must be at least 1"
        }

        it("rejects a negative openDuration") {
            shouldThrow<IllegalArgumentException> {
                circuitBreaker("cb") { openDuration = (-1).seconds }
            }.message shouldContain "openDuration must not be negative"
        }
    }

    describe("Backoff factory validation") {

        it("rejects a multiplier that is not finite and positive") {
            listOf(Double.NaN, Double.POSITIVE_INFINITY, 0.0, -2.0).forEach { invalid ->
                shouldThrow<IllegalArgumentException> {
                    Backoff.exponential(multiplier = invalid)
                }.message shouldContain "multiplier must be finite and positive"

                shouldThrow<IllegalArgumentException> {
                    Backoff.jitter(multiplier = invalid)
                }.message shouldContain "multiplier must be finite and positive"
            }
        }

        it("rejects a negative maxDelay") {
            shouldThrow<IllegalArgumentException> {
                Backoff.exponential(maxDelay = (-1).seconds)
            }.message shouldContain "maxDelay must not be negative"

            shouldThrow<IllegalArgumentException> {
                Backoff.jitter(maxDelay = (-1).seconds)
            }.message shouldContain "maxDelay must not be negative"

            shouldThrow<IllegalArgumentException> {
                Backoff.decorrelatedJitter(maxDelay = (-1).seconds)
            }.message shouldContain "maxDelay must not be negative"
        }

        it("validates eagerly, at construction rather than at the first delay") {
            // A bad multiplier that only surfaced on the first retry would be found in production,
            // not at startup.
            shouldThrow<IllegalArgumentException> { Backoff.exponential(multiplier = -1.0) }
        }
    }
})

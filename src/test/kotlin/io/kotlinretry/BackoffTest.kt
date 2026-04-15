package io.kotlinretry

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.longs.shouldBeLessThanOrEqualTo
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class BackoffTest : DescribeSpec({

    describe("Backoff.none") {
        it("always returns ZERO") {
            Backoff.none.delayFor(1, 500.milliseconds) shouldBe 0.milliseconds
            Backoff.none.delayFor(5, 500.milliseconds) shouldBe 0.milliseconds
        }
    }

    describe("Backoff.fixed") {
        it("always returns the base delay") {
            Backoff.fixed.delayFor(1, 500.milliseconds) shouldBe 500.milliseconds
            Backoff.fixed.delayFor(3, 500.milliseconds) shouldBe 500.milliseconds
        }
    }

    describe("Backoff.linear") {
        it("grows linearly") {
            Backoff.linear.delayFor(1, 500.milliseconds) shouldBe 500.milliseconds
            Backoff.linear.delayFor(2, 500.milliseconds) shouldBe 1000.milliseconds
            Backoff.linear.delayFor(3, 500.milliseconds) shouldBe 1500.milliseconds
        }
    }

    describe("Backoff.exponential") {
        val backoff = Backoff.exponential(multiplier = 2.0, maxDelay = 30.seconds)

        it("doubles each attempt") {
            backoff.delayFor(1, 500.milliseconds) shouldBe 500.milliseconds
            backoff.delayFor(2, 500.milliseconds) shouldBe 1000.milliseconds
            backoff.delayFor(3, 500.milliseconds) shouldBe 2000.milliseconds
        }

        it("caps at maxDelay") {
            backoff.delayFor(20, 500.milliseconds) shouldBe 30.seconds
        }
    }

    describe("Backoff.jitter") {
        val backoff = Backoff.jitter(multiplier = 2.0, maxDelay = 30.seconds)

        it("stays within [0, ceiling]") {
            repeat(50) {
                val delay = backoff.delayFor(3, 500.milliseconds)
                delay.inWholeMilliseconds shouldBeGreaterThanOrEqualTo 0L
                delay.inWholeMilliseconds shouldBeLessThanOrEqualTo 2000L
            }
        }
    }
})

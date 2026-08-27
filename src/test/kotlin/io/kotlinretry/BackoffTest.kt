package io.kotlinretry

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
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

        it("returns ZERO for a sub-millisecond base instead of throwing") {
            backoff.delayFor(1, 500.microseconds) shouldBe Duration.ZERO
        }

        it("returns ZERO for a zero base instead of throwing") {
            backoff.delayFor(1, Duration.ZERO) shouldBe Duration.ZERO
        }

        it("returns ZERO when maxDelay is zero instead of throwing") {
            Backoff.jitter(maxDelay = Duration.ZERO).delayFor(3, 500.milliseconds) shouldBe Duration.ZERO
        }

        it("caps at maxDelay on high attempt numbers") {
            repeat(50) {
                Backoff.jitter(maxDelay = 2.seconds)
                    .delayFor(30, 500.milliseconds)
                    .inWholeMilliseconds shouldBeLessThanOrEqualTo 2000L
            }
        }
    }

    describe("Backoff.decorrelatedJitter") {

        it("returns the base delay on the first attempt") {
            Backoff.decorrelatedJitter(maxDelay = 30.seconds)
                .delayFor(1, 500.milliseconds) shouldBe 500.milliseconds
        }

        it("grows across attempts but never exceeds maxDelay") {
            val backoff = Backoff.decorrelatedJitter(maxDelay = 5.seconds)
            repeat(20) { attempt ->
                val delay = backoff.delayFor(attempt + 1, 100.milliseconds)
                delay.inWholeMilliseconds shouldBeGreaterThanOrEqualTo 100L
                delay.inWholeMilliseconds shouldBeLessThanOrEqualTo 5000L
            }
        }

        it("clamps to maxDelay when base exceeds it instead of throwing") {
            Backoff.decorrelatedJitter(maxDelay = 1.seconds)
                .delayFor(1, 5.seconds) shouldBe 1.seconds
        }

        it("returns ZERO when maxDelay is zero instead of throwing") {
            Backoff.decorrelatedJitter(maxDelay = Duration.ZERO)
                .delayFor(1, 5.seconds) shouldBe Duration.ZERO
        }

        it("holds its invariants when one instance is shared across threads") {
            val backoff = Backoff.decorrelatedJitter(maxDelay = 5.seconds)
            val base    = 100.milliseconds

            val delays = withContext(Dispatchers.Default) {
                coroutineScope {
                    List(64) {
                        async { List(200) { backoff.delayFor(1, base).inWholeMilliseconds } }
                    }.awaitAll()
                }
            }.flatten()

            delays.size shouldBe 64 * 200
            delays.min() shouldBeGreaterThanOrEqualTo 100L
            delays.max() shouldBeLessThanOrEqualTo 5000L
        }

        it("survives an unbounded maxDelay without overflowing") {
            val backoff = Backoff.decorrelatedJitter(maxDelay = Duration.INFINITE)
            repeat(60) { attempt ->
                backoff.delayFor(attempt + 1, 1.seconds)
                    .inWholeMilliseconds shouldBeGreaterThanOrEqualTo 1000L
            }
        }
    }
})

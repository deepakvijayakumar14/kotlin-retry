package io.kotlinretry

import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Strategy that computes the delay before each retry attempt.
 *
 * [attempt] is 1-based (first retry = 1).
 */
fun interface Backoff {
    fun delayFor(attempt: Int, base: Duration): Duration

    companion object {

        /** No delay between attempts. */
        val none: Backoff = Backoff { _, _ -> Duration.ZERO }

        /**
         * Fixed delay: every attempt waits the same [base] duration.
         *
         * ```
         * attempt 1 -> 500ms
         * attempt 2 -> 500ms
         * attempt 3 -> 500ms
         * ```
         */
        val fixed: Backoff = Backoff { _, base -> base }

        /**
         * Linear backoff: delay grows by [base] on each attempt.
         *
         * ```
         * attempt 1 -> 500ms
         * attempt 2 -> 1000ms
         * attempt 3 -> 1500ms
         * ```
         */
        val linear: Backoff = Backoff { attempt, base -> base * attempt }

        /**
         * Exponential backoff: delay doubles each attempt, capped at [maxDelay].
         *
         * ```
         * attempt 1 -> 500ms
         * attempt 2 -> 1000ms
         * attempt 3 -> 2000ms
         * ```
         */
        fun exponential(multiplier: Double = 2.0, maxDelay: Duration = 30_000.milliseconds): Backoff =
            Backoff { attempt, base ->
                val ms = base.inWholeMilliseconds * multiplier.pow(attempt - 1)
                min(ms, maxDelay.inWholeMilliseconds.toDouble()).toLong().milliseconds
            }

        /**
         * Full jitter: randomizes the delay up to the exponential ceiling.
         * Best choice for distributed systems to avoid thundering herd.
         *
         * See: https://aws.amazon.com/blogs/architecture/exponential-backoff-and-jitter/
         */
        fun jitter(multiplier: Double = 2.0, maxDelay: Duration = 30_000.milliseconds): Backoff =
            Backoff { attempt, base ->
                val ceiling = min(
                    base.inWholeMilliseconds * multiplier.pow(attempt - 1),
                    maxDelay.inWholeMilliseconds.toDouble()
                )
                Random.nextLong(ceiling.toLong()).milliseconds
            }

        /**
         * Decorrelated jitter: delay is randomized between [base] and 3x the previous delay.
         * Even better than full jitter for spreading retries across clients.
         */
        fun decorrelatedJitter(maxDelay: Duration = 30_000.milliseconds): Backoff {
            var prev = Duration.ZERO
            return Backoff { _, base ->
                val lo = base.inWholeMilliseconds
                val hi = min(maxDelay.inWholeMilliseconds, (prev.inWholeMilliseconds * 3).coerceAtLeast(lo))
                val next = Random.nextLong(lo, hi + 1).milliseconds
                prev = next
                next
            }
        }
    }
}

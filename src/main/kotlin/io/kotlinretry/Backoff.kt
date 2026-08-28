package io.kotlinretry

import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Rejects the factory arguments that would otherwise surface as a nonsense delay much later:
 * a NaN or negative multiplier, or a negative ceiling.
 */
private fun requireValidGrowth(multiplier: Double, maxDelay: Duration) {
    require(multiplier.isFinite() && multiplier > 0.0) {
        "multiplier must be finite and positive, but was $multiplier"
    }
    requireValidCeiling(maxDelay)
}

private fun requireValidCeiling(maxDelay: Duration) {
    require(maxDelay >= Duration.ZERO) { "maxDelay must not be negative, but was $maxDelay" }
}

/** Growth factor applied to the previous delay by [Backoff.decorrelatedJitter]. */
private const val DECORRELATED_JITTER_GROWTH = 3

/** Applies [DECORRELATED_JITTER_GROWTH] to [millis], saturating at [Long.MAX_VALUE] instead of overflowing. */
private fun grown(millis: Long): Long =
    if (millis > Long.MAX_VALUE / DECORRELATED_JITTER_GROWTH) Long.MAX_VALUE
    else millis * DECORRELATED_JITTER_GROWTH

/**
 * Strategy that computes the delay before each retry attempt.
 *
 * `attempt` is 1-based (first retry = 1).
 */
fun interface Backoff {
    fun delayFor(attempt: Int, base: Duration): Duration

    companion object {

        /** No delay between attempts. */
        val none: Backoff = Backoff { _, _ -> Duration.ZERO }

        /**
         * Fixed delay: every attempt waits the same `base` duration.
         *
         * ```
         * attempt 1 -> 500ms
         * attempt 2 -> 500ms
         * attempt 3 -> 500ms
         * ```
         */
        val fixed: Backoff = Backoff { _, base -> base }

        /**
         * Linear backoff: delay grows by `base` on each attempt.
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
        fun exponential(multiplier: Double = 2.0, maxDelay: Duration = 30_000.milliseconds): Backoff {
            requireValidGrowth(multiplier, maxDelay)
            return Backoff { attempt, base ->
                val ms = base.inWholeMilliseconds * multiplier.pow(attempt - 1)
                min(ms, maxDelay.inWholeMilliseconds.toDouble()).toLong().milliseconds
            }
        }

        /**
         * Full jitter: randomizes the delay uniformly over `[0, ceiling)`, where the ceiling is
         * the exponential delay capped at [maxDelay].
         * Best choice for distributed systems to avoid thundering herd.
         *
         * Returns [Duration.ZERO] when the ceiling rounds down to less than a millisecond
         * (a sub-millisecond `base`, a zero [maxDelay], or a non-positive input).
         *
         * See: https://aws.amazon.com/blogs/architecture/exponential-backoff-and-jitter/
         */
        fun jitter(multiplier: Double = 2.0, maxDelay: Duration = 30_000.milliseconds): Backoff {
            requireValidGrowth(multiplier, maxDelay)
            return Backoff { attempt, base ->
                val ceiling = min(
                    base.inWholeMilliseconds * multiplier.pow(attempt - 1),
                    maxDelay.inWholeMilliseconds.toDouble()
                )
                // Guards the empty range: Random.nextLong demands a strictly positive bound.
                // NaN (from 0 * Infinity) and negative ceilings collapse to 0 here too.
                val ceilingMillis = ceiling.toLong().coerceAtLeast(0L)
                if (ceilingMillis == 0L) Duration.ZERO else Random.nextLong(ceilingMillis).milliseconds
            }
        }

        /**
         * Decorrelated jitter: delay is randomized over `[base, 3x previous delay)`, capped at
         * [maxDelay]. Even better than full jitter for spreading retries across clients.
         *
         * The result is always clamped to `[0, maxDelay]`, so a `base` above [maxDelay] yields
         * [maxDelay] rather than an out-of-range delay. The first call returns `base`.
         *
         * The returned [Backoff] carries the previous delay as state, held atomically, so a single
         * instance is safe to share across concurrent coroutines and threads. Each caller still
         * receives its own draw; concurrent callers simply chain off whichever delay landed first.
         */
        fun decorrelatedJitter(maxDelay: Duration = 30_000.milliseconds): Backoff {
            requireValidCeiling(maxDelay)
            val previousMillis = AtomicLong(0L)
            return Backoff { _, base ->
                val cap = maxDelay.inWholeMilliseconds.coerceAtLeast(0L)
                val lo  = base.inWholeMilliseconds.coerceIn(0L, cap)

                // updateAndGet re-runs this block if another thread wins the CAS. Re-running is
                // safe: it only draws a fresh delay from the newly observed previous value.
                previousMillis.updateAndGet { prev ->
                    val hi = min(cap, grown(prev).coerceAtLeast(lo))

                    // The range collapses once the cap reaches the floor - nothing left to
                    // randomise, and Random.nextLong would reject an empty range.
                    if (hi <= lo) lo else Random.nextLong(lo, hi)
                }.milliseconds
            }
        }
    }
}

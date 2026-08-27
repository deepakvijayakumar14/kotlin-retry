package io.kotlinretry

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.retryWhen
import kotlin.time.Duration

/**
 * Retries the upstream flow with this [policy] when it fails.
 *
 * ```kotlin
 * val prices = priceUpdates()
 *     .retryWith(retryPolicy { attempts = 5; backoff = Backoff.jitter() })
 *     .catch { emit(Price.unavailable()) }
 * ```
 *
 * Two behaviours are worth knowing, both inherited from how retrying a cold flow has to work:
 *
 * - **The upstream is re-collected from the start.** If it failed after emitting, downstream sees
 *   those values again. Make the collector tolerant of repeats, or deduplicate downstream.
 * - **The original failure propagates once attempts run out** - unlike [retry], which wraps it in
 *   [RetryExhaustedException]. Downstream `catch` operators are given the real cause, so
 *   `catch { if (it is IOException) ... }` behaves as written.
 *
 * Cancellation is never retried, matching every other layer in this library.
 */
fun <T> Flow<T>.retryWith(policy: RetryPolicy): Flow<T> = retryWhen { cause, attemptIndex ->
    // attemptIndex counts retries already made, so it is also the 0-based index of the attempt
    // that just failed. Compare before narrowing: it is a Long and maxAttempts an Int.
    if (cause is CancellationException) return@retryWhen false
    if (!policy.retryOn(cause)) return@retryWhen false
    if (attemptIndex >= policy.maxAttempts - 1L) return@retryWhen false

    val attempt = (attemptIndex + 1).toInt()
    val ctx = RetryContext(attempt = attempt, maxAttempts = policy.maxAttempts)
    policy.onRetry?.invoke(ctx, cause)

    val wait = policy.backoff.delayFor(attempt, policy.initialDelay)
    if (wait > Duration.ZERO) delay(wait)
    true
}

/**
 * Retries the upstream flow, configured inline.
 *
 * ```kotlin
 * userEvents()
 *     .retryWith(attempts = 3, delay = 200.milliseconds, retryOn = { it is IOException })
 *     .collect { handle(it) }
 * ```
 *
 * See [retryWith] for the re-collection and error-propagation semantics.
 */
// Mirrors the named-parameter shape of [retry] so both read the same way at a call site.
@Suppress("LongParameterList")
fun <T> Flow<T>.retryWith(
    attempts: Int = DEFAULT_MAX_ATTEMPTS,
    delay: Duration = DEFAULT_DELAY,
    backoff: Backoff = DEFAULT_BACKOFF,
    retryOn: (Throwable) -> Boolean = DEFAULT_RETRY_ON,
    onRetry: ((RetryContext, Throwable) -> Unit)? = null,
): Flow<T> = retryWith(
    retryPolicy {
        this.attempts = attempts
        this.delay    = delay
        this.backoff  = backoff
        this.retryOn  = retryOn
        this.onRetry  = onRetry
    }
)

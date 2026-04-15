package io.kotlinretry

import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

private val log = LoggerFactory.getLogger("io.kotlinretry.Retry")

// -------------------------------------------------------------------------------------------------
// Public DSL entry points
// -------------------------------------------------------------------------------------------------

/**
 * Retries [block] according to a [RetryPolicy] built with the DSL.
 *
 * ```kotlin
 * val result = retry {
 *     attempts  = 3
 *     delay     = 500.milliseconds
 *     backoff   = Backoff.exponential()
 *     retryOn   = { it is IOException }
 * } {
 *     callRemoteApi()
 * }
 * ```
 *
 * @throws RetryExhaustedException if all attempts fail
 */
suspend fun <T> retry(
    configure: RetryPolicy.Builder.() -> Unit = {},
    block: suspend (RetryContext) -> T,
): T = RetryPolicy.Builder().apply(configure).build().execute(block)

/**
 * Retries [block] and returns [default] if all attempts fail instead of throwing.
 *
 * ```kotlin
 * val price = retryOrDefault(default = 0.0) {
 *     fetchLivePrice(ticker)
 * }
 * ```
 */
suspend fun <T> retryOrDefault(
    default: T,
    configure: RetryPolicy.Builder.() -> Unit = {},
    block: suspend (RetryContext) -> T,
): T = runCatching { retry(configure, block) }.getOrDefault(default)

/**
 * Retries [block] and returns `null` if all attempts fail instead of throwing.
 */
suspend fun <T> retryOrNull(
    configure: RetryPolicy.Builder.() -> Unit = {},
    block: suspend (RetryContext) -> T,
): T? = runCatching { retry(configure, block) }.getOrNull()

// -------------------------------------------------------------------------------------------------
// RetryPolicy
// -------------------------------------------------------------------------------------------------

/**
 * Immutable policy that governs retry behaviour.
 * Build via [RetryPolicy.Builder] or the [retry] DSL.
 */
class RetryPolicy private constructor(
    val maxAttempts: Int,
    val initialDelay: Duration,
    val backoff: Backoff,
    val retryOn: (Throwable) -> Boolean,
    val onRetry: ((RetryContext, Throwable) -> Unit)?,
) {

    internal suspend fun <T> execute(block: suspend (RetryContext) -> T): T {
        var lastException: Throwable? = null

        for (attempt in 1..maxAttempts) {
            val ctx = RetryContext(attempt = attempt, maxAttempts = maxAttempts)
            try {
                return block(ctx)
            } catch (ex: Throwable) {
                if (!retryOn(ex)) throw ex          // non-retryable: rethrow immediately
                lastException = ex

                if (attempt < maxAttempts) {
                    val waitDuration = backoff.delayFor(attempt, initialDelay)
                    onRetry?.invoke(ctx, ex)
                    log.debug(
                        "Attempt {}/{} failed ({}), retrying in {}",
                        attempt, maxAttempts, ex.message, waitDuration
                    )
                    if (waitDuration > Duration.ZERO) delay(waitDuration)
                }
            }
        }

        throw RetryExhaustedException(maxAttempts, lastException!!)
    }

    // -- Builder -------------------------------------------------------------------------------

    class Builder {
        /**
         * Maximum number of total attempts (including the first try). Default: 3.
         */
        var attempts: Int = 3

        /**
         * Base delay before the first retry. Subsequent delays are computed by [backoff].
         * Default: 200ms.
         */
        var delay: Duration = 200.milliseconds

        /**
         * Backoff strategy. Default: [Backoff.exponential].
         */
        var backoff: Backoff = Backoff.exponential()

        /**
         * Predicate that decides whether an exception should trigger a retry.
         * Return `true` to retry, `false` to rethrow immediately.
         * Default: retry on any [Exception] (but not [Error]).
         */
        var retryOn: (Throwable) -> Boolean = { it is Exception }

        /**
         * Optional callback invoked before each retry delay.
         * Useful for logging, metrics, or alerting.
         */
        var onRetry: ((RetryContext, Throwable) -> Unit)? = null

        internal fun build() = RetryPolicy(
            maxAttempts  = attempts,
            initialDelay = delay,
            backoff      = backoff,
            retryOn      = retryOn,
            onRetry      = onRetry,
        )
    }
}

// -------------------------------------------------------------------------------------------------
// RetryContext
// -------------------------------------------------------------------------------------------------

/**
 * Contextual information available inside a retry block.
 *
 * ```kotlin
 * retry {
 *     attempts = 5
 * } { ctx ->
 *     if (ctx.isFirstAttempt) log.info("Starting operation")
 *     callApi()
 * }
 * ```
 */
data class RetryContext(
    /** Current attempt number (1-based). */
    val attempt: Int,
    /** Maximum number of attempts configured. */
    val maxAttempts: Int,
) {
    val isFirstAttempt: Boolean get() = attempt == 1
    val isLastAttempt:  Boolean get() = attempt == maxAttempts
    val retriesRemaining: Int   get() = maxAttempts - attempt
}

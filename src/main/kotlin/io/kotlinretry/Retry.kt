package io.kotlinretry

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

private val log = LoggerFactory.getLogger("io.kotlinretry.Retry")

/** Default number of total attempts (including the first try) used by [RetryPolicy.Builder]. */
private const val DEFAULT_MAX_ATTEMPTS = 3

/** Default base delay before the first retry. */
private val DEFAULT_DELAY = 200.milliseconds

/** Shared default strategy. [Backoff.exponential] is stateless, so one instance is reusable. */
private val DEFAULT_BACKOFF = Backoff.exponential()

/** Default predicate: retry any [Exception], but never an [Error]. */
private val DEFAULT_RETRY_ON: (Throwable) -> Boolean = { it is Exception }

// -------------------------------------------------------------------------------------------------
// Public DSL entry points
// -------------------------------------------------------------------------------------------------

/**
 * Retries [block] until it succeeds or [attempts] is exhausted.
 *
 * ```kotlin
 * val result = retry(attempts = 3, delay = 500.milliseconds, retryOn = { it is IOException }) {
 *     callRemoteApi()
 * }
 * ```
 *
 * For configuration you want to name and reuse, build a [retryPolicy] instead.
 *
 * @throws RetryExhaustedException if all attempts fail
 */
// A flat named-parameter list is the point of this overload: it keeps the operation in the
// trailing lambda, which a configuration block would otherwise occupy.
@Suppress("LongParameterList")
suspend fun <T> retry(
    attempts: Int = DEFAULT_MAX_ATTEMPTS,
    delay: Duration = DEFAULT_DELAY,
    backoff: Backoff = DEFAULT_BACKOFF,
    retryOn: (Throwable) -> Boolean = DEFAULT_RETRY_ON,
    onRetry: ((RetryContext, Throwable) -> Unit)? = null,
    block: suspend (RetryContext) -> T,
): T = retryPolicy {
    this.attempts = attempts
    this.delay    = delay
    this.backoff  = backoff
    this.retryOn  = retryOn
    this.onRetry  = onRetry
}.execute(block)

/**
 * Retries [block] and returns [default] if all attempts fail instead of throwing.
 *
 * ```kotlin
 * val price = retryOrDefault(default = 0.0) { fetchLivePrice(ticker) }
 * ```
 */
// Converting any failure into [default] is this function's whole contract.
@Suppress("LongParameterList", "TooGenericExceptionCaught")
suspend fun <T> retryOrDefault(
    default: T,
    attempts: Int = DEFAULT_MAX_ATTEMPTS,
    delay: Duration = DEFAULT_DELAY,
    backoff: Backoff = DEFAULT_BACKOFF,
    retryOn: (Throwable) -> Boolean = DEFAULT_RETRY_ON,
    onRetry: ((RetryContext, Throwable) -> Unit)? = null,
    block: suspend (RetryContext) -> T,
): T = try {
    retry(attempts, delay, backoff, retryOn, onRetry, block)
} catch (ex: CancellationException) {
    throw ex                       // never swallow cancellation
} catch (@Suppress("SwallowedException") ex: Throwable) {
    default
}

/**
 * Retries [block] and returns `null` if all attempts fail instead of throwing.
 */
// Converting any failure into null is this function's whole contract.
@Suppress("LongParameterList", "TooGenericExceptionCaught")
suspend fun <T> retryOrNull(
    attempts: Int = DEFAULT_MAX_ATTEMPTS,
    delay: Duration = DEFAULT_DELAY,
    backoff: Backoff = DEFAULT_BACKOFF,
    retryOn: (Throwable) -> Boolean = DEFAULT_RETRY_ON,
    onRetry: ((RetryContext, Throwable) -> Unit)? = null,
    block: suspend (RetryContext) -> T,
): T? = try {
    retry(attempts, delay, backoff, retryOn, onRetry, block)
} catch (ex: CancellationException) {
    throw ex                       // never swallow cancellation
} catch (@Suppress("SwallowedException") ex: Throwable) {
    null
}

/**
 * Builds a reusable [RetryPolicy].
 *
 * ```kotlin
 * val flaky = retryPolicy { attempts = 5; backoff = Backoff.jitter() }
 * val result = flaky.execute { callRemoteApi() }
 * ```
 */
fun retryPolicy(configure: RetryPolicy.Builder.() -> Unit = {}): RetryPolicy =
    RetryPolicy.Builder().apply(configure).build()

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

    // Retrying is only meaningful if every failure type can be inspected, so the catch is
    // deliberately broad; [retryOn] decides what is actually retryable.
    // Three exits are inherent: rethrow cancellation, rethrow a non-retryable failure, and
    // report exhaustion once the attempts run out.
    @Suppress("TooGenericExceptionCaught", "ThrowsCount")
    suspend fun <T> execute(block: suspend (RetryContext) -> T): T {
        var lastException: Throwable? = null

        for (attempt in 1..maxAttempts) {
            val ctx = RetryContext(attempt = attempt, maxAttempts = maxAttempts)
            try {
                return block(ctx)
            } catch (ex: CancellationException) {
                // Cancellation is not a failure of the operation - it is the caller withdrawing
                // it. Retrying here would defeat structured concurrency and swallow the timeout
                // that ResiliencePolicy layers on top.
                throw ex
            } catch (ex: Throwable) {
                if (!retryOn(ex)) throw ex          // non-retryable: rethrow immediately
                lastException = ex
                awaitNextAttempt(ctx, ex)
            }
        }

        throw RetryExhaustedException(maxAttempts, lastException!!)
    }

    /** Notifies [onRetry] and waits out the backoff delay, unless [ctx] was the final attempt. */
    private suspend fun awaitNextAttempt(ctx: RetryContext, ex: Throwable) {
        if (ctx.isLastAttempt) return

        val waitDuration = backoff.delayFor(ctx.attempt, initialDelay)
        onRetry?.invoke(ctx, ex)
        log.debug(
            "Attempt {}/{} failed ({}), retrying in {}",
            ctx.attempt, maxAttempts, ex.message, waitDuration
        )
        if (waitDuration > Duration.ZERO) delay(waitDuration)
    }

    // -- Builder -------------------------------------------------------------------------------

    class Builder {
        /**
         * Maximum number of total attempts (including the first try). Default: 3.
         */
        var attempts: Int = DEFAULT_MAX_ATTEMPTS

        /**
         * Base delay before the first retry. Subsequent delays are computed by [backoff].
         * Default: 200ms.
         */
        var delay: Duration = DEFAULT_DELAY

        /**
         * Backoff strategy. Default: [Backoff.exponential].
         */
        var backoff: Backoff = DEFAULT_BACKOFF

        /**
         * Predicate that decides whether an exception should trigger a retry.
         * Return `true` to retry, `false` to rethrow immediately.
         * Default: retry on any [Exception] (but not [Error]).
         */
        var retryOn: (Throwable) -> Boolean = DEFAULT_RETRY_ON

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
 * retry(configure = { attempts = 5 }) { ctx ->
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

package io.kotlinretry

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration

/**
 * Composes retry, circuit breaker, timeout, and fallback into a single resilient execution.
 *
 * Execution order (outermost to innermost):
 * ```
 * fallback( timeout( circuitBreaker( retry( block ) ) ) )
 * ```
 *
 * ```kotlin
 * val result: String = resilient("payment-service", configure = {
 *     retry {
 *         attempts = 3
 *         delay    = 200.milliseconds
 *         backoff  = Backoff.exponential()
 *     }
 *     circuitBreaker {
 *         failureThreshold = 5
 *         openDuration     = 30.seconds
 *     }
 *     timeout(5.seconds)
 *     fallback { "default" }
 * }) {
 *     callPaymentService()
 * }
 * ```
 *
 * Kotlin permits only one trailing lambda per call, so [configure] is passed by name
 * and [block] stays in the trailing position.
 */
suspend fun <T> resilient(
    name: String = "resilient",
    configure: ResilientBuilder<T>.() -> Unit,
    block: suspend (RetryContext) -> T,
): T = ResilientBuilder<T>(name).apply(configure).execute(block)

// -------------------------------------------------------------------------------------------------

class ResilientBuilder<T>(private val name: String) {

    private var retryConfig: (RetryPolicy.Builder.() -> Unit)? = null
    private var breakerConfig: (CircuitBreaker.Builder.() -> Unit)? = null
    private var timeoutDuration: Duration? = null
    private var fallbackFn: (suspend (Throwable) -> T)? = null

    /**
     * Configure retry behaviour. Omit to skip retries.
     */
    fun retry(configure: RetryPolicy.Builder.() -> Unit) {
        retryConfig = configure
    }

    /**
     * Attach a circuit breaker. A breaker named [name] is created (or use [use]).
     */
    fun circuitBreaker(configure: CircuitBreaker.Builder.() -> Unit) {
        breakerConfig = configure
    }

    /**
     * Attach a pre-existing [CircuitBreaker] instance (for sharing across call sites).
     */
    fun use(breaker: CircuitBreaker) {
        breakerConfig = null
        _breaker = breaker
    }

    private var _breaker: CircuitBreaker? = null

    /**
     * Wrap the entire execution (including retries) in a coroutine timeout.
     * Throws [OperationTimeoutException] on expiry.
     */
    fun timeout(duration: Duration) {
        timeoutDuration = duration
    }

    /**
     * Provide a fallback value when all other mechanisms fail.
     * The fallback receives the last exception.
     *
     * ```kotlin
     * fallback { ex -> cachedValue ?: throw ex }
     * ```
     */
    fun fallback(fn: suspend (Throwable) -> T) {
        fallbackFn = fn
    }

    /** Convenience: static fallback value. */
    fun fallback(value: T) {
        fallbackFn = { value }
    }

    // -- Execution ----------------------------------------------------------------------------

    // The fallback layer is the last line of defence and must see anything the inner
    // layers throw, hence the deliberately broad catch below.
    @Suppress("TooGenericExceptionCaught")
    internal suspend fun execute(block: suspend (RetryContext) -> T): T {
        val breaker  = _breaker ?: breakerConfig?.let { circuitBreaker(name, it) }
        val fallback = fallbackFn
        val timeout  = timeoutDuration
        val retry    = retryConfig

        suspend fun runCore(): T {
            val operation: suspend (RetryContext) -> T = block

            // Innermost: raw block, optionally wrapped in circuit breaker
            val guarded: suspend (RetryContext) -> T = if (breaker != null) {
                { ctx -> breaker.execute { operation(ctx) } }
            } else {
                operation
            }

            // Retry layer
            return if (retry != null) {
                io.kotlinretry.retry(retry, guarded)
            } else {
                guarded(RetryContext(attempt = 1, maxAttempts = 1))
            }
        }

        // Timeout layer
        val timed: suspend () -> T = if (timeout != null) {
            {
                try {
                    withTimeout(timeout) { runCore() }
                } catch (ex: TimeoutCancellationException) {
                    throw OperationTimeoutException(
                        "Operation '$name' timed out after $timeout", ex
                    )
                }
            }
        } else {
            { runCore() }
        }

        // Fallback layer (outermost)
        return if (fallback != null) {
            try {
                timed()
            } catch (ex: Throwable) {
                fallback(ex)
            }
        } else {
            timed()
        }
    }
}

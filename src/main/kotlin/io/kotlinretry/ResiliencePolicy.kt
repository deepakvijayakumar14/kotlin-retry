package io.kotlinretry

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration

/**
 * A reusable composition of retry, circuit breaker and timeout.
 *
 * Build the policy **once** and execute it many times. This matters: a circuit breaker is
 * stateful, so one rebuilt per call would start CLOSED every time and could never open.
 *
 * ```kotlin
 * val payments = resiliencePolicy("payment-service") {
 *     retry          { attempts = 3; backoff = Backoff.exponential() }
 *     circuitBreaker { failureThreshold = 5; openDuration = 30.seconds }
 *     timeout(5.seconds)
 * }
 *
 * val result = payments.execute { callPaymentService() }
 * ```
 *
 * Execution order, outermost to innermost:
 * ```
 * fallback( timeout( circuitBreaker( retry( block ) ) ) )
 * ```
 *
 * The policy itself is not generic, so a single instance serves call sites returning different
 * types. A fallback is supplied per call via [executeOrElse], where the result type is known.
 */
class ResiliencePolicy internal constructor(
    val name: String,
    private val retryPolicy: RetryPolicy?,
    private val breaker: CircuitBreaker?,
    private val timeout: Duration?,
) {

    /** The circuit breaker guarding this policy, or `null` if none was configured. */
    val circuitBreaker: CircuitBreaker? get() = breaker

    /**
     * Runs [block] through the policy, rethrowing whatever escapes it.
     *
     * @throws CircuitBreakerOpenException if the breaker is OPEN
     * @throws OperationTimeoutException if the configured timeout expires
     * @throws RetryExhaustedException if every retry attempt fails
     */
    suspend fun <T> execute(block: suspend (RetryContext) -> T): T {
        val guarded: suspend (RetryContext) -> T =
            if (breaker != null) { ctx -> breaker.execute { block(ctx) } } else block

        suspend fun runCore(): T =
            if (retryPolicy != null) retryPolicy.execute(guarded)
            else guarded(RetryContext(attempt = 1, maxAttempts = 1))

        return if (timeout != null) runWithTimeout(timeout) { runCore() } else runCore()
    }

    /**
     * Runs [block] through the policy, handing the failure to [fallback] if everything else fails.
     *
     * ```kotlin
     * val price = pricing.executeOrElse(fallback = { cachedPrice }) { fetchLivePrice() }
     * ```
     */
    // The fallback is the last line of defence and must see anything the inner layers throw.
    @Suppress("TooGenericExceptionCaught")
    suspend fun <T> executeOrElse(
        fallback: suspend (Throwable) -> T,
        block: suspend (RetryContext) -> T,
    ): T = try {
        execute(block)
    } catch (ex: CancellationException) {
        // The caller withdrew the work; producing a fallback value would ignore that.
        throw ex
    } catch (ex: Throwable) {
        fallback(ex)
    }

    private suspend fun <T> runWithTimeout(duration: Duration, block: suspend () -> T): T =
        try {
            withTimeout(duration) { block() }
        } catch (ex: TimeoutCancellationException) {
            throw OperationTimeoutException("Operation '$name' timed out after $duration", ex)
        }
}

// -------------------------------------------------------------------------------------------------

class ResiliencePolicyBuilder internal constructor(private val name: String) {

    private var retryConfig: (RetryPolicy.Builder.() -> Unit)? = null
    private var breakerConfig: (CircuitBreaker.Builder.() -> Unit)? = null
    private var sharedBreaker: CircuitBreaker? = null
    private var timeoutDuration: Duration? = null

    /** Configure retry behaviour. Omit to skip retries. */
    fun retry(configure: RetryPolicy.Builder.() -> Unit) {
        retryConfig = configure
    }

    /** Give this policy its own circuit breaker, named after the policy. */
    fun circuitBreaker(configure: CircuitBreaker.Builder.() -> Unit) {
        sharedBreaker = null
        breakerConfig = configure
    }

    /** Guard this policy with an existing breaker, so several policies share failure counts. */
    fun use(breaker: CircuitBreaker) {
        breakerConfig = null
        sharedBreaker = breaker
    }

    /** Wrap the whole execution, retries included, in a coroutine timeout. */
    fun timeout(duration: Duration) {
        timeoutDuration = duration
    }

    internal fun build(): ResiliencePolicy = ResiliencePolicy(
        name = name,
        retryPolicy = retryConfig?.let { retryPolicy(it) },
        // Built here, once per policy - never per call. A breaker constructed per call would
        // start CLOSED every time and so could never open.
        breaker = sharedBreaker ?: breakerConfig?.let { io.kotlinretry.circuitBreaker(name, it) },
        timeout = timeoutDuration,
    )
}

/**
 * Builds a reusable [ResiliencePolicy]. Hold onto the result; do not rebuild it per call.
 *
 * ```kotlin
 * val inventory = resiliencePolicy("inventory-service") {
 *     retry          { attempts = 2 }
 *     circuitBreaker { failureThreshold = 5 }
 * }
 * ```
 */
fun resiliencePolicy(
    name: String = "resilient",
    configure: ResiliencePolicyBuilder.() -> Unit = {},
): ResiliencePolicy = ResiliencePolicyBuilder(name).apply(configure).build()

package io.kotlinretry

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Default consecutive failures before the circuit opens, used by [CircuitBreaker.Builder]. */
private const val DEFAULT_FAILURE_THRESHOLD = 5

/**
 * Coroutine-safe circuit breaker with three states: CLOSED, OPEN, HALF_OPEN.
 *
 * ```
 *  CLOSED  ──(failures >= threshold)──>  OPEN
 *  OPEN    ──(openDuration elapsed)───>  HALF_OPEN
 *  HALF_OPEN ──(success)──────────────>  CLOSED
 *  HALF_OPEN ──(failure)──────────────>  OPEN
 * ```
 *
 * Create via the [circuitBreaker] DSL:
 *
 * ```kotlin
 * val breaker = circuitBreaker("payment-service") {
 *     failureThreshold = 5
 *     successThreshold = 2
 *     openDuration     = 30.seconds
 *     retryOn          = { it is IOException }
 * }
 *
 * val result = breaker.execute { callPaymentService() }
 * ```
 *
 * @throws CircuitBreakerOpenException if the breaker is OPEN and rejects the call
 */
class CircuitBreaker private constructor(private val config: Config) {

    private val log = LoggerFactory.getLogger("io.kotlinretry.CircuitBreaker")

    private val mutex        = Mutex()
    private val state        = AtomicReference(State.CLOSED)
    private val failureCount = AtomicInteger(0)
    private val successCount = AtomicInteger(0)
    // Read by resolvedState() without holding [mutex], so it must be published safely.
    @Volatile
    private var openedAt: Instant? = null

    val name: String get() = config.name
    val currentState: State get() = resolvedState()

    // -- Execute ------------------------------------------------------------------------------

    /**
     * Executes [block] through the circuit breaker.
     *
     * - CLOSED: calls are passed through; failures are counted
     * - OPEN: calls are rejected with [CircuitBreakerOpenException]
     * - HALF_OPEN: one probe call is allowed through to test recovery
     */
    // The breaker must observe every failure type before deciding; [Config.recordFailure]
    // narrows down what counts as a circuit failure.
    @Suppress("TooGenericExceptionCaught")
    suspend fun <T> execute(block: suspend () -> T): T {
        val current = resolvedState()

        // A rejected call is not a state transition, so onStateChange stays silent here -
        // otherwise the alerting hook fires once per shed request while the circuit is open.
        if (current == State.OPEN) throw CircuitBreakerOpenException(name)

        return try {
            val result = block()
            onSuccess(current)
            result
        } catch (ex: Throwable) {
            if (config.recordFailure(ex)) onFailure(current)
            throw ex
        }
    }

    // -- State transitions --------------------------------------------------------------------

    private suspend fun onSuccess(priorState: State) {
        when (priorState) {
            State.CLOSED     -> failureCount.set(0)
            State.HALF_OPEN  -> {
                val count = successCount.incrementAndGet()
                if (count >= config.successThreshold) {
                    mutex.withLock { transitionTo(State.CLOSED) }
                }
            }
            State.OPEN -> { /* shouldn't happen */ }
        }
    }

    private suspend fun onFailure(priorState: State) {
        when (priorState) {
            State.CLOSED -> {
                val count = failureCount.incrementAndGet()
                log.debug("[{}] Failure {}/{}", name, count, config.failureThreshold)
                if (count >= config.failureThreshold) {
                    mutex.withLock { transitionTo(State.OPEN) }
                }
            }
            State.HALF_OPEN -> {
                mutex.withLock { transitionTo(State.OPEN) }
            }
            State.OPEN -> { /* already open */ }
        }
    }

    private fun transitionTo(next: State) {
        // Stamp the open time *before* publishing OPEN. Writing it afterwards leaves a window in
        // which a reader sees OPEN alongside the timestamp from a previous cycle, judges
        // openDuration long elapsed, and flips the circuit straight back to HALF_OPEN.
        if (next == State.OPEN) openedAt = Instant.now()

        val prev = state.getAndSet(next)
        if (prev == next) return

        when (next) {
            State.OPEN -> {
                failureCount.set(0)
                successCount.set(0)
                log.warn("[{}] Circuit OPENED after {} failures", name, config.failureThreshold)
            }
            State.HALF_OPEN -> {
                successCount.set(0)
                log.info("[{}] Circuit HALF-OPEN - probing recovery", name)
            }
            State.CLOSED -> {
                failureCount.set(0)
                successCount.set(0)
                openedAt = null
                log.info("[{}] Circuit CLOSED - service recovered", name)
            }
        }
        config.onStateChange?.invoke(name, prev, next)
    }

    /** Resolves OPEN -> HALF_OPEN if [Config.openDuration] has elapsed. */
    private fun resolvedState(): State {
        val current = state.get()
        if (current != State.OPEN) return current

        val opened = openedAt
        val openDurationElapsed = opened != null &&
            Instant.now().isAfter(opened.plusMillis(config.openDuration.inWholeMilliseconds))

        // Only the thread that wins the CAS owns this transition. Firing the callback for every
        // observer instead would emit one "state change" per read of currentState.
        // successCount is already 0 here: every path into OPEN goes through transitionTo, which
        // zeroes it. Re-zeroing would race with a probe that has already succeeded.
        if (openDurationElapsed && state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
            config.onStateChange?.invoke(name, State.OPEN, State.HALF_OPEN)
            log.info("[{}] Circuit HALF-OPEN - openDuration elapsed", name)
        }
        return state.get()
    }

    /** Manually resets the circuit breaker to CLOSED state. */
    suspend fun reset() = mutex.withLock { transitionTo(State.CLOSED) }

    // -- State enum ---------------------------------------------------------------------------

    enum class State { CLOSED, OPEN, HALF_OPEN }

    // -- Config -------------------------------------------------------------------------------

    data class Config(
        val name: String,
        val failureThreshold: Int,
        val successThreshold: Int,
        val openDuration: Duration,
        val recordFailure: (Throwable) -> Boolean,
        val onStateChange: ((name: String, from: State, to: State) -> Unit)?,
    )

    // -- Builder ------------------------------------------------------------------------------

    class Builder(private val name: String) {
        /** Number of consecutive failures before the circuit opens. Default: 5. */
        var failureThreshold: Int  = DEFAULT_FAILURE_THRESHOLD

        /** Number of consecutive successes in HALF_OPEN before the circuit closes. Default: 2. */
        var successThreshold: Int  = 2

        /** How long the circuit stays OPEN before allowing a probe. Default: 30s. */
        var openDuration: Duration = 30.seconds

        /**
         * Predicate to decide if an exception counts as a circuit-breaker failure.
         * Default: all [Exception]s count (but not [Error]s).
         */
        var recordFailure: (Throwable) -> Boolean = { it is Exception }

        /** Optional callback fired on every state transition. Useful for metrics/alerting. */
        var onStateChange: ((name: String, from: State, to: State) -> Unit)? = null

        internal fun build() = CircuitBreaker(
            Config(name, failureThreshold, successThreshold, openDuration, recordFailure, onStateChange)
        )
    }
}

/**
 * Creates a [CircuitBreaker] using the builder DSL.
 *
 * ```kotlin
 * val breaker = circuitBreaker("payments") {
 *     failureThreshold = 5
 *     openDuration     = 30.seconds
 *     onStateChange    = { name, from, to -> metrics.record(name, to.name) }
 * }
 * ```
 */
fun circuitBreaker(name: String, configure: CircuitBreaker.Builder.() -> Unit = {}): CircuitBreaker =
    CircuitBreaker.Builder(name).apply(configure).build()

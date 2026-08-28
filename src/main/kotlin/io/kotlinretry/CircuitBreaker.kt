package io.kotlinretry

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** Default consecutive failures before the circuit opens, used by [CircuitBreaker.Builder]. */
private const val DEFAULT_FAILURE_THRESHOLD = 5

/** Default consecutive probe successes before the circuit closes again. */
private const val DEFAULT_SUCCESS_THRESHOLD = 2

/** Default number of probe calls allowed through at once while HALF_OPEN. */
private const val DEFAULT_PERMITTED_CALLS_IN_HALF_OPEN = 1

/**
 * Coroutine-safe circuit breaker with three states: CLOSED, OPEN, HALF_OPEN.
 *
 * ```
 *  CLOSED  ──(failures >= threshold)──>  OPEN
 *  OPEN    ──(openDuration elapsed)───>  HALF_OPEN
 *  HALF_OPEN ──(successThreshold successes)──>  CLOSED
 *  HALF_OPEN ──(failure)──────────────>  OPEN
 * ```
 *
 * While HALF_OPEN, at most [Config.permittedCallsInHalfOpen] calls run at a time; the rest are
 * rejected as if the circuit were still OPEN. That keeps a recovering dependency from being
 * flooded the moment the open window expires.
 *
 * Create via the [circuitBreaker] DSL:
 *
 * ```kotlin
 * val breaker = circuitBreaker("payment-service") {
 *     failureThreshold = 5
 *     successThreshold = 2
 *     openDuration     = 30.seconds
 *     recordFailure    = { it is IOException }
 * }
 *
 * val result = breaker.execute { callPaymentService() }
 * ```
 *
 * @throws CircuitBreakerOpenException if the breaker rejects the call
 */
class CircuitBreaker private constructor(private val config: Config) {

    private val log = LoggerFactory.getLogger("io.kotlinretry.CircuitBreaker")

    private val mutex        = Mutex()
    private val state        = AtomicReference(State.CLOSED)
    private val failureCount = AtomicInteger(0)
    private val successCount = AtomicInteger(0)

    /**
     * Probe slots available right now. Non-zero only while HALF_OPEN: every other state zeroes it,
     * so a caller that reads HALF_OPEN before the allowance is published is simply turned away.
     */
    private val halfOpenPermits = AtomicInteger(0)

    // Read by resolvedState() without holding [mutex], so it must be published safely.
    @Volatile
    private var openedAt: TimeMark? = null

    val name: String get() = config.name

    /**
     * The state the next call would see.
     *
     * Reading this resolves an expired open window, so an observer can move the breaker from OPEN
     * to HALF_OPEN (firing [Config.onStateChange] once) without executing anything.
     */
    val currentState: State get() = resolvedState()

    // -- Execute ------------------------------------------------------------------------------

    /**
     * Executes [block] through the circuit breaker.
     *
     * - CLOSED: calls are passed through; failures are counted
     * - OPEN: calls are rejected with [CircuitBreakerOpenException]
     * - HALF_OPEN: up to [Config.permittedCallsInHalfOpen] probe calls run at a time to test
     *   recovery; calls beyond that allowance are rejected with [CircuitBreakerOpenException]
     */
    // The breaker must observe every failure type before deciding; [Config.recordFailure]
    // narrows down what counts as a circuit failure.
    // Four exits are inherent: reject while OPEN, reject a probe with no permit, rethrow
    // cancellation, rethrow a recorded failure after counting it.
    @Suppress("TooGenericExceptionCaught", "ThrowsCount")
    suspend fun <T> execute(block: suspend () -> T): T {
        val current = resolvedState()

        // A rejected call is not a state transition, so onStateChange stays silent here -
        // otherwise the alerting hook fires once per shed request while the circuit is open.
        if (current == State.OPEN) throw CircuitBreakerOpenException(name)

        val isProbe = current == State.HALF_OPEN
        if (isProbe && !acquireProbePermit()) throw CircuitBreakerOpenException(name)

        return try {
            val result = block()
            onSuccess(current)
            result
        } catch (ex: CancellationException) {
            // A withdrawn call says nothing about the dependency's health, so it must not
            // count towards opening the circuit.
            throw ex
        } catch (ex: Throwable) {
            if (config.recordFailure(ex)) onFailure(current)
            throw ex
        } finally {
            // Runs after the transition above, so a probe that decided the outcome finds the
            // breaker already CLOSED or OPEN and releases nothing.
            if (isProbe) releaseProbePermit()
        }
    }

    // -- Half-open probe permits ----------------------------------------------------------------

    /** Takes one probe slot, or returns `false` if the half-open allowance is already spent. */
    private fun acquireProbePermit(): Boolean {
        while (true) {
            val available = halfOpenPermits.get()
            if (available <= 0) return false
            if (halfOpenPermits.compareAndSet(available, available - 1)) return true
        }
    }

    /**
     * Returns a probe slot once its call finishes, so the *next* probe can run.
     *
     * With [Config.successThreshold] above 1 the breaker needs several probes in a row, and
     * without this release the first one would hold the only slot forever.
     */
    private fun releaseProbePermit() {
        // Any other state zeroes the counter, and re-entering HALF_OPEN republishes the full
        // allowance, so a release that races a transition is discarded rather than leaked.
        if (state.get() != State.HALF_OPEN) return
        halfOpenPermits.updateAndGet { held ->
            if (held < config.permittedCallsInHalfOpen) held + 1 else held
        }
    }

    // -- State transitions --------------------------------------------------------------------

    private suspend fun onSuccess(priorState: State) {
        when (priorState) {
            State.CLOSED     -> failureCount.set(0)
            State.HALF_OPEN  -> {
                val count = successCount.incrementAndGet()
                if (count >= config.successThreshold) {
                    closeIfStillProbing()
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
                    applyTransition(State.OPEN)
                }
            }
            State.HALF_OPEN -> applyTransition(State.OPEN)
            State.OPEN -> { /* already open */ }
        }
    }

    /**
     * Applies the change and returns the state it replaced, or `null` if the breaker was already
     * in [next]. Callers must hold [mutex]. [Config.onStateChange] is deliberately not fired
     * here - see [applyTransition].
     */
    private fun transitionTo(next: State): State? {
        // Stamp the open time *before* publishing OPEN. Writing it afterwards leaves a window in
        // which a reader sees OPEN alongside the timestamp from a previous cycle, judges
        // openDuration long elapsed, and flips the circuit straight back to HALF_OPEN.
        if (next == State.OPEN) openedAt = config.timeSource.markNow()

        val prev = state.getAndSet(next)
        if (prev == next) return null

        when (next) {
            State.OPEN -> {
                failureCount.set(0)
                successCount.set(0)
                halfOpenPermits.set(0)
                log.warn("[{}] Circuit OPENED after {} failures", name, config.failureThreshold)
            }
            // HALF_OPEN is reached only through the lock-free CAS in resolvedState(). Coming
            // through here would bypass that CAS and could clobber a concurrent transition.
            State.HALF_OPEN -> Unit
            State.CLOSED -> {
                failureCount.set(0)
                successCount.set(0)
                halfOpenPermits.set(0)
                openedAt = null
                log.info("[{}] Circuit CLOSED - service recovered", name)
            }
        }
        return prev
    }

    /**
     * Closes the circuit, unless a sibling probe has already reopened it.
     *
     * Reachable only with [Config.permittedCallsInHalfOpen] above 1, where two probes run at once:
     * if one fails and reopens the circuit while the other is still in flight, the survivor's
     * success must not discard that fresh evidence of a sick dependency.
     */
    private suspend fun closeIfStillProbing() {
        val prev = mutex.withLock {
            if (state.get() != State.HALF_OPEN) null else transitionTo(State.CLOSED)
        } ?: return
        config.onStateChange?.invoke(name, prev, State.CLOSED)
    }

    /**
     * Transitions under [mutex], then fires [Config.onStateChange] *after* releasing it.
     *
     * The callback is user code. Running it under the lock serialises every transition behind it,
     * and a callback that re-enters the breaker deadlocks on the non-reentrant [Mutex].
     */
    private suspend fun applyTransition(next: State) {
        val prev = mutex.withLock { transitionTo(next) } ?: return
        config.onStateChange?.invoke(name, prev, next)
    }

    /** Resolves OPEN -> HALF_OPEN if [Config.openDuration] has elapsed. */
    private fun resolvedState(): State {
        val current = state.get()
        if (current != State.OPEN) return current

        val opened = openedAt
        val openDurationElapsed = opened != null && opened.elapsedNow() >= config.openDuration

        // Only the thread that wins the CAS owns this transition. Firing the callback for every
        // observer instead would emit one "state change" per read of currentState.
        // successCount is already 0 here: every path into OPEN goes through transitionTo, which
        // zeroes it. Re-zeroing would race with a probe that has already succeeded.
        if (openDurationElapsed && state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
            // Published after the CAS, so a caller that observes HALF_OPEN a moment early reads
            // the 0 left behind by transitionTo(OPEN) and is turned away. Erring towards one
            // probe too few keeps the allowance a ceiling; publishing first could exceed it when
            // this CAS loses to a concurrent transition.
            halfOpenPermits.set(config.permittedCallsInHalfOpen)
            config.onStateChange?.invoke(name, State.OPEN, State.HALF_OPEN)
            log.info("[{}] Circuit HALF-OPEN - openDuration elapsed", name)
        }
        return state.get()
    }

    /** Manually resets the circuit breaker to CLOSED state. */
    suspend fun reset() = applyTransition(State.CLOSED)

    // -- State enum ---------------------------------------------------------------------------

    enum class State { CLOSED, OPEN, HALF_OPEN }

    // -- Config -------------------------------------------------------------------------------

    // One field per builder knob, plus the injectable clock; a parameter object per group would
    // buy nothing here.
    @Suppress("LongParameterList")
    data class Config(
        val name: String,
        val failureThreshold: Int,
        val successThreshold: Int,
        val permittedCallsInHalfOpen: Int,
        val openDuration: Duration,
        val recordFailure: (Throwable) -> Boolean,
        val onStateChange: ((name: String, from: State, to: State) -> Unit)?,
        // Monotonic by default: the open window must not be lengthened or cut short by an NTP
        // step or a manual clock change, which is exactly what a wall clock would allow.
        val timeSource: TimeSource = TimeSource.Monotonic,
    )

    // -- Builder ------------------------------------------------------------------------------

    class Builder(private val name: String) {
        /** Number of consecutive failures before the circuit opens. Must be >= 1. Default: 5. */
        var failureThreshold: Int  = DEFAULT_FAILURE_THRESHOLD

        /**
         * Number of consecutive successes in HALF_OPEN before the circuit closes. Must be >= 1.
         * Default: 2.
         *
         * Probes run one at a time unless [permittedCallsInHalfOpen] says otherwise, so a
         * threshold of 2 means two successful probes in sequence.
         */
        var successThreshold: Int  = DEFAULT_SUCCESS_THRESHOLD

        /**
         * How many probe calls may run at a time while HALF_OPEN. Must be >= 1. Default: 1.
         *
         * Raise it only if the dependency can take the concurrent load of a recovery test; the
         * point of the default is that a half-open circuit trickles traffic rather than releasing
         * everything that queued up while it was open.
         */
        var permittedCallsInHalfOpen: Int = DEFAULT_PERMITTED_CALLS_IN_HALF_OPEN

        /** How long the circuit stays OPEN before allowing a probe. Must not be negative. Default: 30s. */
        var openDuration: Duration = 30.seconds

        /**
         * Predicate to decide if an exception counts as a circuit-breaker failure.
         * Default: all [Exception]s count (but not [Error]s).
         */
        var recordFailure: (Throwable) -> Boolean = { it is Exception }

        /**
         * Optional callback fired on every state transition. Useful for metrics/alerting.
         *
         * Invoked outside the breaker's lock, so it may call back into the breaker. The trade-off
         * is that concurrent transitions may deliver their callbacks in an order that differs from
         * the order the transitions actually happened in.
         */
        var onStateChange: ((name: String, from: State, to: State) -> Unit)? = null

        /** Clock behind [openDuration]. Swapped in tests so the open window can be advanced. */
        internal var timeSource: TimeSource = TimeSource.Monotonic

        internal fun build(): CircuitBreaker {
            // Caught here rather than at the first call: a breaker that can never open is a
            // configuration mistake, and finding out at 3am is worse than finding out at startup.
            require(failureThreshold >= 1) {
                "failureThreshold must be at least 1, but was $failureThreshold"
            }
            require(successThreshold >= 1) {
                "successThreshold must be at least 1, but was $successThreshold"
            }
            require(permittedCallsInHalfOpen >= 1) {
                "permittedCallsInHalfOpen must be at least 1, but was $permittedCallsInHalfOpen"
            }
            require(openDuration >= Duration.ZERO) {
                "openDuration must not be negative, but was $openDuration"
            }

            return CircuitBreaker(
                Config(
                    name = name,
                    failureThreshold = failureThreshold,
                    successThreshold = successThreshold,
                    permittedCallsInHalfOpen = permittedCallsInHalfOpen,
                    openDuration = openDuration,
                    recordFailure = recordFailure,
                    onStateChange = onStateChange,
                    timeSource = timeSource,
                )
            )
        }
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
 *
 * @throws IllegalArgumentException if any threshold is below 1 or [CircuitBreaker.Builder.openDuration]
 *   is negative
 */
fun circuitBreaker(name: String, configure: CircuitBreaker.Builder.() -> Unit = {}): CircuitBreaker =
    CircuitBreaker.Builder(name).apply(configure).build()

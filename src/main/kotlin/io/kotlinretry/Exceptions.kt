package io.kotlinretry

/**
 * Thrown when all retry attempts are exhausted without a successful result.
 *
 * @param attempts total number of attempts made
 * @param cause the last exception thrown by the operation
 */
class RetryExhaustedException(
    val attempts: Int,
    cause: Throwable,
) : Exception("Operation failed after $attempts attempt(s): ${cause.message}", cause)

/**
 * Thrown when a [CircuitBreaker] is in the OPEN state and rejects a call.
 *
 * @param name the circuit breaker's name
 */
class CircuitBreakerOpenException(val name: String) :
    Exception("CircuitBreaker '$name' is OPEN - calls are being rejected")

/**
 * Thrown when an operation exceeds its configured timeout.
 * Wraps [kotlinx.coroutines.TimeoutCancellationException] with additional context.
 */
class OperationTimeoutException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

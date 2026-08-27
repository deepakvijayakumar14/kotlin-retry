# kotlin-retry

[![Maven Central](https://img.shields.io/maven-central/v/io.github.deepakvijayakumar14/kotlin-retry.svg)](https://central.sonatype.com/artifact/io.github.deepakvijayakumar14/kotlin-retry)
[![CI](https://github.com/deepakvijayakumar14/kotlin-retry/actions/workflows/ci.yml/badge.svg)](https://github.com/deepakvijayakumar14/kotlin-retry/actions)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

A lightweight, coroutine-native resilience DSL for Kotlin. Composable retry, circuit breaker, timeout, and fallback — without the weight of Resilience4j.

```kotlin
// Build the policy once - it owns the circuit breaker's state.
val payments = resiliencePolicy("payment-service") {
    retry          { attempts = 3; backoff = Backoff.exponential() }
    circuitBreaker { failureThreshold = 5; openDuration = 30.seconds }
    timeout(5.seconds)
}

// Execute it anywhere.
val result = payments.executeOrElse(fallback = { "cached-result" }) {
    callPaymentService()
}
```

---

## Why not Resilience4j?

Resilience4j is excellent but designed for Java: registry-based, annotation-heavy, and callback-oriented. `kotlin-retry` is built around Kotlin coroutines and DSL idioms from the ground up:

| | kotlin-retry | Resilience4j |
|---|:---:|:---:|
| Coroutine-native (`suspend`) | YES | Partial |
| `Flow` operator | YES | NO |
| DSL configuration | YES | NO |
| Zero code generation | YES | YES |
| Dependency footprint | Coroutines only | 10+ modules |
| Registry required | NO | YES |

---

## Installation

The Maven coordinate uses the `io.github.deepakvijayakumar14` namespace; the Kotlin package is
`io.kotlinretry`. Release history is in [CHANGELOG.md](CHANGELOG.md).

```kotlin
dependencies {
    implementation("io.github.deepakvijayakumar14:kotlin-retry:0.2.0")
}
```

---

## Retry

```kotlin
// Basic retry
val result = retry(attempts = 3, delay = 200.milliseconds, backoff = Backoff.exponential()) {
    fetchFromApi()
}

// Only retry specific exceptions
retry(attempts = 5, retryOn = { it is IOException || it is TimeoutException }) {
    callRemoteService()
}

// Get context inside the block
retry(attempts = 3) { ctx ->
    if (ctx.isFirstAttempt) log.info("Starting operation")
    log.debug("Attempt ${ctx.attempt} of ${ctx.maxAttempts}")
    callApi()
}

// Return a default instead of throwing
val price = retryOrDefault(default = lastKnownPrice) {
    fetchLivePrice(ticker)
}

// Return null instead of throwing
val data: MyData? = retryOrNull { fetchData() }
```

Configuration you want to name and reuse becomes a policy:

```kotlin
val flaky = retryPolicy { attempts = 5; backoff = Backoff.jitter() }

val a = flaky.execute { callServiceA() }
val b = flaky.execute { callServiceB() }
```

---

## Backoff strategies

```kotlin
Backoff.none                         // No delay between attempts
Backoff.fixed                        // Same delay every time
Backoff.linear                       // Delay grows linearly: 200ms, 400ms, 600ms...
Backoff.exponential()                // Doubles each attempt: 200ms, 400ms, 800ms... (default)
Backoff.exponential(multiplier = 3.0, maxDelay = 60.seconds)
Backoff.jitter()                     // Full jitter: random up to exponential ceiling
Backoff.decorrelatedJitter()         // Decorrelated jitter (best for distributed systems)
```

---

## Circuit Breaker

```kotlin
val breaker = circuitBreaker("downstream-api") {
    failureThreshold = 5          // Open after 5 consecutive failures
    successThreshold = 2          // Close again after 2 successes in HALF_OPEN
    openDuration     = 30.seconds // Wait before probing recovery
    recordFailure    = { it is IOException || it is HttpException }
    onStateChange    = { name, from, to ->
        metrics.increment("circuit_breaker.transition", "name" to name, "state" to to.name)
    }
}

val result = breaker.execute { callDownstreamApi() }
```

States: `CLOSED` (normal) -> `OPEN` (rejecting calls) -> `HALF_OPEN` (probing) -> `CLOSED`.

---

## Timeout

Wraps the entire execution (including retries) in a coroutine timeout:

```kotlin
val search = resiliencePolicy("search") {
    retry   { attempts = 3 }
    timeout(10.seconds)              // Total budget across all attempts
}

val result = search.execute { callSearchApi() }
```

---

## Fallback

A fallback belongs to the call, not the policy - that is what lets one policy serve call sites
returning different types.

```kotlin
val cache = resiliencePolicy("cache") { retry { attempts = 3 } }

// Static value
cache.executeOrElse(fallback = { emptyList() }) {
    fetchFromDatabase()
}

// Dynamic fallback with access to the exception
cache.executeOrElse(
    fallback = { ex ->
        log.warn("Pricing unavailable: ${ex.message}, returning cached price")
        cachedPrice
    },
) {
    fetchLivePrice()
}
```

---

## Composing everything

A policy composes all four mechanisms. Execution order:

```
fallback( timeout( circuitBreaker( retry( block ) ) ) )
```

```kotlin
val payments = resiliencePolicy("payment-processor") {
    retry {
        attempts = 3
        delay    = 100.milliseconds
        backoff  = Backoff.jitter()
        retryOn  = { it is IOException }
    }
    circuitBreaker {
        failureThreshold = 5
        openDuration     = 30.seconds
        onStateChange    = { name, _, to -> alerting.notify(name, to) }
    }
    timeout(8.seconds)
}

val result = payments.executeOrElse(
    fallback = { ex ->
        when (ex) {
            is CircuitBreakerOpenException -> PaymentResult.serviceUnavailable()
            is OperationTimeoutException   -> PaymentResult.timeout()
            else                           -> throw ex
        }
    },
) { ctx ->
    log.debug("Payment attempt ${ctx.attempt}")
    paymentGateway.charge(request)
}
```

---

## Sharing a circuit breaker

```kotlin
// Create once, share across multiple call sites
val breaker = circuitBreaker("inventory-service") {
    failureThreshold = 5
}

// In service A
val getStock = resiliencePolicy("get-stock") {
    use(breaker)
    retry { attempts = 2 }
}

// In service B - same breaker, shared failure count
val reserveStock = resiliencePolicy("reserve-stock") { use(breaker) }

getStock.execute { getStock(itemId) }
reserveStock.execute { reserveStock(itemId, qty) }
```

---

## Flow

`retryWith` re-collects a failing upstream flow using the same policies as the rest of the library:

```kotlin
val prices = priceUpdates()
    .retryWith(attempts = 5, delay = 200.milliseconds, backoff = Backoff.jitter())
    .catch { emit(Price.unavailable()) }

// or with a policy you already have
val events = userEvents().retryWith(streamingPolicy)
```

Two things differ from `retry { }`, both inherent to retrying a cold flow:

- **The upstream is re-collected from the start.** A flow that fails after emitting will re-emit
  those values on the next attempt, so downstream sees repeats. Deduplicate if that matters.
- **The original exception propagates** once attempts run out, rather than being wrapped in
  `RetryExhaustedException`. That keeps downstream `catch { it is IOException }` working.

Cancellation is never retried, as everywhere else.

---

## Build policies once

A policy owns its circuit breaker, and a breaker is only meaningful if it accumulates failures
across calls. Build policies at startup and hold them; rebuilding one per call gives every call a
fresh breaker that can never open.

```kotlin
// Correct: one policy, reused.
class PaymentClient(private val gateway: Gateway) {
    private val policy = resiliencePolicy("payments") {
        circuitBreaker { failureThreshold = 5 }
    }

    suspend fun charge(request: Request) = policy.execute { gateway.charge(request) }
}
```

---

## Cancellation

Cancellation is the caller withdrawing the work, not a failure of it. No layer treats it as one:
it is never retried, never counted towards opening a circuit, and never converted into a fallback
value. `CancellationException` always propagates, so structured concurrency behaves as expected.

```kotlin
val job = launch {
    retry(attempts = Int.MAX_VALUE) { pollForever() }
}
job.cancel()   // the retry loop stops; it does not keep retrying
```

---

## License

[MIT](LICENSE)

# kotlin-retry

[![Maven Central](https://img.shields.io/maven-central/v/io.kotlinretry/kotlin-retry.svg)](https://search.maven.org/artifact/io.kotlinretry/kotlin-retry)
[![CI](https://github.com/deepakvijayakumar/kotlin-retry/actions/workflows/ci.yml/badge.svg)](https://github.com/deepakvijayakumar/kotlin-retry/actions)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

A lightweight, coroutine-native resilience DSL for Kotlin. Composable retry, circuit breaker, timeout, and fallback — without the weight of Resilience4j.

```kotlin
val result = resilient("payment-service") {
    retry          { attempts = 3; backoff = Backoff.exponential() }
    circuitBreaker { failureThreshold = 5; openDuration = 30.seconds }
    timeout(5.seconds)
    fallback       { "cached-result" }
} {
    callPaymentService()
}
```

---

## Why not Resilience4j?

Resilience4j is excellent but designed for Java: registry-based, annotation-heavy, and callback-oriented. `kotlin-retry` is built around Kotlin coroutines and DSL idioms from the ground up:

| | kotlin-retry | Resilience4j |
|---|:---:|:---:|
| Coroutine-native (`suspend`) | YES | Partial |
| `Flow` compatible | YES | NO |
| DSL configuration | YES | NO |
| Zero code generation | YES | YES |
| Dependency footprint | Coroutines only | 10+ modules |
| Registry required | NO | YES |

---

## Installation

```kotlin
dependencies {
    implementation("io.kotlinretry:kotlin-retry:0.1.0")
}
```

---

## Retry

```kotlin
// Basic retry
val result = retry {
    attempts = 3
    delay    = 200.milliseconds
    backoff  = Backoff.exponential()
} {
    fetchFromApi()
}

// Only retry specific exceptions
retry {
    attempts = 5
    retryOn  = { it is IOException || it is TimeoutException }
} {
    callRemoteService()
}

// Get context inside the block
retry { attempts = 3 } { ctx ->
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
val result = resilient("search") {
    retry   { attempts = 3 }
    timeout(10.seconds)              // Total budget across all attempts
} {
    callSearchApi()
}
```

---

## Fallback

```kotlin
// Static value
resilient("cache") {
    retry    { attempts = 3 }
    fallback { emptyList() }
} {
    fetchFromDatabase()
}

// Dynamic fallback with access to the exception
resilient("pricing") {
    fallback { ex ->
        log.warn("Pricing unavailable: ${ex.message}, returning cached price")
        cachedPrice
    }
} {
    fetchLivePrice()
}
```

---

## Composing everything

The `resilient` DSL composes all four mechanisms. Execution order:

```
fallback( timeout( circuitBreaker( retry( block ) ) ) )
```

```kotlin
val result = resilient("payment-processor") {
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
    fallback { ex ->
        when (ex) {
            is CircuitBreakerOpenException -> PaymentResult.serviceUnavailable()
            is OperationTimeoutException   -> PaymentResult.timeout()
            else                           -> throw ex
        }
    }
} { ctx ->
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
resilient("get-stock") {
    use(breaker)
    retry { attempts = 2 }
} { getStock(itemId) }

// In service B - same breaker, shared failure count
resilient("reserve-stock") {
    use(breaker)
} { reserveStock(itemId, qty) }
```

---

## License

[MIT](LICENSE)

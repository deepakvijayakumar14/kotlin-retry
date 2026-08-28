# kotlin-retry

[![Maven Central](https://img.shields.io/maven-central/v/io.github.deepakvijayakumar14/kotlin-retry.svg)](https://central.sonatype.com/artifact/io.github.deepakvijayakumar14/kotlin-retry)
[![CI](https://github.com/deepakvijayakumar14/kotlin-retry/actions/workflows/ci.yml/badge.svg)](https://github.com/deepakvijayakumar14/kotlin-retry/actions)
[![Coverage](https://img.shields.io/badge/coverage-%E2%89%A590%25-brightgreen)](CONTRIBUTING.md)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

A small, coroutine-native resilience DSL for Kotlin: composable retry, circuit breaker, timeout, and fallback in one dependency.

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

## How this compares to Resilience4j

Resilience4j is the mature, full-featured option, and its
[Kotlin module](https://resilience4j.readme.io/docs/getting-started-4) does support `suspend`
functions and `Flow` operators. Reach for it when you need the breadth. `kotlin-retry` is the
smaller choice: Kotlin all the way down, one artifact, and a DSL rather than a decorator chain.

| | kotlin-retry | Resilience4j |
|---|---|---|
| Written in | Kotlin | Java core + Kotlin extensions |
| Artifacts | One | Core module per concern |
| Configuration | DSL | Builder / decorator, optional annotations |
| Registry | Not required | Central registry |
| `suspend` support | Yes | Yes |
| `Flow` support | Yes | Yes |
| Retry, circuit breaker, timeout, fallback | Yes | Yes |
| Rate limiter, bulkhead, cache | No | Yes |
| Metrics / framework integrations | `onStateChange` and `onRetry` hooks | Micrometer, Dropwizard, Spring Boot, Micronaut |

---

## Installation

The Maven coordinate uses the `io.github.deepakvijayakumar14` namespace; the Kotlin package is
`io.kotlinretry`. Release history is in [CHANGELOG.md](CHANGELOG.md).

```kotlin
dependencies {
    implementation("io.github.deepakvijayakumar14:kotlin-retry:0.3.0")
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

Configuration is validated where it is written, not where it is used. `attempts = 0`, a negative
delay, a threshold below 1, or a `NaN` backoff multiplier throws `IllegalArgumentException` from
the builder — at startup, naming the setting — rather than surfacing as something unrecognisable
from inside the library much later.

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
    permittedCallsInHalfOpen = 1  // Probe with one call at a time (default)
    recordFailure    = { it is IOException || it is HttpException }
    onStateChange    = { name, from, to ->
        metrics.increment("circuit_breaker.transition", "name" to name, "state" to to.name)
    }
}

val result = breaker.execute { callDownstreamApi() }
```

States: `CLOSED` (normal) -> `OPEN` (rejecting calls) -> `HALF_OPEN` (probing) -> `CLOSED`.

**Half-open is not open season.** When `openDuration` expires the breaker admits at most
`permittedCallsInHalfOpen` calls at a time — one by default — and rejects the rest with
`CircuitBreakerOpenException`. Everything that queued up while the circuit was open does not get
released onto a dependency that has only just come back. The slot is freed after each probe
finishes, so `successThreshold = 2` means two successful probes in sequence.

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

A policy composes all four mechanisms. Execution order, outermost to innermost:

```
fallback( timeout( retry( circuitBreaker( block ) ) ) )
```

The breaker sits **inside** the retry loop, so it sees every individual attempt: a dependency that
fails three times in one `execute` call moves the breaker three failures closer to opening.
Counting one logical failure per call instead would make an outage take `attempts` times longer to
detect.

The flip side is that a retry loop must not sit there retrying a breaker that is already rejecting
calls, so `CircuitBreakerOpenException` is never retried. An open circuit fails fast and arrives at
your fallback as itself, not wrapped in `RetryExhaustedException`.

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

## Contributing

Bug reports and pull requests are welcome — see [CONTRIBUTING.md](CONTRIBUTING.md). `./gradlew
check` is the whole gate: tests, detekt, public-ABI verification, and a coverage floor enforced by
Kover. No hosted service is involved — if it is green locally, it is green in CI.

---

## License

[MIT](LICENSE)

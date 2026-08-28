# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.3.0] - 2026-08-28

Correctness release. The half-open state did not limit probe traffic, the documented composition
order was the reverse of the implemented one, and invalid configuration was accepted silently.
Every change below is behavioural; none change a signature you were already calling.

### Added

- `permittedCallsInHalfOpen` on `CircuitBreaker.Builder` (default `1`). A half-open circuit admits
  at most this many calls at a time and rejects the rest with `CircuitBreakerOpenException`.
- Configuration validation. `RetryPolicy.Builder`, `CircuitBreaker.Builder` and the `Backoff`
  factories reject invalid settings with `IllegalArgumentException` naming the setting.
- Tests pinning the composition order, the half-open probe limit, and each validation rule. The
  circuit-breaker tests now advance an injected clock instead of sleeping, so the suite no longer
  waits out real `openDuration` windows.
- Project tooling: Kover coverage with a floor enforced by `check`, the Kotlin Binary
  Compatibility Validator (the public ABI is pinned in `api/kotlin-retry.api` and `apiCheck` fails
  the build on drift), Dependabot, `CONTRIBUTING.md`, and issue/PR templates. CI is now a single
  `./gradlew check` across JDK 17 and 21 instead of separate build, test, and detekt invocations.
  All of it runs from the Gradle wrapper and GitHub Actions — no third-party service.

### Changed

- **Breaking (behaviour):** `HALF_OPEN` now admits one call at a time instead of all of them.
  Previously every concurrent caller passed straight through the moment `openDuration` expired,
  so a dependency that had just come back received the entire backlog at once — the flood the
  circuit had spent `openDuration` preventing. Raise `permittedCallsInHalfOpen` to restore
  more concurrent probing, though not the unbounded behaviour.
- **Breaking (behaviour):** `RetryPolicy` never retries `CircuitBreakerOpenException`, whatever
  `retryOn` says. It previously did, because the exception extends `Exception` and so matched the
  default predicate. Two things follow: an open circuit fails fast instead of serving the caller
  a full set of backoff delays, and the rejection reaches the caller as itself rather than wrapped
  in `RetryExhaustedException` — so a fallback matching on `CircuitBreakerOpenException`, as the
  README's own composed example does, now actually matches. `Flow.retryWith` does the same.
- The circuit breaker measures `openDuration` with a monotonic `TimeSource` rather than
  `Instant.now()`. A clock step no longer holds the circuit open past its window or reopens it
  early. The clock is injectable internally so tests can control it.

### Fixed

- **`HALF_OPEN` did not limit probe traffic**, despite the documentation promising one probe call.
  See above.
- **The documented composition order was backwards.** README and KDoc both showed
  `fallback(timeout(circuitBreaker(retry(block))))`, while `ResiliencePolicy` executes
  `fallback(timeout(retry(circuitBreaker(block))))`. The implemented order is the intended one —
  the breaker sees every attempt, so an outage is detected `attempts` times sooner — and is now
  documented, explained, and covered by tests.
- **A concurrent probe could undo another probe's decision to reopen.** With more than one
  permitted half-open call, a probe that succeeded after a sibling had already failed and reopened
  the circuit would close it again, discarding fresh evidence that the dependency was still down.
  Closing now requires the circuit to still be `HALF_OPEN`.
- **Invalid configuration was accepted.** `attempts = 0` skipped the retry loop entirely and threw
  `NullPointerException` from the library's internals; non-positive thresholds, negative delays and
  durations, and `NaN`/infinite/negative backoff multipliers were all taken at face value.
- **The Resilience4j comparison table was inaccurate.** It claimed no `Flow` operator and only
  partial coroutine support; `resilience4j-kotlin` has had both for years. The table now compares
  what actually differs, and names what Resilience4j has that this library does not.

### Known limitations

- `Backoff.decorrelatedJitter()` keeps one previous-delay value inside the `Backoff` instance, so
  concurrent executions sharing an instance draw from each other's chain. It is thread-safe but
  not per-execution independent. Give each call site its own instance if that matters; making the
  state per-execution requires changing the `Backoff` interface and is deferred to a later release.

## [0.2.0] - 2026-08-27

First release published to Maven Central. `0.1.0` was declared in `build.gradle.kts` but never
tagged or published, so nothing here breaks a consumer — only code built from source.

The `0.1.0` tree did not compile: `detekt` reported eight violations and the test sources failed
to build entirely, so its test suite had never run. Several of the defects below were found once
it did.

### Added

- `resiliencePolicy(name) { }` and `ResiliencePolicy`, a reusable composition of retry, circuit
  breaker and timeout. Execute with `policy.execute { }`, or `policy.executeOrElse(fallback) { }`
  to supply a fallback.
- `Flow<T>.retryWith(policy)` and `Flow<T>.retryWith(attempts = …)` — retries a failing upstream
  flow using the library's policies.
- `retryPolicy { }` for retry configuration worth naming and reusing; `RetryPolicy.execute` is now
  public.
- `ResiliencePolicy.circuitBreaker`, exposing the breaker a policy owns.
- A release workflow publishing to the Maven Central Portal, documented in `RELEASING.md`.

### Changed

- **Breaking:** the published coordinate is now `io.github.deepakvijayakumar14:kotlin-retry`
  (was `io.kotlinretry:kotlin-retry`). The Kotlin package is unchanged — imports stay
  `io.kotlinretry.*`.
- **Breaking:** `resilient(name, configure) { }` is replaced by `resiliencePolicy(name) { }` plus
  `execute { }`. Configuration and execution are now separate so a policy — and the breaker state
  it owns — outlives a single call.
- **Breaking:** `retry` takes named parameters instead of a configuration lambda:
  `retry(attempts = 3, delay = 200.milliseconds) { }`. Same for `retryOrDefault` and `retryOrNull`.
  Kotlin permits only one trailing lambda per call, so the previously documented
  `retry { config } { block }` form could never compile.
- **Breaking:** a fallback is supplied per call via `executeOrElse` rather than in the builder.
  This keeps `ResiliencePolicy` non-generic, so one policy serves call sites returning different
  types.
- The javadoc artifact is generated by Dokka. It was previously derived from the `javadoc` task,
  which has no Java sources here, and would have shipped empty.
- `Backoff.jitter` documents its range as `[0, ceiling)`, and `decorrelatedJitter` documents that
  results are clamped to `[0, maxDelay]` and that the first call returns `base`.

### Removed

- `resilient()`, `ResilientBuilder`, and the builder's `fallback { }` — superseded by
  `resiliencePolicy` and `executeOrElse`.

### Fixed

- **A circuit breaker configured inline in `resilient` never opened.** A fresh breaker was
  constructed on every call, so its failure count was discarded each time. Ten consecutive
  failures through a policy with `failureThreshold = 2` produced ten calls to the guarded block
  and zero rejections. A policy now builds its breaker once.
- **Cancellation was treated as a retryable failure.** `CancellationException` extends
  `IllegalStateException`, so the default `retryOn = { it is Exception }` matched it. Cancelling a
  scope did not stop a retry loop; `retryOrDefault` and `retryOrNull` converted cancellation into
  a value; and the circuit breaker counted it as a dependency failure. All layers now rethrow it
  untouched. As a result, `timeout` composed with `retry` reports `OperationTimeoutException`
  instead of `RetryExhaustedException`.
- **`CircuitBreaker` stamped `openedAt` after publishing the `OPEN` state.** A concurrent reader
  could see `OPEN` beside the timestamp of a previous cycle, judge `openDuration` elapsed, and
  return the circuit to `HALF_OPEN` immediately — so it stopped staying open just as a failed
  probe confirmed the dependency was down. The timestamp is now written first, and `openedAt` is
  `@Volatile` as it is read outside the lock.
- **`CircuitBreaker` announced the `OPEN → HALF_OPEN` transition from every observer** rather than
  the thread that won the compare-and-set. Because `currentState` resolves that transition,
  reading the state emitted state-change events.
- **`onStateChange` ran while the breaker held its lock.** `Mutex` is not reentrant, so a callback
  that re-entered the breaker deadlocked. Callbacks now fire after the lock is released; the
  trade-off is that concurrent transitions may deliver callbacks out of order.
- **`onStateChange` fired on every rejected call** as a meaningless `OPEN → OPEN` transition,
  flooding the alerting hook precisely while the circuit was shedding load. Count
  `CircuitBreakerOpenException` instead.
- **`Backoff.jitter` threw `IllegalArgumentException`** whenever the ceiling rounded below one
  millisecond — a sub-millisecond `base`, a zero `maxDelay`, or a non-positive input. It escaped
  from outside the retry loop's `try`, replacing the caller's real error with an unrelated crash.
- **`Backoff.decorrelatedJitter` threw** when `maxDelay` was below `base`, because the cap was
  applied after the floor; overflowed with an unbounded `maxDelay`, silently collapsing the
  backoff to `base`; and kept its state in a plain `var`, losing roughly 64% of updates under
  contention in measurement, which stops the delay chain advancing. State is now an `AtomicLong`,
  so one instance is safe to share.
- POM metadata: added the `scm.connection` and `scm.developerConnection` entries Maven Central
  requires, and corrected the repository URL, which pointed at a non-existent account.
- README examples now compile. They are compiled and executed by the test suite so they cannot
  drift from the API again.

## 0.1.0

Never tagged or released. The version was declared in the build, but the tree did not compile.

[0.3.0]: https://github.com/deepakvijayakumar14/kotlin-retry/releases/tag/v0.3.0
[0.2.0]: https://github.com/deepakvijayakumar14/kotlin-retry/releases/tag/v0.2.0

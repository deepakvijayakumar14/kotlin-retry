# Contributing

Thanks for taking an interest. This is a small library with a deliberately small API, so the bar
for adding surface is high and the bar for correctness is higher.

## Getting set up

You need JDK 17. Everything else comes from the Gradle wrapper.

```bash
git clone https://github.com/deepakvijayakumar14/kotlin-retry.git
cd kotlin-retry
./gradlew check
```

`check` is the whole gate: it compiles, runs the tests, runs detekt, verifies the public ABI
against `api/kotlin-retry.api`, and fails if line coverage drops below the floor in
`build.gradle.kts`. If it passes locally it will pass in CI — no hosted service is in the loop,
so there is nothing to sign up for and nothing that can be down.

Useful individual tasks:

| Task | What it does |
|---|---|
| `./gradlew test` | Tests only |
| `./gradlew detekt` | Static analysis |
| `./gradlew apiDump` | Regenerate `api/kotlin-retry.api` after an intentional API change |
| `./gradlew koverHtmlReport` | Coverage report at `build/reports/kover/html/index.html` |
| `./gradlew koverLog` | Print the coverage percentage |
| `./gradlew dokkaHtml` | API documentation |

## What a change needs

**A test that fails without it.** Concurrency and cancellation bugs are the ones that matter in a
library like this, and they do not show up in a happy-path test. If you are fixing a race, write
the test that loses the race first.

**No real-time sleeping in tests.** The circuit breaker takes an injectable `TimeSource`; tests
advance a `TestClock` instead of waiting out an `openDuration`. A test that waits is a test that
is flaky on a loaded CI runner.

**Cancellation stays sacred.** `CancellationException` is the caller withdrawing the work. No
layer may retry it, count it as a dependency failure, or convert it into a fallback value. If
your change catches `Throwable` anywhere, it must rethrow cancellation first.

**Comments explain decisions, not syntax.** The existing code comments say *why* a lock is
released before a callback, or why a timestamp is written before a state change. Match that. A
comment restating what the line already says is noise.

## API changes

The public ABI is pinned in `api/kotlin-retry.api`. `apiCheck` fails the build when it drifts, so
an intentional change means running `./gradlew apiDump` and committing the result. Treat that diff
as part of the review: it is exactly what a consumer would experience.

Anything that changes behaviour for existing callers — even without changing a signature — needs
a `CHANGELOG.md` entry under **Changed** marked `**Breaking (behaviour):**`, saying what used to
happen and what happens now.

## Documentation

README snippets are compiled and executed by `ReadmeSnippetsCompileCheck`. If you add or change an
example in the README, add or change the matching snippet there too, so the documentation cannot
drift away from the API.

## Commits and pull requests

Conventional-commit prefixes: `feat`, `fix`, `docs`, `test`, `build`, `ci`, `refactor`. Add `!`
for a breaking change (`feat!:`).

In the pull request, say what the behaviour was before and what it is after. For a bug fix, the
most useful thing you can include is the sequence that reproduces it.

## Releasing

Maintainers only — see [RELEASING.md](RELEASING.md).

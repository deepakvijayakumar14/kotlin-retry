## What this changes

<!-- One or two sentences. What was the behaviour before, and what is it now? -->

## Why

<!-- The problem being solved. For a bug fix, the sequence that reproduces it. -->

## Checklist

- [ ] `./gradlew check` passes locally
- [ ] A test fails without this change
- [ ] Cancellation is still never retried, counted as a failure, or converted to a fallback value
- [ ] No test waits out a real duration (advance a `TestClock` instead)
- [ ] `./gradlew apiDump` re-run and committed, if the public API changed
- [ ] `CHANGELOG.md` updated, with `**Breaking (behaviour):**` if existing callers are affected
- [ ] README snippets updated in both `README.md` and `ReadmeSnippetsCompileCheck`, if examples changed

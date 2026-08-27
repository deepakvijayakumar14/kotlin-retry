# Releasing

`kotlin-retry` publishes to Maven Central through the [Central Portal](https://central.sonatype.com)
using the [Gradle Maven Publish Plugin](https://vanniktech.github.io/gradle-maven-publish-plugin/).
Sonatype's OSSRH (`oss.sonatype.org`, `s01.oss.sonatype.org`) reached end-of-life on 2025-06-30 and
no longer accepts deployments.

This mirrors `kotlin-snowflake` — same plugin, same workflow shape, same secret names — so the two
projects release the same way and share one set of credentials.

> The plugin is pinned to **0.30.0**, the last line that supports Kotlin 1.9.x. Version 0.37.0
> requires Kotlin Gradle Plugin 2.2+, which this project does not use.

## One-time setup

**1. Namespace.** `group` is `io.github.deepakvijayakumar14`, verified in the Central Portal via
GitHub. This is the Maven coordinate only — the Kotlin package remains `io.kotlinretry`.

**2. Portal user token.** Central Portal → your profile → *Generate User Token*. This produces a
username/password pair that is **not** your login. The same token works for every project under
the namespace.

**3. Signing key.** Central requires a detached PGP signature for every artifact.

```bash
gpg --full-generate-key                       # RSA 4096, no expiry is fine
gpg --list-secret-keys --keyid-format=long    # note the key id
gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>   # Central verifies against a keyserver
gpg --armor --export-secret-keys <KEY_ID>     # this whole block is the SIGNING_KEY secret
```

**4. Repository secrets** (Settings → Secrets and variables → Actions). These are the same four
names and values as `kotlin-snowflake`, but secrets do not cross repositories — add them here too:

| Secret | Value |
|---|---|
| `MAVEN_CENTRAL_USERNAME` | Portal user token username |
| `MAVEN_CENTRAL_PASSWORD` | Portal user token password |
| `SIGNING_KEY` | ASCII-armored private key, including the `-----BEGIN...` / `-----END...` lines |
| `SIGNING_KEY_PASSWORD` | Passphrase for that key |

## Cutting a release

1. Set `version` in `build.gradle.kts`, update `CHANGELOG.md`, and commit.
2. **Dry run first.** Actions → *Publish* → *Run workflow*, leaving **dry run** checked. This
   builds, tests, and signs against the real key without uploading, then fails loudly if no `.asc`
   signatures were produced. A broken or missing key is the usual first-release failure, and this
   catches it for free.
3. Run it again with **dry run unchecked** to upload.
4. Release the deployment at <https://central.sonatype.com/publishing/deployments>.
5. Tag the released commit:

```bash
git tag v0.2.0 && git push origin v0.2.0
```

## Why publishing is manual

The workflow has no push or tag trigger. A released version can never be modified or removed, so
uploading is a deliberate act, and the final release is a separate click in the Portal. Step 4 is
easy to forget: an uploaded deployment sits as *pending* and never reaches Maven Central until
someone releases it.

## Local checks

```bash
./gradlew publishToMavenLocal   # writes to ~/.m2, unsigned unless a key is configured
./gradlew printVersion          # the version the workflow will publish
```

Signing is skipped when no key is configured, so local builds and CI need no GPG setup. A blank
`signingInMemoryKey` counts as absent — GitHub Actions substitutes an empty string for a secret
that does not exist, and treating that as present fails with "no configured signatory".

# Releasing

`kotlin-retry` publishes to Maven Central through the [Central Portal](https://central.sonatype.com).
Sonatype's OSSRH (`oss.sonatype.org`, `s01.oss.sonatype.org`) reached end-of-life on
2025-06-30 and no longer accepts deployments.

There is no official Sonatype Gradle plugin for the Portal, and the main community plugin
requires Kotlin Gradle Plugin 2.2+, which this project does not use. So the release workflow
talks to the [Publisher API](https://central.sonatype.org/publish/publish-portal-api/) directly:
Gradle stages signed artifacts into `build/staging-deploy`, and the workflow zips that directory
and uploads it as a deployment bundle.

## One-time setup

**1. Namespace.** Already done: `group` is `io.github.deepakvijayakumar14`, verified in the Central
Portal via GitHub. Note this is the Maven coordinate only - the Kotlin package remains
`io.kotlinretry`.

**2. Portal user token.** Central Portal → your profile → *Generate User Token*. This produces a
username/password pair that is **not** your login.

**3. Signing key.** Central requires a detached PGP signature for every artifact.

```bash
gpg --full-generate-key                       # RSA 4096, no expiry is fine
gpg --list-secret-keys --keyid-format=long    # note the key id
gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>   # Central verifies against a keyserver
gpg --armor --export-secret-keys <KEY_ID>     # this whole block is the GPG_PRIVATE_KEY secret
```

**4. Repository secrets** (Settings → Secrets and variables → Actions):

| Secret | Value |
|---|---|
| `MAVEN_CENTRAL_USERNAME` | Portal user token username |
| `MAVEN_CENTRAL_PASSWORD` | Portal user token password |
| `GPG_PRIVATE_KEY` | ASCII-armored private key, including the `-----BEGIN...` / `-----END...` lines |
| `GPG_PASSPHRASE` | Passphrase for that key (omit only if the key has none) |

## Cutting a release

1. Set `version` in `build.gradle.kts` and commit it.
2. Tag and push:

```bash
git tag v0.2.0
git push origin v0.2.0
```

The workflow refuses to run if the tag and the declared version disagree, or if the version ends
in `-SNAPSHOT`. It then builds, tests, runs detekt, signs, and uploads the bundle.

3. Approve the deployment at <https://central.sonatype.com/publishing/deployments>.

## Publishing modes

**A released version can never be modified or removed.** The workflow therefore defaults to
`USER_MANAGED`: it validates the bundle and stops, leaving the final release as a click in the
Portal. Run it from the Actions tab with `workflow_dispatch` and choose `AUTOMATIC` only when you
want the tag push to publish without review.

`workflow_dispatch` + `USER_MANAGED` is also the safe way to rehearse: it exercises signing,
bundling, and Central's validation without releasing anything.

## Optional hardening

Add an `environment: maven-central` to the `publish` job and configure required reviewers on that
environment, so releases need a second pair of eyes.

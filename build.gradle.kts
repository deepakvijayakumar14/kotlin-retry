import com.vanniktech.maven.publish.SonatypeHost
import kotlinx.kover.gradle.plugin.dsl.CoverageUnit
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.9.23"
    `java-library`
    id("org.jetbrains.dokka") version "1.9.20"
    id("io.gitlab.arturbosch.detekt") version "1.23.6"
    id("org.jetbrains.kotlinx.kover") version "0.9.1"
    // Fails the build when the public ABI drifts from api/kotlin-retry.api. Regenerate the
    // dump with `./gradlew apiDump` and review the diff - that diff is the compatibility story
    // a published library owes its consumers.
    id("org.jetbrains.kotlinx.binary-compatibility-validator") version "0.16.3"
    // Matches kotlin-snowflake. 0.30.0 is the last line that supports Kotlin 1.9.x;
    // 0.37.0 requires Kotlin Gradle Plugin 2.2+.
    id("com.vanniktech.maven.publish") version "0.30.0"
}

// Maven coordinate namespace, verified in the Central Portal.
// The Kotlin package stays io.kotlinretry - the two are unrelated.
group   = "io.github.deepakvijayakumar14"
version = "0.3.0"

repositories {
    mavenCentral()
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
    implementation("org.slf4j:slf4j-api:2.0.18")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.0")
    testImplementation("io.kotest:kotest-runner-junit5:5.8.1")
    testImplementation("io.kotest:kotest-assertions-core:5.8.1")
    testImplementation("ch.qos.logback:logback-classic:1.5.3")
}

tasks.withType<KotlinCompile> {
    kotlinOptions {
        jvmTarget        = "17"
        freeCompilerArgs = listOf("-Xjsr305=strict", "-opt-in=kotlin.RequiresOptIn")
    }
}

tasks.withType<Test> { useJUnitPlatform() }

// Coverage is enforced here rather than reported to a hosted service: `check` fails if line
// coverage drops below the floor, so a regression is caught by the same command that runs the
// tests, with no account, token, or third party involved.
//
// The floor sits below the current figure on purpose. A threshold pinned to today's number turns
// every honest refactor into a build failure, and the point is to catch coverage falling off a
// cliff, not to chase the last percent.
kover {
    reports {
        filters {
            excludes {
                // Generated Kotlin metadata, not code anyone wrote or can test.
                annotatedBy("kotlin.jvm.JvmSynthetic")
            }
        }
        verify {
            rule {
                minBound(90, CoverageUnit.LINE)
            }
        }
    }
}

/** Prints the project version alone, so the publish workflow can report and check it. */
tasks.register("printVersion") {
    val projectVersion = project.version.toString()
    doLast { println(projectVersion) }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// Publishing to the Central Portal. OSSRH reached end-of-life on 2025-06-30, so the legacy
// s01.oss.sonatype.org endpoints no longer accept deployments.
//
// Credentials are read from these Gradle properties, supplied in CI as ORG_GRADLE_PROJECT_* env:
//   mavenCentralUsername            Portal user token name
//   mavenCentralPassword            Portal user token password
//   signingInMemoryKey              ASCII-armoured GPG private key
//   signingInMemoryKeyPassword      passphrase for that key
//
// Publish with: ./gradlew publishToMavenCentral
mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)

    // Central rejects unsigned artifacts, but signing every local build would make a GPG key a
    // prerequisite for `publishToMavenLocal` and for CI. Sign only when a key is configured.
    //
    // Blank counts as absent, not present: GitHub Actions substitutes an empty string for a
    // secret that does not exist, so an isPresent() check would try to sign with an empty key
    // and fail with "no configured signatory".
    if (!providers.gradleProperty("signingInMemoryKey").orNull.isNullOrBlank()) {
        signAllPublications()
    }

    coordinates(group.toString(), "kotlin-retry", version.toString())

    pom {
        name.set("kotlin-retry")
        description.set(
            "Lightweight coroutine-native resilience DSL for Kotlin: retry, circuit breaker, timeout, fallback"
        )
        url.set("https://github.com/deepakvijayakumar14/kotlin-retry")
        inceptionYear.set("2026")

        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
            }
        }

        developers {
            developer {
                id.set("deepakvijayakumar14")
                name.set("Deepak Vijayakumar")
                url.set("https://github.com/deepakvijayakumar14")
            }
        }

        scm {
            connection.set("scm:git:git://github.com/deepakvijayakumar14/kotlin-retry.git")
            developerConnection.set("scm:git:ssh://github.com/deepakvijayakumar14/kotlin-retry.git")
            url.set("https://github.com/deepakvijayakumar14/kotlin-retry")
        }
    }
}

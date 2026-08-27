import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.9.23"
    `java-library`
    `maven-publish`
    signing
    id("org.jetbrains.dokka") version "1.9.20"
    id("io.gitlab.arturbosch.detekt") version "1.23.6"
}

// Maven coordinate namespace, verified in the Central Portal.
// The Kotlin package stays io.kotlinretry - the two are unrelated.
group   = "io.github.deepakvijayakumar14"
version = "0.2.0"

repositories {
    mavenCentral()
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
    implementation("org.slf4j:slf4j-api:2.0.12")

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

/** Prints the project version alone, so the release workflow can check it against the git tag. */
tasks.register("printVersion") {
    val projectVersion = project.version.toString()
    doLast { println(projectVersion) }
}

// Kotlin sources produce no `javadoc` output, so withJavadocJar() would publish an empty jar.
// Dokka is already on the classpath - use it for the javadoc artifact Central requires.
val dokkaJavadocJar by tasks.registering(Jar::class) {
    archiveClassifier.set("javadoc")
    from(tasks.named("dokkaJavadoc"))
}

java {
    withSourcesJar()
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifact(dokkaJavadocJar)
            pom {
                name.set("kotlin-retry")
                description.set("Lightweight coroutine-native resilience DSL for Kotlin: retry, circuit breaker, timeout, fallback")
                url.set("https://github.com/deepakvijayakumar14/kotlin-retry")
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
                    url.set("https://github.com/deepakvijayakumar14/kotlin-retry")
                    connection.set("scm:git:https://github.com/deepakvijayakumar14/kotlin-retry.git")
                    developerConnection.set("scm:git:ssh://git@github.com/deepakvijayakumar14/kotlin-retry.git")
                }
            }
        }
    }
    repositories {
        // Sonatype's OSSRH reached end-of-life on 2025-06-30. The Central Portal takes an uploaded
        // bundle instead of a deploy, so publish into a local directory and let the release
        // workflow zip and POST it.
        maven {
            name = "staging"
            url  = layout.buildDirectory.dir("staging-deploy").get().asFile.toURI()
        }
    }
}

signing {
    // Signing keys only exist in CI, so keep `./gradlew build` working without them.
    val signingKey        = System.getenv("GPG_PRIVATE_KEY")
    val signingPassphrase = System.getenv("GPG_PASSPHRASE")
    isRequired = signingKey != null
    if (signingKey != null) useInMemoryPgpKeys(signingKey, signingPassphrase)
    sign(publishing.publications["mavenJava"])
}

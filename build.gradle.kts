import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.9.23"
    `java-library`
    `maven-publish`
    signing
    id("org.jetbrains.dokka") version "1.9.20"
    id("io.gitlab.arturbosch.detekt") version "1.23.6"
}

group   = "io.kotlinretry"
version = "0.1.0"

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

java {
    withJavadocJar()
    withSourcesJar()
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            pom {
                name.set("kotlin-retry")
                description.set("Lightweight coroutine-native resilience DSL for Kotlin: retry, circuit breaker, timeout, fallback")
                url.set("https://github.com/deepakvijayakumar/kotlin-retry")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
                developers {
                    developer {
                        id.set("deepakvijayakumar")
                        name.set("Deepak Vijayakumar")
                    }
                }
                scm {
                    url.set("https://github.com/deepakvijayakumar/kotlin-retry")
                }
            }
        }
    }
    repositories {
        maven {
            name = "sonatype"
            url  = uri(
                if (version.toString().endsWith("SNAPSHOT"))
                    "https://s01.oss.sonatype.org/content/repositories/snapshots/"
                else
                    "https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/"
            )
            credentials {
                username = findProperty("ossrhUsername") as String? ?: System.getenv("OSSRH_USERNAME")
                password = findProperty("ossrhPassword") as String? ?: System.getenv("OSSRH_PASSWORD")
            }
        }
    }
}

signing { sign(publishing.publications["mavenJava"]) }

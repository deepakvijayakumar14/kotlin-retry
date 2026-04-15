package io.kotlinretry

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import java.io.IOException

class CircuitBreakerTest : DescribeSpec({

    describe("CircuitBreaker state transitions") {

        it("starts CLOSED and passes calls through") = runTest {
            val breaker = circuitBreaker("test") { failureThreshold = 3 }
            breaker.currentState shouldBe CircuitBreaker.State.CLOSED

            val result = breaker.execute { "ok" }
            result shouldBe "ok"
        }

        it("transitions to OPEN after failureThreshold failures") = runTest {
            val breaker = circuitBreaker("test") { failureThreshold = 3 }

            repeat(3) {
                runCatching { breaker.execute { throw IOException("fail") } }
            }

            breaker.currentState shouldBe CircuitBreaker.State.OPEN
        }

        it("rejects calls with CircuitBreakerOpenException when OPEN") = runTest {
            val breaker = circuitBreaker("test") { failureThreshold = 2 }

            repeat(2) { runCatching { breaker.execute { throw IOException("fail") } } }

            shouldThrow<CircuitBreakerOpenException> {
                breaker.execute { "should be rejected" }
            }.name shouldBe "test"
        }

        it("resets to CLOSED after manual reset") = runTest {
            val breaker = circuitBreaker("test") { failureThreshold = 1 }

            runCatching { breaker.execute { throw IOException("fail") } }
            breaker.currentState shouldBe CircuitBreaker.State.OPEN

            breaker.reset()
            breaker.currentState shouldBe CircuitBreaker.State.CLOSED

            val result = breaker.execute { "recovered" }
            result shouldBe "recovered"
        }

        it("invokes onStateChange callback on transitions") = runTest {
            val transitions = mutableListOf<Triple<String, CircuitBreaker.State, CircuitBreaker.State>>()
            val breaker = circuitBreaker("tracked") {
                failureThreshold = 2
                onStateChange    = { name, from, to -> transitions += Triple(name, from, to) }
            }

            repeat(2) { runCatching { breaker.execute { throw IOException("fail") } } }

            transitions.any { it.third == CircuitBreaker.State.OPEN } shouldBe true
        }

        it("does not count non-recorded failures") = runTest {
            val breaker = circuitBreaker("test") {
                failureThreshold = 2
                recordFailure    = { it is IOException }
            }

            repeat(5) {
                runCatching { breaker.execute { throw IllegalStateException("not counted") } }
            }

            breaker.currentState shouldBe CircuitBreaker.State.CLOSED
        }
    }
})

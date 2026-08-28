package io.kotlinretry

import java.util.concurrent.atomic.AtomicLong
import kotlin.time.AbstractLongTimeSource
import kotlin.time.Duration
import kotlin.time.DurationUnit

/**
 * A clock the tests move by hand.
 *
 * Circuit-breaker tests used to `delay()` past `openDuration` in real time, which made them both
 * slow and dependent on the scheduler being prompt. Injecting this instead makes the open window
 * exact: nothing elapses until [advance] says so.
 *
 * The reading is atomic because the concurrency tests read it from many threads at once.
 */
internal class TestClock : AbstractLongTimeSource(DurationUnit.MILLISECONDS) {

    private val readingMillis = AtomicLong(0L)

    override fun read(): Long = readingMillis.get()

    fun advance(by: Duration) {
        readingMillis.addAndGet(by.inWholeMilliseconds)
    }
}

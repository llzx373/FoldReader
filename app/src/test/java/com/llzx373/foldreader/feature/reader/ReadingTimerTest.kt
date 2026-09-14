package com.llzx373.foldreader.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReadingTimerTest {

    @Test
    fun `accumulates only while running`() {
        val timer = ReadingTimer()
        assertEquals(0L, timer.totalMs(1000L))
        timer.start(1000L)
        assertEquals(500L, timer.totalMs(1500L))
        timer.stop(2000L)
        assertEquals(1000L, timer.totalMs(5000L))
        timer.start(5000L)
        timer.start(5100L)
        assertEquals(1300L, timer.totalMs(5300L))
        timer.stop(6000L)
        assertEquals(2000L, timer.totalMs(9999L))
    }
}

class ReadingSpeedTrackerTest {

    @Test
    fun `needs at least two distinct samples`() {
        val tracker = ReadingSpeedTracker()
        assertNull(tracker.charsPerMinute())
        tracker.feed(0L, 0L)
        assertNull(tracker.charsPerMinute())
        tracker.feed(0L, 10_000L)
        assertNull(tracker.charsPerMinute())
    }

    @Test
    fun `computes chars per minute`() {
        val tracker = ReadingSpeedTracker()
        tracker.feed(0L, 0L)
        tracker.feed(600L, 60_000L)
        assertEquals(600.0, tracker.charsPerMinute()!!, 0.001)
        assertEquals(30.0, tracker.remainingMinutes(18_600L, 600L)!!, 0.001)
    }

    @Test
    fun `backward movement yields no estimate`() {
        val tracker = ReadingSpeedTracker()
        tracker.feed(500L, 0L)
        tracker.feed(100L, 60_000L)
        assertNull(tracker.charsPerMinute())
    }

    @Test
    fun `old samples slide out of window`() {
        val tracker = ReadingSpeedTracker(windowMs = 60_000L)
        tracker.feed(0L, 0L)
        tracker.feed(300L, 30_000L)
        tracker.feed(600L, 60_000L)
        tracker.feed(900L, 120_000L)
        assertEquals(300.0, tracker.charsPerMinute()!!, 0.001)
    }

    @Test
    fun `remaining time formatting`() {
        assertEquals("约剩 45 分钟", formatRemainingTime(45.0))
        assertEquals("约剩 2 小时", formatRemainingTime(120.0))
        assertEquals("约剩 1 小时 5 分钟", formatRemainingTime(65.9))
        assertEquals("约剩 0 分钟", formatRemainingTime(-3.0))
    }
}

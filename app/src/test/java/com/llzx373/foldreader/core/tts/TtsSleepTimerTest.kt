package com.llzx373.foldreader.core.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsSleepTimerTest {

    @Test
    fun `分钟档截止时刻按设定分钟偏移`() {
        assertEquals(1_000L + 15 * 60_000L, TtsSleepTimer.deadlineMs(TtsSleepOption.MIN_15, 1_000L))
        assertEquals(0L + 60 * 60_000L, TtsSleepTimer.deadlineMs(TtsSleepOption.MIN_60, 0L))
    }

    @Test
    fun `关与读完本章没有截止时刻`() {
        assertNull(TtsSleepTimer.deadlineMs(TtsSleepOption.OFF, 0L))
        assertNull(TtsSleepTimer.deadlineMs(TtsSleepOption.CHAPTER_END, 0L))
    }

    @Test
    fun `剩余分钟向上取整`() {
        val deadline = 15 * 60_000L
        assertEquals(15, TtsSleepTimer.remainingMinutes(deadline, 0L))
        assertEquals(15, TtsSleepTimer.remainingMinutes(deadline, 1L))
        assertEquals(14, TtsSleepTimer.remainingMinutes(deadline, 60_000L))
        assertEquals(1, TtsSleepTimer.remainingMinutes(deadline, deadline - 1L))
        assertEquals(0, TtsSleepTimer.remainingMinutes(deadline, deadline))
    }

    @Test
    fun `到点判定含等于`() {
        assertFalse(TtsSleepTimer.expired(1_000L, 999L))
        assertTrue(TtsSleepTimer.expired(1_000L, 1_000L))
        assertTrue(TtsSleepTimer.expired(1_000L, 2_000L))
    }

    @Test
    fun `文案：分钟档显示剩余，读完本章固定文案，关为 null`() {
        assertEquals(
            "30 分钟后停止",
            TtsSleepTimer.remainingText(TtsSleepOption.MIN_30, 30 * 60_000L, 0L),
        )
        assertEquals(
            "读完本章停止",
            TtsSleepTimer.remainingText(TtsSleepOption.CHAPTER_END, null, 0L),
        )
        assertNull(TtsSleepTimer.remainingText(TtsSleepOption.OFF, null, 0L))
        assertNull(TtsSleepTimer.remainingText(TtsSleepOption.MIN_15, null, 0L))
    }
}

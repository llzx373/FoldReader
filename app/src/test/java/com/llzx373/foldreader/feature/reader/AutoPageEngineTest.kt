package com.llzx373.foldreader.feature.reader

import com.llzx373.foldreader.core.data.settings.AutoPageMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoPageEngineTest {

    @Test
    fun `manual interaction pauses for 10 seconds then resumes`() {
        val clock = AutoPageClock()
        assertTrue(clock.shouldRun(enabled = true, uiPaused = false, nowMs = 0L))
        clock.noteManualInteraction(1_000L)
        assertTrue(clock.isManualPaused(5_000L))
        assertFalse(clock.shouldRun(true, false, 10_999L))
        assertFalse(clock.isManualPaused(11_000L))
        assertTrue(clock.shouldRun(true, false, 11_000L))
    }

    @Test
    fun `ui pause and disabled gate running`() {
        val clock = AutoPageClock()
        assertFalse(clock.shouldRun(enabled = false, uiPaused = false, nowMs = 0L))
        assertFalse(clock.shouldRun(enabled = true, uiPaused = true, nowMs = 0L))
        assertTrue(clock.shouldRun(enabled = true, uiPaused = false, nowMs = 0L))
    }

    @Test
    fun `interval steps cycle and wrap`() {
        var sec = 3
        val seen = mutableListOf(sec)
        repeat(AUTO_PAGE_INTERVAL_STEPS.size) {
            sec = nextAutoPageIntervalSec(sec)
            seen += sec
        }
        assertEquals(3, sec)
        assertEquals(AUTO_PAGE_INTERVAL_STEPS.size, seen.distinct().size)
        assertTrue(seen.all { it in 3..30 })
    }

    @Test
    fun `speed steps cycle and wrap`() {
        var speed = 30f
        repeat(AUTO_PAGE_SPEED_STEPS.size) { speed = nextAutoPageSpeedPx(speed) }
        assertEquals(30f, speed)
        assertEquals(45f, nextAutoPageSpeedPx(31f))
    }

    @Test
    fun `mode toggles`() {
        assertEquals(AutoPageMode.SCROLL, nextAutoPageMode(AutoPageMode.INTERVAL))
        assertEquals(AutoPageMode.INTERVAL, nextAutoPageMode(AutoPageMode.SCROLL))
    }

    @Test
    fun `turn requests emit and drop when buffer full`() {
        val requests = AutoPageTurnRequests()
        val received = java.util.concurrent.CopyOnWriteArrayList<Boolean>()
        val scope = CoroutineScope(Dispatchers.Default)
        val job = scope.launch {
            requests.requests.collect {
                received += it
                delay(Long.MAX_VALUE) // 慢消费者：占住订阅观察缓冲行为
            }
        }
        runBlocking {
            withTimeoutOrNull(1_000L) {
                requests.subscriptionCount.first { it > 0 }
            }
        }
        assertTrue(requests.request(forward = true))
        runBlocking {
            withTimeoutOrNull(1_000L) {
                while (received.isEmpty()) delay(5L)
            }
        }
        assertTrue(requests.request(forward = true)) // 消费者占住，进入缓冲（容量 1）
        assertFalse(requests.request(forward = true)) // 缓冲已满，丢弃
        job.cancel()
        assertEquals(listOf(true), received)
    }

    @Test
    fun `max auto scroll clamps at zero`() {
        assertEquals(
            0f,
            maxAutoScrollPx(5, 1, 20f, 8f, 24f, 24f, 1000),
        )
        assertEquals(
            48f,
            maxAutoScrollPx(10, 2, 100f, 20f, 4f, 4f, 1000),
        )
    }
}

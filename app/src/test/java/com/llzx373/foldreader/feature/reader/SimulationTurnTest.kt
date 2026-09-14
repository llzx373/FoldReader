package com.llzx373.foldreader.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SimulationTurnTest {

    @Test
    fun `fling toward completion completes regardless of progress`() {
        assertEquals(TurnOutcome.COMPLETE, decideTurnOutcome(0.1f, 900f))
        assertEquals(TurnOutcome.CANCEL, decideTurnOutcome(0.9f, -900f))
    }

    @Test
    fun `slow drag decided by threshold`() {
        assertEquals(TurnOutcome.COMPLETE, decideTurnOutcome(0.35f, 0f))
        assertEquals(TurnOutcome.COMPLETE, decideTurnOutcome(0.8f, 100f))
        assertEquals(TurnOutcome.CANCEL, decideTurnOutcome(0.34f, 0f))
        assertEquals(TurnOutcome.CANCEL, decideTurnOutcome(0.05f, -100f))
    }

    @Test
    fun `frame monitor degrades only on sustained jank`() {
        val monitor = FrameHealthMonitor(badFrameMs = 24f, windowLimit = 10, badFrameLimit = 5)
        // 窗口内坏帧未达上限：不降级
        repeat(4) { monitor.noteFrame(50f) }
        repeat(6) { monitor.noteFrame(10f) }
        assertFalse(monitor.shouldDegrade())
        // 帧数不足窗口：即使全坏也不降级
        monitor.reset()
        repeat(9) { monitor.noteFrame(50f) }
        assertFalse(monitor.shouldDegrade())
        // 持续掉帧：降级
        monitor.noteFrame(50f)
        assertTrue(monitor.shouldDegrade())
        // 健康帧：不降级
        monitor.reset()
        repeat(12) { monitor.noteFrame(8f) }
        assertFalse(monitor.shouldDegrade())
    }

    @Test
    fun `curl helpers stay in range`() {
        assertTrue(curlShadowAlpha(0f) > curlShadowAlpha(1f))
        assertEquals(1f, curlScaleY(0f), 0.001f)
        assertEquals(1f, curlScaleY(1f), 0.001f)
        assertTrue(curlScaleY(0.5f) < 1f)
        assertTrue(curlScaleY(2f) <= 1f)
    }
}

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
        val monitor = FrameHealthMonitor(badFrameMs = 34f, windowSize = 30, warmupFrames = 5)
        // 起始帧（动画开始几帧）不计入统计
        repeat(5) { monitor.noteFrame(50f) }
        assertFalse(monitor.shouldDegrade())
        // 窗口内坏帧未过半：不降级
        repeat(14) { monitor.noteFrame(50f) }
        repeat(16) { monitor.noteFrame(10f) }
        assertFalse(monitor.shouldDegrade())
        // 帧数不足窗口：即使全坏也不降级
        monitor.reset()
        repeat(25) { monitor.noteFrame(50f) }
        assertFalse(monitor.shouldDegrade())
        // 持续掉帧填满窗口：降级
        repeat(10) { monitor.noteFrame(50f) }
        assertTrue(monitor.shouldDegrade())
        // 健康帧：不降级
        monitor.reset()
        repeat(40) { monitor.noteFrame(8f) }
        assertFalse(monitor.shouldDegrade())
        // 约 30fps 的帧（低于 34ms 阈值）不算坏帧
        monitor.reset()
        repeat(40) { monitor.noteFrame(30f) }
        assertFalse(monitor.shouldDegrade())
    }

    @Test
    fun `default threshold flags 30ms frames as bad`() {
        // 默认阈值 24ms：持续 30ms（约 33fps）即判定掉帧并降级
        val monitor = FrameHealthMonitor()
        repeat(40) { monitor.noteFrame(30f) }
        assertTrue(monitor.shouldDegrade())
        monitor.reset()
        repeat(40) { monitor.noteFrame(16f) }
        assertFalse(monitor.shouldDegrade())
    }
}

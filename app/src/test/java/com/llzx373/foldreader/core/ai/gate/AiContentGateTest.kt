package com.llzx373.foldreader.core.ai.gate

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiContentGateTest {

    private val tempDir = Files.createTempDirectory("ai-gate-test").toFile()
    private val historyFile = tempDir.resolve("history.json")
    private val gate = AiContentGate(historyFile)

    @Test
    fun `记录三条后按序读回且字段一致`() {
        gate.record("翻译", "第3章正文", 120, timestamp = 1000L)
        gate.record("润色", "选中文本", 45, timestamp = 2000L)
        gate.record("摘要", "整章", 300, timestamp = 3000L)

        val history = gate.history()
        assertEquals(3, history.size)
        assertEquals(
            AiContentGate.OutboundRecord(1000L, "翻译", "第3章正文", 120),
            history[0],
        )
        assertEquals(
            AiContentGate.OutboundRecord(2000L, "润色", "选中文本", 45),
            history[1],
        )
        assertEquals(
            AiContentGate.OutboundRecord(3000L, "摘要", "整章", 300),
            history[2],
        )
    }

    @Test
    fun `超过上限丢弃最旧记录`() {
        repeat(505) { i ->
            gate.record("翻译", "范围$i", i, timestamp = i.toLong())
        }

        val history = gate.history()
        assertEquals(500, history.size)
        // 最旧的 5 条（scope0..scope4）被丢弃，最早剩 scope5
        assertEquals("范围5", history.first().scope)
        assertEquals("范围504", history.last().scope)
    }

    @Test
    fun `文件不存在时历史为空`() {
        assertTrue(gate.history().isEmpty())
    }

    @Test
    fun `损坏文件按空历史处理不抛异常`() {
        historyFile.writeText("{这不是合法 JSON")

        assertTrue(gate.history().isEmpty())
        // 损坏后仍能继续记录
        gate.record("翻译", "范围", 10, timestamp = 1L)
        assertEquals(1, gate.history().size)
    }

    @Test
    fun `clear 删除历史文件`() {
        gate.record("翻译", "范围", 10)
        gate.clear()

        assertTrue(gate.history().isEmpty())
        assertTrue(!historyFile.exists())
    }
}

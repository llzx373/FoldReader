package com.llzx373.foldreader.core.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsSentenceSplitterTest {

    @Test
    fun `按句末标点切句`() {
        val segments = TtsSentenceSplitter.split("第一句。第二句！第三句？", 0)
        assertEquals(listOf("第一句。", "第二句！", "第三句？"), segments.map { it.text })
        assertEquals(listOf(0L, 4L, 8L), segments.map { it.charOffset })
    }

    @Test
    fun `连续标点与闭引号归同一句`() {
        val segments = TtsSentenceSplitter.split("他说：「好。」下一句", 0)
        assertEquals(listOf("他说：「好。」", "下一句"), segments.map { it.text })
        assertEquals(7L, segments[1].charOffset)
    }

    @Test
    fun `换行也是句末`() {
        val segments = TtsSentenceSplitter.split("第一行\n第二行\n", 0)
        assertEquals(listOf("第一行\n", "第二行\n"), segments.map { it.text })
    }

    @Test
    fun `长句按逗号二次切`() {
        // 160 字一句、中间有逗号：先按逗号断开，断点之前的部分不超过上限
        val part = "字".repeat(100) + "，" + "字".repeat(59) + "。"
        val segments = TtsSentenceSplitter.split(part, 0)
        assertEquals(2, segments.size)
        assertEquals("字".repeat(100) + "，", segments[0].text)
        assertEquals("字".repeat(59) + "。", segments[1].text)
        assertEquals(101L, segments[1].charOffset)
    }

    @Test
    fun `无标点超长硬切`() {
        val text = "字".repeat(320)
        val segments = TtsSentenceSplitter.split(text, 0)
        assertEquals(3, segments.size)
        assertEquals(150, segments[0].text.length)
        assertEquals(150, segments[1].text.length)
        assertEquals(20, segments[2].text.length)
        assertEquals(listOf(0L, 150L, 300L), segments.map { it.charOffset })
    }

    @Test
    fun `二次切找不到逗号时硬切`() {
        // 句末有句号但中段 200 字无标点：上限内硬切
        val text = "字".repeat(200) + "。"
        val segments = TtsSentenceSplitter.split(text, 0)
        assertEquals(150, segments[0].text.length)
        assertEquals(51, segments[1].text.length)
        assertEquals(150L, segments[1].charOffset)
    }

    @Test
    fun `偏移基于 baseOffset`() {
        val segments = TtsSentenceSplitter.split("甲。乙。", 1000)
        assertEquals(listOf(1000L, 1002L), segments.map { it.charOffset })
    }

    @Test
    fun `空文本与纯空白`() {
        assertTrue(TtsSentenceSplitter.split("", 0).isEmpty())
        assertTrue(TtsSentenceSplitter.split("\n\n\n", 0).isEmpty())
        assertTrue(TtsSentenceSplitter.split("  \n ", 5).isEmpty())
    }
}

package com.llzx373.foldreader.core.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WhitespaceNormalizerTest {

    @Test
    fun `段首空白整段折叠`() {
        val text = "       \u3000\u3000正文"
        val mask = normalizedWidthMask(text)
        for (i in 0 until 9) assertFalse("index $i 应被折叠", mask[i])
        for (i in 9 until text.length) assertTrue("index $i 应保留", mask[i])
    }

    @Test
    fun `行尾空白整段折叠`() {
        val text = "正文   \u3000"
        val mask = normalizedWidthMask(text)
        assertTrue(mask[0])
        assertTrue(mask[1])
        for (i in 2 until text.length) assertFalse("index $i 应被折叠", mask[i])
    }

    @Test
    fun `行内连续空白折叠为一个`() {
        val text = "他   说"
        val mask = normalizedWidthMask(text)
        assertEquals(listOf(true, true, false, false, true), mask.toList())
    }

    @Test
    fun `单个行内空白保留`() {
        val text = "a b"
        val mask = normalizedWidthMask(text)
        assertEquals(listOf(true, true, true), mask.toList())
    }

    @Test
    fun `不可见字符折叠`() {
        val text = "a\uFEFFb\u200Bc\u200Dd\u00ADe"
        val mask = normalizedWidthMask(text)
        assertEquals(
            listOf(true, false, true, false, true, false, true, false, true),
            mask.toList(),
        )
    }

    @Test
    fun `整行都是空白时不折叠`() {
        for (text in listOf("   ", "\u3000\u3000", "\t", "")) {
            val mask = normalizedWidthMask(text)
            assertTrue("$text 应全部保留", mask.all { it })
        }
    }

    @Test
    fun `掩码文本等长且折叠处为零宽占位符`() {
        val text = "       \u3000\u3000正文  内容"
        val masked = maskedForMeasure(text)
        assertEquals(text.length, masked.length)
        for (i in 0 until 9) assertEquals('\u200B', masked[i])
        assertEquals('正', masked[9])
        assertEquals('文', masked[10])
        // 行内连续空白只留首个
        assertEquals(' ', masked[11])
        assertEquals('\u200B', masked[12])
        assertEquals('内', masked[13])
    }

    @Test
    fun `无需折叠时原样返回`() {
        val text = "干净的正文"
        assertSame(text, maskedForMeasure(text))
    }
}

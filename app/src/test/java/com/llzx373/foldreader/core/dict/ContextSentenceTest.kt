package com.llzx373.foldreader.core.dict

import org.junit.Assert.assertEquals
import org.junit.Test

class ContextSentenceTest {

    @Test
    fun `取含词的整句（中文句号边界）`() {
        val window = "前一句。他咬了一口 apple 就走了。后一句。"
        val start = window.indexOf("apple")
        assertEquals(
            "他咬了一口 apple 就走了。",
            extractContextSentence(window, start, "apple".length),
        )
    }

    @Test
    fun `英文标点与换行边界`() {
        val window = "Line one.\nHe took an apple and left! Next."
        val start = window.indexOf("apple")
        assertEquals(
            "He took an apple and left!",
            extractContextSentence(window, start, "apple".length),
        )
    }

    @Test
    fun `无边界时取窗口全文`() {
        val window = "一段没有任何标点的话 apple 还有后续"
        val start = window.indexOf("apple")
        assertEquals(window, extractContextSentence(window, start, "apple".length))
    }

    @Test
    fun `超长句以词为中心截取并加省略号`() {
        val window = "x".repeat(150) + "apple" + "y".repeat(150)
        val start = 150
        val sentence = extractContextSentence(window, start, 5)
        assertEquals(202, sentence.length) // 200 + 前后省略号
        assertEquals('…', sentence.first())
        assertEquals('…', sentence.last())
    }

    @Test
    fun `空窗口与越界下标不炸`() {
        assertEquals("", extractContextSentence("", 0, 0))
        val window = "short text"
        assertEquals(window, extractContextSentence(window, 999, 5))
    }

    @Test
    fun `词在句首句尾`() {
        val window = "apple 在句首。另一句"
        assertEquals("apple 在句首。", extractContextSentence(window, 0, 5))
        val window2 = "句尾是 apple"
        val start = window2.indexOf("apple")
        assertEquals("句尾是 apple", extractContextSentence(window2, start, 5))
    }
}

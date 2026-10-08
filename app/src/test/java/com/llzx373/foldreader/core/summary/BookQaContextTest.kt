package com.llzx373.foldreader.core.summary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BookQaContextTest {

    @Test
    fun `未超预算时摘要与当前章全保留`() {
        val context = assembleQaContext(
            currentChapterTitle = "第三章",
            currentChapterText = "当前章正文",
            priorSummaries = listOf("第一章" to "摘要一", "第二章" to "摘要二"),
        )!!

        assertEquals(listOf("第一章" to "摘要一", "第二章" to "摘要二"), context.summaries)
        assertEquals("第三章" to "当前章正文", context.currentChapter)
    }

    @Test
    fun `超预算时摘要全保留当前章截头`() {
        val chapterText = "头".repeat(100) + "尾".repeat(50)
        val context = assembleQaContext(
            currentChapterTitle = "章",
            currentChapterText = chapterText,
            priorSummaries = listOf("前章" to "摘".repeat(40)),
            maxChars = 100,
        )!!

        // 摘要 40 字全保留，当前章只剩 60 字预算的尾部
        assertEquals(listOf("前章" to "摘".repeat(40)), context.summaries)
        val kept = context.currentChapter!!.second
        assertEquals(60, kept.length)
        assertEquals(chapterText.takeLast(60), kept)
        assertTrue(kept.endsWith("尾"))
    }

    @Test
    fun `摘要占满预算时当前章整块舍弃`() {
        val context = assembleQaContext(
            currentChapterTitle = "章",
            currentChapterText = "正文",
            priorSummaries = listOf("前章" to "摘".repeat(100)),
            maxChars = 100,
        )!!

        assertEquals(1, context.summaries.size)
        assertNull(context.currentChapter)
    }

    @Test
    fun `无摘要且当前章为空返回 null`() {
        assertNull(assembleQaContext("章", "", emptyList()))
        assertNull(assembleQaContext("章", "   \n ", listOf("前章" to "  ")))
    }

    @Test
    fun `仅当前章也能成上下文`() {
        val context = assembleQaContext("章", "正文", emptyList())!!

        assertTrue(context.summaries.isEmpty())
        assertEquals("章" to "正文", context.currentChapter)
    }
}

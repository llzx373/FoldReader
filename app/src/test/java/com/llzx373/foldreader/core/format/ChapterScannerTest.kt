package com.llzx373.foldreader.core.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterScannerTest {

    private fun scanAll(text: String, chunkSize: Int = Int.MAX_VALUE): List<Chapter> {
        val scanner = ChapterScanner()
        var i = 0
        while (i < text.length) {
            val end = minOf(i + chunkSize, text.length)
            scanner.feed(text.substring(i, end))
            i = end
        }
        if (text.isEmpty()) scanner.feed("")
        return scanner.finish()
    }

    @Test
    fun `常见网文章节格式全部命中`() {
        val text = buildString {
            append("卷首语：写在前面。\n")
            append("第一章 重生\n")
            append("正文里提到第一章节三个字不应误伤。\n")
            append("第1章 再出发\n")
            append("一些内容。\n")
            append("第两百章 终局之战\n")
            append("更多内容。\n")
            append("Chapter 12 Extra Story\n")
            append("English content.\n")
            append("12、纯数字分卷\n")
            append("最后的内容。")
        }
        val chapters = scanAll(text)

        assertEquals(
            listOf("卷首", "第一章 重生", "第1章 再出发", "第两百章 终局之战", "Chapter 12 Extra Story", "12、纯数字分卷"),
            chapters.map { it.title },
        )
    }

    @Test
    fun `章节偏移连续且与原文对齐`() {
        val text = buildString {
            append("前言废话两行\n第二行前言。\n")
            append("第一章 开始\n")
            append("正文字符。\n")
            append("第二章 继续\n")
            append("更多正文。")
        }
        val chapters = scanAll(text)

        assertEquals(3, chapters.size)
        assertEquals(0L, chapters.first().charStart)
        assertEquals(text.length.toLong(), chapters.last().charEnd)
        chapters.zipWithNext().forEach { (a, b) -> assertEquals(a.charEnd, b.charStart) }
        chapters.drop(1).forEach { chapter ->
            assertEquals(chapter.title, text.substring(chapter.charStart.toInt(), chapter.charEnd.toInt()).lines().first())
        }
    }

    @Test
    fun `正文首章前内容归入卷首`() {
        val text = "一些没有标题的引言。\n第一章 正文开始\n内容。"
        val chapters = scanAll(text)

        assertEquals("卷首", chapters.first().title)
        assertEquals(0L, chapters.first().charStart)
        assertEquals(chapters[1].charStart, chapters.first().charEnd)
    }

    @Test
    fun `无章节文本降级为单章全文`() {
        val text = "这只是一段普通文本。\n没有任何章节标题，第二行也一样。\n第三行。"
        val chapters = scanAll(text)

        assertEquals(1, chapters.size)
        assertEquals("全文", chapters[0].title)
        assertEquals(0L, chapters[0].charStart)
        assertEquals(text.length.toLong(), chapters[0].charEnd)
    }

    @Test
    fun `跨块喂入与整体喂入结果一致`() {
        val text = buildString {
            append("第一章 跨块测试\n")
            append("很长的正文".repeat(200) + "\n")
            append("第3章 标题被切碎在第\n")
            append("第二章 又来了\n")
            append("结尾")
        }
        val whole = scanAll(text)
        val byTinyChunks = scanAll(text, chunkSize = 7)

        assertEquals(whole, byTinyChunks)
        assertEquals(listOf("第一章 跨块测试", "第3章 标题被切碎在第", "第二章 又来了"), whole.map { it.title })
    }

    @Test
    fun `超长行不误判为章节`() {
        val longLine = "第三章" + "很长的标题行".repeat(20)
        val text = "$longLine\n正文内容。\n第一章 短标题\n内容。"
        val chapters = scanAll(text)

        assertTrue(chapters.none { it.title.startsWith("第三章") })
        assertEquals("第一章 短标题", chapters.last().title)
    }
}

package com.llzx373.foldreader.core.format

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchScannerTest {

    private class MemContent(private val text: String) : BookContent {
        override val charCount: Long get() = text.length.toLong()
        override suspend fun read(range: LongRange): String =
            text.substring(range.first.toInt(), (range.last + 1).toInt())
    }

    private suspend fun scan(
        text: String,
        query: String,
        windowChars: Int = 4096,
        contextChars: Int = 20,
    ): List<SearchHit> {
        val hits = mutableListOf<SearchHit>()
        searchContent(
            content = MemContent(text),
            query = query,
            windowChars = windowChars,
            contextChars = contextChars,
            onHit = { hits += it },
        )
        return hits
    }

    @Test
    fun `match spanning window boundary is not lost`() = runBlocking {
        // 手工演算：query "abcde" 起点 8；window=10 → step=6；窗口 [0,10) 只有 "ab"，
        // 完整命中落在窗口 [6,16)（idx=2 < step=6，归其所有）
        val hits = scan("01234567abcdeZZZ", "abcde", windowChars = 10)
        assertEquals(1, hits.size)
        assertEquals(8L, hits[0].offset)
    }

    @Test
    fun `no duplicate hits across overlapping windows`() = runBlocking {
        // 手工演算："abab"×5（20 字），query "abab" len4 window=8 step=5；
        // 期望命中全部偶数偏移 0,2,…,16，共 9 个且不重复
        val text = "abab".repeat(5)
        val hits = scan(text, "abab", windowChars = 8)
        assertEquals((0L..16L step 2).toList(), hits.map { it.offset })
        assertEquals(hits.size, hits.map { it.offset }.distinct().size)
    }

    @Test
    fun `context extraction clips at file edges`() = runBlocking {
        // 命中 offset=8，contextChars=20 → 左端钳到 0；context 即全文，命中起始下标 8
        val hits = scan("01234567abcdeZZZ", "abcde", windowChars = 10)
        assertEquals("01234567abcdeZZZ", hits[0].context)
        assertEquals(8, hits[0].matchStartInContext)
        assertEquals(5, hits[0].matchLength)
        // 中段：x*50 + "needle" + x*50，contextChars=5 → 摘要 5x+needle+5x
        val text = "x".repeat(50) + "needle" + "x".repeat(50)
        val mid = scan(text, "needle", contextChars = 5)
        assertEquals(1, mid.size)
        assertEquals("xxxxx" + "needle" + "xxxxx", mid[0].context)
        assertEquals(5, mid[0].matchStartInContext)
    }

    @Test
    fun `case insensitive ascii match`() = runBlocking {
        val hits = scan("他说 Hello World 然后 HELLO again", "hello")
        assertEquals(listOf(3L, 18L), hits.map { it.offset })
    }

    @Test
    fun `empty query or empty book yields nothing`() = runBlocking {
        assertTrue(scan("任意文本", "  ").isEmpty())
        assertTrue(scan("", "任意").isEmpty())
    }

    @Test
    fun `progress reaches total char count`() = runBlocking {
        val text = "天地玄黄".repeat(100) // 400 字
        val progress = mutableListOf<Long>()
        searchContent(
            content = MemContent(text),
            query = "宇宙",
            windowChars = 64,
            contextChars = 4,
            onHit = {},
            onProgress = { progress += it },
        )
        assertEquals(400L, progress.last())
        assertTrue(progress.zipWithNext().all { (a, b) -> a < b })
    }
}

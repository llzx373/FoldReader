package com.llzx373.foldreader.core.reader

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 滚动批量预取的边界：取多少页、何时停、游标怎么推进。
 * 原来一次只补一页，快速滑动时会出现「列表到底、下一页还没排出来」的空窗。
 */
class ScrollPrefetchTest {

    private fun page(start: Long, end: Long = start + 10): Page =
        Page(charStart = start, charEnd = end, lines = emptyList(), paddingLeft = 0f, paddingRight = 0f)

    @Test
    fun `取满上限即停`() = runBlocking {
        var calls = 0
        val pages = collectScrollPages(
            startCursor = 0L,
            limit = 5,
            next = { cursor ->
                calls++
                page(cursor)
            },
            advance = { it.charEnd },
        )

        assertEquals(5, pages.size)
        assertEquals("不应多取一次", 5, calls)
        assertEquals(listOf(0L, 10L, 20L, 30L, 40L), pages.map { it.charStart })
    }

    @Test
    fun `到书尾提前停止`() = runBlocking {
        // 只有三页可取，第四次 next 返回 null
        val pages = collectScrollPages(
            startCursor = 0L,
            limit = 10,
            next = { cursor -> if (cursor < 30L) page(cursor) else null },
            advance = { it.charEnd },
        )

        assertEquals(listOf(0L, 10L, 20L), pages.map { it.charStart })
    }

    @Test
    fun `向前翻用 charStart - 1 作为游标`() = runBlocking {
        // 调用方传入的 edge 就是「首屏页起点 - 1」（首屏起点 90 → edge 89）
        val seenCursors = mutableListOf<Long>()
        val pages = collectScrollPages(
            startCursor = 89L,
            limit = 3,
            next = { cursor ->
                seenCursors += cursor
                // 模拟 pageBefore：返回包含该偏移的那一页（每页 10 字）
                page((cursor / 10) * 10)
            },
            advance = { it.charStart - 1 },
        )

        assertEquals(listOf(89L, 79L, 69L), seenCursors)
        assertEquals(listOf(80L, 70L, 60L), pages.map { it.charStart })
    }

    @Test
    fun `limit 非正时直接返回空`() = runBlocking {
        var calls = 0
        val pages = collectScrollPages(
            startCursor = 0L,
            limit = 0,
            next = { calls++; page(it) },
            advance = { it.charEnd },
        )

        assertTrue(pages.isEmpty())
        assertEquals(0, calls)
    }

    @Test
    fun `第一页就取不到时返回空而不报错`() = runBlocking {
        val pages = collectScrollPages(
            startCursor = 0L,
            limit = 5,
            next = { null },
            advance = { it.charEnd },
        )

        assertTrue(pages.isEmpty())
    }

    @Test
    fun `游标推进规则由调用方决定`() = runBlocking {
        val cursors = mutableListOf<Long>()
        collectScrollPages(
            startCursor = 0L,
            limit = 3,
            next = { cursor ->
                cursors += cursor
                page(cursor, end = cursor + 7)
            },
            advance = { it.charEnd + 1 },
        )

        assertEquals(listOf(0L, 8L, 16L), cursors)
    }
}

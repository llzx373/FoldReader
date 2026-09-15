package com.llzx373.foldreader.feature.reader

import com.llzx373.foldreader.core.data.db.BookmarkEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class BookmarkLogicTest {

    @Test
    fun `snapshot strips whitespace and truncates`() {
        assertEquals(
            "纸上得来终觉浅，绝知此事要躬行。",
            bookmarkSnapshotOf("  纸上得来终觉浅，\n绝知此事要躬行。"),
        )
        assertEquals("", bookmarkSnapshotOf(" \n\t "))
        // 手动演算：清理后 30 字，截到 24 字
        val long = "天地玄黄宇宙洪荒日月盈昃辰宿列张寒来暑往秋收冬藏闰余成岁"
        assertEquals(24, bookmarkSnapshotOf(long).length)
        assertEquals(long.take(24), bookmarkSnapshotOf(long))
    }

    @Test
    fun `find bookmark by anchor offset`() {
        val a = bookmark(id = 1, offset = 100)
        val b = bookmark(id = 2, offset = 500)
        assertSame(a, findBookmarkAt(listOf(a, b), 100))
        assertSame(b, findBookmarkAt(listOf(a, b), 500))
        assertNull(findBookmarkAt(listOf(a, b), 101))
        assertNull(findBookmarkAt(emptyList(), 100))
    }

    @Test
    fun `same page different offsets coexist and toggle independently`() {
        // 同页（100~200）两条书签：精确偏移各自匹配，互不干扰
        val a = bookmark(id = 1, offset = 100)
        val b = bookmark(id = 2, offset = 150)
        val bookmarks = listOf(a, b)
        assertSame(a, findBookmarkAt(bookmarks, 100)) // toggle 100 → 删 a
        assertSame(b, findBookmarkAt(bookmarks, 150)) // toggle 150 → 删 b
        assertNull(findBookmarkAt(bookmarks, 120)) // 同页新偏移 → 新增第三条
        assertNull(findBookmarkAt(listOf(a), 150)) // 删 b 后 150 可再加
    }

    @Test
    fun `bookmarks sorted by recency descending`() {
        val old = bookmark(id = 1, offset = 500, createdAt = 1_000L)
        val mid = bookmark(id = 2, offset = 100, createdAt = 2_000L)
        val new = bookmark(id = 3, offset = 900, createdAt = 3_000L)
        assertEquals(
            listOf(new, mid, old),
            sortBookmarksByRecency(listOf(old, mid, new)),
        )
        assertEquals(emptyList<BookmarkEntity>(), sortBookmarksByRecency(emptyList()))
    }

    private fun bookmark(id: Long, offset: Long, createdAt: Long = 0L) = BookmarkEntity(
        id = id,
        bookId = 1,
        charOffset = offset,
        chapterIndex = 0,
        snapshotText = "",
        createdAt = createdAt,
    )
}

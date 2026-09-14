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

    private fun bookmark(id: Long, offset: Long) = BookmarkEntity(
        id = id,
        bookId = 1,
        charOffset = offset,
        chapterIndex = 0,
        snapshotText = "",
        createdAt = 0,
    )
}

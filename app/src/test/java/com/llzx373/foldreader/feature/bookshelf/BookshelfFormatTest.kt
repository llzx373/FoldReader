package com.llzx373.foldreader.feature.bookshelf

import com.llzx373.foldreader.core.data.db.BookEntity
import com.llzx373.foldreader.core.data.db.BookFormat
import com.llzx373.foldreader.core.data.db.needsContentPreparation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BookCoverPaletteTest {

    @Test
    fun `同书名取色稳定且在色板内`() {
        val first = BookCoverPalette.colorFor("雪中悍刀行")
        assertEquals(first, BookCoverPalette.colorFor("雪中悍刀行"))
        assertTrue(BookCoverPalette.colorFor("雪中悍刀行") in BookCoverPalette.palette)
    }

    @Test
    fun `任意书名取色不越界`() {
        val samples = listOf("", "a", "第一章", "庆余年", "𠀀𠀁", "zzzzzzzzzz".repeat(50))
        samples.forEach { title ->
            assertTrue(BookCoverPalette.colorFor(title) in BookCoverPalette.palette)
        }
    }

    @Test
    fun `色板颜色互不重复`() {
        assertEquals(BookCoverPalette.palette.size, BookCoverPalette.palette.toSet().size)
    }
}

class ReadingProgressFormatTest {

    @Test
    fun `无进度记录显示未开始`() {
        assertEquals("未开始", formatReadingProgress(null, 1000))
    }

    @Test
    fun `总字符数为零显示未开始`() {
        assertEquals("未开始", formatReadingProgress(10, 0))
    }

    @Test
    fun `进度百分比计算`() {
        assertEquals("已读 0%", formatReadingProgress(0, 1000))
        assertEquals("已读 50%", formatReadingProgress(500, 1000))
        assertEquals("已读 99%", formatReadingProgress(999, 1000))
    }

    @Test
    fun `进度百分比不越界`() {
        assertEquals("已读 100%", formatReadingProgress(2000, 1000))
    }

    @Test
    fun `最近阅读时间为空返回 null`() {
        assertNull(formatLastRead(null))
        assertEquals("format 非空", true, formatLastRead(1_700_000_000_000L)?.isNotBlank())
    }
}

/** 书架角标的判定规则：哪些书该显示"待解析"。 */
class ContentPreparationTest {

    private fun book(format: BookFormat, preparedAt: Long?) = BookEntity(
        id = 1L,
        title = "书",
        author = null,
        fileUri = "content://book/1",
        contentHash = "hash",
        format = format,
        totalChars = 0,
        encoding = "UTF-8",
        importedAt = 0,
        lastReadAt = null,
        contentPreparedAt = preparedAt,
    )

    @Test
    fun `未压平的 EPUB 与 FB2 需要提示`() {
        assertTrue(book(BookFormat.EPUB, null).needsContentPreparation())
        assertTrue(book(BookFormat.FB2, null).needsContentPreparation())
    }

    @Test
    fun `已就绪的书不再提示`() {
        assertEquals(false, book(BookFormat.EPUB, 1_700_000_000_000L).needsContentPreparation())
        assertEquals(false, book(BookFormat.FB2, 1L).needsContentPreparation())
    }

    @Test
    fun `TXT 没有压平步骤恒不提示`() {
        assertEquals(false, book(BookFormat.TXT, null).needsContentPreparation())
        assertEquals(false, book(BookFormat.TXT, 1L).needsContentPreparation())
    }
}

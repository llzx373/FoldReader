package com.llzx373.foldreader.feature.widget

import com.llzx373.foldreader.core.data.db.BookEntity
import com.llzx373.foldreader.core.data.db.BookFormat
import com.llzx373.foldreader.core.data.db.BookWithProgress
import org.junit.Assert.assertEquals
import org.junit.Test

/** M34 桌面小部件「继续阅读」的取数与进度口径（纯逻辑）。 */
class WidgetRecentBooksTest {

    private fun book(
        id: Long,
        lastReadAt: Long? = null,
        hidden: Boolean = false,
        format: BookFormat = BookFormat.TXT,
        totalChars: Long = 1000,
        pageCount: Int? = null,
    ) = BookWithProgress(
        book = BookEntity(
            id = id,
            title = "书$id",
            author = null,
            fileUri = "content://book/$id",
            contentHash = "hash$id",
            format = format,
            totalChars = totalChars,
            encoding = "UTF-8",
            importedAt = id,
            lastReadAt = lastReadAt,
            hidden = hidden,
            comicPageCount = pageCount,
        ),
        charOffset = null,
        comicPage = null,
    )

    @Test
    fun `取数排除隐藏与未读并按最近阅读倒序`() {
        val books = listOf(
            book(id = 1, lastReadAt = 100),
            book(id = 2, lastReadAt = 300),
            book(id = 3, lastReadAt = 200, hidden = true), // 隐藏不上桌面
            book(id = 4, lastReadAt = null), // 未读不算「在读」
        )
        assertEquals(listOf(2L, 1L), pickWidgetRecent(books, 3).map { it.book.id })
    }

    @Test
    fun `取数受行数上限截断`() {
        val books = (1L..5L).map { book(id = it, lastReadAt = it) }
        assertEquals(listOf(5L, 4L, 3L), pickWidgetRecent(books, 3).map { it.book.id })
    }

    @Test
    fun `进度百分比文本按字符偏移页式按页序号`() {
        val text = book(id = 1, lastReadAt = 1, totalChars = 200).copy(charOffset = 50)
        assertEquals(25, widgetProgressPercent(text))

        val comic = book(
            id = 2, lastReadAt = 1, format = BookFormat.COMIC, pageCount = 40,
        ).copy(comicPage = 10)
        assertEquals(25, widgetProgressPercent(comic))

        // 无进度 / 页数未解析：0（进度条为空），而不是编一个数
        assertEquals(0, widgetProgressPercent(book(id = 3, lastReadAt = 1)))
        assertEquals(
            0,
            widgetProgressPercent(
                book(id = 4, lastReadAt = 1, format = BookFormat.COMIC, pageCount = null)
                    .copy(comicPage = 3),
            ),
        )
    }
}

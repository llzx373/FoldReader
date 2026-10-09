package com.llzx373.foldreader.feature.bookshelf

import com.llzx373.foldreader.core.data.db.BookEntity
import com.llzx373.foldreader.core.data.db.BookFormat
import com.llzx373.foldreader.core.data.db.BookWithProgress
import com.llzx373.foldreader.core.data.db.needsContentPreparation
import com.llzx373.foldreader.core.data.settings.BookshelfSort
import java.util.Locale
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
        assertNull(formatLastRead(null, Locale.US))
    }

    @Test
    fun `最近阅读时间按传入的 Locale 排版`() {
        // Locale 由调用方传入（Composable 侧走 rememberLocale），函数内部不再读默认值——
        // 这样切换系统语言时书架上的时间才会跟着变。这里固定 Locale.US 断言 ASCII 数字，
        // 避免受运行环境默认 Locale 影响。
        val text = formatLastRead(1_700_000_000_000L, Locale.US)
        assertTrue("实际输出：$text", text!!.matches(Regex("\\d{2}-\\d{2} \\d{2}:\\d{2}")))
    }
}

/** 书架排序：RECENT_READ 按 lastReadAt 降序，未读过的书沉底并按导入时间兜底。 */
class BookshelfSortTest {

    private fun book(id: Long, importedAt: Long, lastReadAt: Long?) = BookWithProgress(
        BookEntity(
            id = id,
            title = "书$id",
            author = null,
            fileUri = "content://book/$id",
            contentHash = "hash$id",
            format = BookFormat.TXT,
            totalChars = 0,
            encoding = "UTF-8",
            importedAt = importedAt,
            lastReadAt = lastReadAt,
            contentPreparedAt = null,
        ),
        charOffset = null,
        comicPage = null,
    )

    @Test
    fun `最近读过的排最前`() {
        val books = listOf(
            book(id = 1, importedAt = 100, lastReadAt = 500),
            book(id = 2, importedAt = 200, lastReadAt = 900),
        )
        val sorted = sortBookshelf(books, BookshelfSort.RECENT_READ)
        assertEquals(listOf(2L, 1L), sorted.map { it.book.id })
    }

    @Test
    fun `从未读过的书沉底且按导入时间兜底`() {
        val books = listOf(
            book(id = 1, importedAt = 100, lastReadAt = null),
            book(id = 2, importedAt = 200, lastReadAt = 500),
            book(id = 3, importedAt = 300, lastReadAt = null),
        )
        val sorted = sortBookshelf(books, BookshelfSort.RECENT_READ)
        assertEquals(listOf(2L, 3L, 1L), sorted.map { it.book.id })
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

    private fun comic(
        container: com.llzx373.foldreader.core.comic.ComicContainer,
        pageCount: Int?,
        preparedAt: Long? = null,
        localPath: String? = null,
    ) = book(BookFormat.COMIC, preparedAt).copy(
        comicContainer = container,
        comicPageCount = pageCount,
        comicLocalPath = localPath,
    )

    @Test
    fun `zip 与目录漫画导入即知页数不提示`() {
        assertEquals(
            false,
            comic(com.llzx373.foldreader.core.comic.ComicContainer.ZIP, pageCount = 120)
                .needsContentPreparation(),
        )
        assertEquals(
            false,
            comic(com.llzx373.foldreader.core.comic.ComicContainer.FOLDER, pageCount = 3)
                .needsContentPreparation(),
        )
    }

    @Test
    fun `rar 未预热时提示待解析`() {
        assertTrue(
            comic(com.llzx373.foldreader.core.comic.ComicContainer.RAR, pageCount = null)
                .needsContentPreparation(),
        )
    }

    @Test
    fun `rar 预热完成后撤下角标`() {
        // 预热/首开：页数回填 + contentPreparedAt 落库
        assertEquals(
            false,
            comic(
                com.llzx373.foldreader.core.comic.ComicContainer.RAR,
                pageCount = 120,
                preparedAt = 1_700_000_000_000L,
            ).needsContentPreparation(),
        )
        assertEquals(
            false,
            comic(
                com.llzx373.foldreader.core.comic.ComicContainer.SEVEN_ZIP,
                pageCount = 30,
                preparedAt = 1L,
            ).needsContentPreparation(),
        )
    }

    @Test
    fun `rar 已复制到本地视为就绪`() {
        assertEquals(
            false,
            comic(
                com.llzx373.foldreader.core.comic.ComicContainer.TAR,
                pageCount = 12,
                localPath = "/data/comics/local/hash/pages",
            ).needsContentPreparation(),
        )
    }

    @Test
    fun `顺序容器页数已知但未预热也未本地化仍提示`() {
        // 备份恢复的场景：页数随备份回来了，但解压缓存与本地副本都没跟过来
        assertTrue(
            comic(com.llzx373.foldreader.core.comic.ComicContainer.RAR, pageCount = 120)
                .needsContentPreparation(),
        )
    }
}

/** M34：批量导出正文的默认文件名规则（非法字符清洗 + 截断）。 */
class BatchExportFileNameTest {

    @Test
    fun `路径分隔符等非法字符清洗为下划线`() {
        assertEquals(
            "a_b_c.txt",
            BookshelfViewModel.batchExportFileName("a/b\\c"),
        )
        assertEquals(
            "书_名.txt",
            BookshelfViewModel.batchExportFileName("书:名"),
        )
    }

    @Test
    fun `空名兜底且超长截断`() {
        assertEquals("book.txt", BookshelfViewModel.batchExportFileName("  "))
        assertEquals(
            "x".repeat(80) + ".txt",
            BookshelfViewModel.batchExportFileName("x".repeat(200)),
        )
    }
}

package com.llzx373.foldreader.core.pdf

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.llzx373.foldreader.FoldReaderApplication
import com.llzx373.foldreader.core.data.db.BookFormat
import com.llzx373.foldreader.core.data.db.BookSource
import com.llzx373.foldreader.core.paged.PagedPageImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * PDF 的端到端链路（走真实的 AppContainer：真库、真书架实体、真沙箱渲染）：
 * 导入 → books 表里出现 PDF → 打开页位图来源 → 出图 + 宽高比。
 *
 * 单元测试覆盖不到这条链路（渲染在 native / 沙箱进程里），所以只能跑在真机或模拟器上。
 */
@RunWith(AndroidJUnit4::class)
class PdfAppPathTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container get() = (context.applicationContext as FoldReaderApplication).container

    @Test
    fun `导入到渲染的整条链路`() = runBlocking {
        val file = writeSamplePdf(context, pageCount = 3, name = "app-path.pdf")

        val result = container.importBookUseCase.import(
            uri = Uri.fromFile(file),
            source = BookSource.IMPORT,
        )
        val imported = result as com.llzx373.foldreader.feature.importer.ImportBookUseCase.Result.Imported
        val book = container.bookshelfRepository.getBook(imported.bookId)
        assertNotNull("导入后应能在书架里查到", book)
        assertEquals(BookFormat.PDF, book!!.format)
        assertEquals("app-path", book.title)

        container.openPagedSource(book, password = null).use { source ->
            assertEquals(3, source.pageCount)

            val image = source.loadPage(0, 600, 800)
            assertTrue("第 0 页应出一张 Still 位图", image is PagedPageImage.Still)
            val bitmap = (image as PagedPageImage.Still).bitmap
            assertTrue(bitmap.width > 0 && bitmap.height > 0)

            val aspects = source.probeAspects()
            assertEquals(3, aspects.size)
            assertTrue("宽高比应为正数，实际 ${aspects[0]}", aspects[0] > 0f)

            val thumbnail = source.loadThumbnail(1, 120, 160)
            assertNotNull("缩略图应能出图", thumbnail)
        }

        // 阅读器打开时会回填页数，这里直接验证回填接口可用
        container.bookshelfRepository.updateComicPageCount(book.id, 3)
        assertEquals(3, container.bookshelfRepository.getBook(book.id)?.comicPageCount)
    }

    /**
     * 预热链路：导入 → 后台 PdfBox 解析 → 元数据/封面/目录/页数写进库、角标消失。
     * 这条链路串起了 DAO 回填、封面落盘、chapters 写入三处接线，单元测试覆盖不到。
     */
    @Test
    fun `后台预热补齐元数据_封面_目录_页数`() = runBlocking {
        val file = writeSamplePdfWithOutline(context, name = "app-path-outline.pdf")
        val result = container.importBookUseCase.import(Uri.fromFile(file), source = BookSource.IMPORT)
        val imported = result as com.llzx373.foldreader.feature.importer.ImportBookUseCase.Result.Imported

        // 导入只登记，解析在后台队列里；等 contentPreparedAt 落地（也就是角标消失）
        val book = withTimeout(60_000) {
            var current = container.bookshelfRepository.getBook(imported.bookId)
            while (current?.contentPreparedAt == null) {
                delay(200)
                current = container.bookshelfRepository.getBook(imported.bookId)
            }
            current!!
        }

        assertEquals(6, book.comicPageCount)
        assertEquals("带目录的样张", book.title)
        assertEquals("FoldReader 作者", book.author)
        assertEquals("提取链路验证", book.description)
        assertNotNull("封面应已渲染落盘", book.coverPath)

        val chapters = container.bookshelfRepository.getChapters(book.id)
        assertEquals(listOf("第一章", "1.1 小节", "第二章"), chapters.map { it.title })
        assertEquals(listOf(0, 1, 0), chapters.map { it.depth })
        assertEquals(listOf(1L, 2L, 4L), chapters.map { it.pageIndex })
        // 正文也压平了：同一批目录项同时带两套锚点——页序号给页式，字符偏移给文本模式。
        // 字符偏移是压平时实测出来的页首位置，不是把页序号当偏移用。
        assertTrue("文本型 PDF 应有压平产物", book.cleanedFilePath != null)
        assertTrue("应统计出字符数，实际 ${book.totalChars}", book.totalChars > 0)
        assertTrue(
            "后面的章头应落在更靠后的字符位置：${chapters.map { it.charStart }}",
            chapters[0].charStart > 0 &&
                chapters[1].charStart > chapters[0].charStart &&
                chapters[2].charStart > chapters[1].charStart,
        )
        assertTrue("章节区间应收口", chapters.last().charEnd == book.totalChars)

        // 最要紧的一条：字符锚点得真的落在它那一章的开头。
        // 第一章锚在第 2 页，所以从它的 charStart 读出来应该是第 2 页的正文。
        val content = container.pdfBookParser.openContent(Uri.fromFile(file))
        val anchor = chapters[0].charStart
        val atAnchor = content.read(anchor until anchor + 60)
        assertTrue("第一章应指向第 2 页开头，实际读到：$atAnchor", atAnchor.contains("Outline sample page 2"))
    }

    @Test
    fun `扫描件不产生压平产物但页数照常回填`() = runBlocking {
        val file = writeScanLikePdf(context, name = "app-path-scan.pdf")
        val result = container.importBookUseCase.import(Uri.fromFile(file), source = BookSource.IMPORT)
        val imported = result as com.llzx373.foldreader.feature.importer.ImportBookUseCase.Result.Imported

        val book = withTimeout(60_000) {
            var current = container.bookshelfRepository.getBook(imported.bookId)
            while (current?.contentPreparedAt == null) {
                delay(200)
                current = container.bookshelfRepository.getBook(imported.bookId)
            }
            current!!
        }

        assertEquals(3, book.comicPageCount)
        assertNull("扫描件没有正文", book.cleanedFilePath)
        assertEquals(0L, book.totalChars)
        // 页式阅读器据此显示「这是扫描件，暂不支持取字」而不是一个点了没反应的开关
        assertTrue("准备完成 + 没有正文 = 扫描件", book.contentPreparedAt != null)
    }
}

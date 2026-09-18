package com.llzx373.foldreader.core.pdf

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * PdfBox 提取链路：元数据 / 目录树（含层级与页锚点）/ 内容提取权限 / 封面渲染。
 *
 * 这条链路只在真机或模拟器上跑得起来（要 `PDFBoxResourceLoader` 的 assets 资源）。
 * 断言刻意卡在"层级 + 页序号"上：目录跳转全靠 pageIndex，取错页比取不到更糟。
 */
@RunWith(AndroidJUnit4::class)
class PdfBoxReaderTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun `元数据与目录树都能提取到`() {
        val file = writeSamplePdfWithOutline(context, name = "reader-outline.pdf")

        val info = PdfBoxReader.read(context, file.toURI().toString())
        assertNotNull("file:// 应能直接解析", info)
        info!!

        assertEquals(6, info.pageCount)
        assertEquals("带目录的样张", info.title)
        assertEquals("FoldReader 作者", info.author)
        assertEquals("提取链路验证", info.subject)
        assertEquals("pdf,outline,测试", info.keywords)
        assertTrue("样张没有加密限制，应允许提取内容", info.canExtractContent)
        assertNotNull("首页应能渲染出封面", info.cover)

        // 第一章(第 2 页) → 1.1 小节(第 3 页)，第二章(第 5 页)
        val titles = info.outline.map { it.title }
        assertEquals(listOf("第一章", "1.1 小节", "第二章"), titles)
        assertEquals(listOf(0, 1, 0), info.outline.map { it.depth })
        assertEquals(listOf(1, 2, 4), info.outline.map { it.pageIndex })
    }

    @Test
    fun `没有目录的文档返回空列表而不是报错`() {
        val file = writeSamplePdf(context, pageCount = 2, name = "reader-plain.pdf")

        val info = PdfBoxReader.read(context, file.toURI().toString())
        assertNotNull(info)
        assertEquals(2, info!!.pageCount)
        assertTrue("普通样张没有目录", info.outline.isEmpty())
        assertEquals("冒烟样张", info.title)
    }

    @Test
    fun `读不到的文件返回 null`() {
        val missing = context.cacheDir.resolve("does-not-exist.pdf")
        assertEquals(null, PdfBoxReader.read(context, missing.toURI().toString()))
    }
}

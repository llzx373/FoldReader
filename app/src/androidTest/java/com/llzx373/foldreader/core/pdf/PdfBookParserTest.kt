package com.llzx373.foldreader.core.pdf

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.llzx373.foldreader.FoldReaderApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 文本型 PDF「当电子书读」的压平链路。
 *
 * 关键分界：文本型 → 产出压平产物（进 TXT 管线，免费获得分页/搜索/书签）；
 * 扫描件 → **不产出**任何产物，也不报错（它只是没得读，不是坏了）。
 */
@RunWith(AndroidJUnit4::class)
class PdfBookParserTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container get() = (context.applicationContext as FoldReaderApplication).container

    @Test
    fun `文本型 PDF 能抽出正文并读出内容`() = runBlocking {
        val file = writeSamplePdfWithOutline(context, name = "flatten-text.pdf")
        val parser = container.pdfBookParser

        val charCount = parser.prewarmAndCharCount(Uri.fromFile(file))
        assertNotNull("文本型 PDF 应能压平", charCount)
        assertTrue("字符数应为正，实际 $charCount", (charCount ?: 0) > 0)

        val content = parser.openContent(Uri.fromFile(file))
        val head = content.read(0L until 300L)
        assertTrue("正文应含第一页的文字，实际：$head", head.contains("Outline sample page 1"))

        // 纸书页码按页给，文本模式下页边界是确定的
        val labels = parser.pageLabels(Uri.fromFile(file))
        assertEquals(6, labels?.size)
        assertEquals("第 1 页", labels?.first()?.label)
    }

    @Test
    fun `扫描件不产出压平产物且不报错`() = runBlocking {
        val file = writeScanLikePdf(context, name = "flatten-scan.pdf")
        val parser = container.pdfBookParser

        assertNull("扫描件抽不出正文", parser.prewarmAndCharCount(Uri.fromFile(file)))
        assertTrue("预热的 prewarm 应安静返回", runCatching { parser.prewarm(Uri.fromFile(file)) }.isSuccess)
    }
}

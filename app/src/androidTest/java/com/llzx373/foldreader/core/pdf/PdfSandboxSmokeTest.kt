package com.llzx373.foldreader.core.pdf

import android.graphics.Bitmap
import android.net.Uri
import android.util.Size
import androidx.pdf.SandboxedPdfLoader
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P1 可行性冒烟：这条链路是整套 PDF 方案的前提，而它不是单元测试能覆盖的
 * （渲染在 native / 沙箱进程里），所以只能跑在真机或模拟器上。
 *
 * 验两件事：
 *  1. `androidx.pdf` 的沙箱文档服务能独立出页位图（不需要它的成品 UI）；
 *  2. PdfBox-Android 能读元数据与抽文本（含字体资源，顺带验证 R8/资源打包没问题）。
 *
 * 测试用的 PDF 由 PdfBox 现场生成，不往仓库塞二进制样张。
 */
@RunWith(AndroidJUnit4::class)
class PdfSandboxSmokeTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun `沙箱文档服务能出页位图`() = runBlocking {
        val file = writeSamplePdf(context, pageCount = 3, name = "smoke-3.pdf")
        val loader = SandboxedPdfLoader(context)

        val document = loader.openDocument(Uri.fromFile(file), password = null)
        try {
            assertEquals(3, document.pageCount)

            val bitmap: Bitmap? = document.getPageBitmapSource(0).use { source ->
                source.getBitmap(Size(600, 800), null)
            }
            assertNotNull("第 0 页应能渲染出位图", bitmap)
            assertTrue("位图应有内容", bitmap!!.width > 0 && bitmap.height > 0)
            // 内存预算就是按这个算的：平台 PdfRenderer 只能出 ARGB_8888，
            // 比漫画那条路的 RGB_565 占一倍——换算"能缓存几页"时别按 2 字节/像素估
            assertEquals(Bitmap.Config.ARGB_8888, bitmap.config)
        } finally {
            document.close()
        }
    }

    @Test
    fun `批量页面信息能给出尺寸`() = runBlocking {
        val file = writeSamplePdf(context, pageCount = 3, name = "infos.pdf")
        val document = SandboxedPdfLoader(context).openDocument(Uri.fromFile(file), null)
        try {
            val infos = runCatching { document.getPageInfos(0..2) }
                .getOrElse { throw AssertionError("getPageInfos 抛异常：$it") }
            assertEquals("应返回每页一条信息", 3, infos.size)
            val first = infos.first()
            assertTrue(
                "宽高应为正数，实际 page=${first.pageNum} w=${first.width} h=${first.height}",
                first.width > 0 && first.height > 0,
            )
        } finally {
            document.close()
        }
    }

    @Test
    fun `PdfBox 能读元数据与正文`() {
        val file = writeSamplePdf(context, pageCount = 2, name = "smoke-2.pdf")

        PDDocument.load(file).use { document ->
            assertEquals(2, document.numberOfPages)
            assertEquals("冒烟样张", document.documentInformation.title)
            assertEquals("FoldReader", document.documentInformation.author)

            val text = PDFTextStripper().apply { sortByPosition = true }.getText(document)
            assertTrue("应抽出第 1 页文字，实际：$text", text.contains("FoldReader page 1"))
            assertTrue("应抽出第 2 页文字", text.contains("FoldReader page 2"))
            println("smoke: extracted ${text.length} chars")
        }
    }
}

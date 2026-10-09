package com.llzx373.foldreader.core.pdf

import android.net.Uri
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * 「抽取抛异常」与「真扫描件」的分流（B6）：抽取异常上抛 [TextExtractionException]
 * （界面报「提取失败，可重试」），不能并入 null 路径被误告成扫描件。
 */
@RunWith(RobolectricTestRunner::class)
class PdfBoxReaderExtractionTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** stripText 是 object 上的测试缝，每个用例用完都复位成默认实现。 */
    @After
    fun resetStripper() {
        PdfBoxReader.stripText = defaultStripText
    }

    private val defaultStripText = PdfBoxReader.stripText

    /** 造一个空白（无文字）PDF，经 content:// 喂给解析器（Windows 下 file:// URI 路径不可靠）。 */
    private fun blankPdfUri(): Uri {
        val file = File(tmp.newFolder(), "blank.pdf")
        PDDocument().use { doc ->
            doc.addPage(PDPage())
            doc.save(file)
        }
        val uri = Uri.parse("content://test/blank.pdf")
        val resolver = RuntimeEnvironment.getApplication().contentResolver
        shadowOf(resolver).registerInputStreamSupplier(uri) { FileInputStream(file) }
        return uri
    }

    @Test
    fun `抽取抛异常时上抛 TextExtractionException 而非落入扫描件分支`() {
        PdfBoxReader.stripText = { throw IOException("字体表损坏") }

        val error = assertThrows(TextExtractionException::class.java) {
            PdfBoxReader.read(
                RuntimeEnvironment.getApplication(),
                blankPdfUri().toString(),
                textTarget = tmp.newFile("out.txt"),
            )
        }
        assertEquals(TextExtractionException.MESSAGE, error.message)
    }

    @Test
    fun `真扫描件（抽完没字）仍返回 text 为 null 且不抛异常`() {
        val info = PdfBoxReader.read(
            RuntimeEnvironment.getApplication(),
            blankPdfUri().toString(),
            textTarget = tmp.newFile("out.txt"),
        )

        assertNotNull("文档本身解析成功", info)
        assertNull("没字 = 扫描件分支", info?.text)
    }
}

package com.llzx373.foldreader.core.format

import com.llzx373.foldreader.core.data.db.BookFormat
import com.llzx373.foldreader.core.format.epub.TestEpubs
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatDetectorTest {

    /** 合成 zip local file header：第一个条目 mimetype（stored）+ 指定内容。 */
    private fun zipHead(entryName: String, content: ByteArray): ByteArray {
        val name = entryName.toByteArray(Charsets.UTF_8)
        val header = ByteArray(30)
        header[0] = 0x50; header[1] = 0x4B; header[2] = 0x03; header[3] = 0x04
        header[26] = (name.size and 0xFF).toByte()
        header[27] = (name.size shr 8).toByte()
        return header + name + content + ByteArray(64)
    }

    private val epubMagic = "application/epub+zip".toByteArray(Charsets.US_ASCII)

    @Test
    fun `zip 头含 mimetype 条目判定为 EPUB（无扩展名也靠魔数）`() {
        val head = zipHead("mimetype", epubMagic)
        assertEquals(BookFormat.EPUB, FormatDetector.detect(null, null, head))
    }

    @Test
    fun `真实构造的 EPUB 文件头部判定为 EPUB`() {
        val file = File.createTempFile("detector", ".bin")
        try {
            TestEpubs.write(file, TestEpubs.epub2())
            val head = file.inputStream().use { it.readNBytes(64 * 1024) }
            assertEquals(BookFormat.EPUB, FormatDetector.detect("随便.bin", null, head))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `扩展名 epub 即使魔数缺失也判定 EPUB`() {
        assertEquals(BookFormat.EPUB, FormatDetector.detect("book.EPUB", null, ByteArray(0)))
        assertEquals(
            BookFormat.EPUB,
            FormatDetector.detect(null, FormatDetector.EPUB_MIME_TYPE, ByteArray(0)),
        )
    }

    @Test
    fun `txt 扩展名与 text_plain 判定 TXT`() {
        assertEquals(BookFormat.TXT, FormatDetector.detect("novel.txt", null, ByteArray(0)))
        assertEquals(BookFormat.TXT, FormatDetector.detect(null, "text/plain", ByteArray(0)))
    }

    @Test
    fun `PDF 头部不识别为已知格式但 isPdf 为真`() {
        val head = "%PDF-1.7\n".toByteArray()
        assertNull(FormatDetector.detect("doc.pdf", "application/pdf", head))
        assertTrue(FormatDetector.isPdf(head))
        assertFalse(FormatDetector.isPdf("not a pdf".toByteArray()))
    }

    @Test
    fun `普通 zip（首条目非 mimetype）不误判 EPUB`() {
        val head = zipHead("AndroidManifest.xml", ByteArray(0))
        assertNull(FormatDetector.detect("app.zip", null, head))
    }

    @Test
    fun `mimetype 条目内容不符时不靠魔数误判 EPUB`() {
        val head = zipHead("mimetype", "text/plain".toByteArray())
        assertNull(FormatDetector.detect("x.bin", null, head))
        // 魔数不符但扩展名是 epub → 仍按 EPUB（上层解析失败会回退/报错）
        assertEquals(BookFormat.EPUB, FormatDetector.detect("x.epub", null, head))
    }

    @Test
    fun `未知内容返回 null`() {
        assertNull(FormatDetector.detect("data.bin", null, "hello world".toByteArray()))
        assertNull(FormatDetector.detect(null, null, ByteArray(0)))
    }

    @Test
    fun `FB2 裸 XML 根标签判定（含 BOM 前导空白 注释）`() {
        val xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<FictionBook xmlns=\"http://www.gribuser.ru/xml/fictionbook/2.0\">"
        assertEquals(BookFormat.FB2, FormatDetector.detect(null, null, xml.toByteArray()))

        val withBom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + xml.toByteArray()
        assertEquals(BookFormat.FB2, FormatDetector.detect(null, null, withBom))

        val withComment = ("<?xml version=\"1.0\"?>\n<!-- comment -->\n<FictionBook>").toByteArray()
        assertEquals(BookFormat.FB2, FormatDetector.detect("x", null, withComment))

        // 根标签不是 FictionBook 的普通 XML 不误判
        assertNull(FormatDetector.detect("x", null, "<?xml version=\"1.0\"?><html>".toByteArray()))
    }

    @Test
    fun `FB2 zip 变体靠首条目名与扩展名判定`() {
        val head = zipHead("story.fb2", "x".toByteArray())
        assertEquals(BookFormat.FB2, FormatDetector.detect(null, null, head))
        assertEquals(BookFormat.FB2, FormatDetector.detect("a.fb2.zip", null, ByteArray(0)))
        assertEquals(BookFormat.FB2, FormatDetector.detect("b.FB2", null, ByteArray(0)))
        // 普通 zip（非 fb2 条目）不误判
        assertNull(FormatDetector.detect("c.zip", null, zipHead("story.txt", ByteArray(0))))
    }
}

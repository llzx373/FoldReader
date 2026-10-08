package com.llzx373.foldreader.core.format

import com.llzx373.foldreader.core.data.db.BookFormat
import com.llzx373.foldreader.core.format.docx.TestDocx
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
    fun `PDF 按魔数与扩展名识别`() {
        val head = "%PDF-1.7\n".toByteArray()
        assertEquals(BookFormat.PDF, FormatDetector.detect("doc.pdf", "application/pdf", head))
        // 扩展名/MIME 单独也能认出来（魔数采样不到时）
        assertEquals(BookFormat.PDF, FormatDetector.detect("doc.pdf", null, ByteArray(0)))
        assertEquals(BookFormat.PDF, FormatDetector.detect(null, FormatDetector.PDF_MIME_TYPE, ByteArray(0)))
        assertTrue(FormatDetector.isPdf(head))
        assertFalse(FormatDetector.isPdf("not a pdf".toByteArray()))
    }

    @Test
    fun `普通 zip 不误判 EPUB 而是按漫画容器处理`() {
        // 有意为之：相当一部分漫画就是没改名的 zip。判成漫画后若里面没有图片，
        // 导入会明确报「压缩包内没有可显示的图片」，比当成文本解出满屏乱码好。
        val head = zipHead("AndroidManifest.xml", ByteArray(0))
        assertEquals(BookFormat.COMIC, FormatDetector.detect("app.zip", null, head))
    }

    @Test
    fun `mimetype 条目内容不符时不靠魔数误判 EPUB`() {
        val head = zipHead("mimetype", "text/plain".toByteArray())
        // 只有 EPUB 规范要求的 mimetype 内容才算 EPUB；不符就只是个普通 zip → 漫画容器
        assertEquals(BookFormat.COMIC, FormatDetector.detect("x.bin", null, head))
        // 魔数不符但扩展名是 epub → 仍按 EPUB（上层解析失败会回退/报错）
        assertEquals(BookFormat.EPUB, FormatDetector.detect("x.epub", null, head))
    }

    @Test
    fun `未知内容返回 null`() {
        assertNull(FormatDetector.detect("data.bin", null, "hello world".toByteArray()))
        assertNull(FormatDetector.detect(null, null, ByteArray(0)))
    }

    @Test
    fun `DOCX 首条目 word 前缀按魔数判定`() {
        val head = zipHead("word/document.xml", ByteArray(0))
        assertEquals(BookFormat.DOCX, FormatDetector.detect("x.bin", null, head))
        assertTrue(FormatDetector.isDocx(head, null, null))
    }

    @Test
    fun `DOCX 首条目 Content_Types 需扩展名或 MIME 佐证`() {
        val head = zipHead("[Content_Types].xml", ByteArray(0))
        assertEquals(BookFormat.DOCX, FormatDetector.detect("a.docx", null, head))
        assertEquals(
            BookFormat.DOCX,
            FormatDetector.detect(null, FormatDetector.DOCX_MIME_TYPE, head),
        )
        // 其他 OOXML 包（xlsx 等）首条目也常是它：无佐证不收，按漫画容器处理
        assertEquals(BookFormat.COMIC, FormatDetector.detect("a.xlsx", null, head))
    }

    @Test
    fun `docx 扩展名与 MIME 单独可判定`() {
        assertEquals(BookFormat.DOCX, FormatDetector.detect("report.DOCX", null, ByteArray(0)))
        assertEquals(
            BookFormat.DOCX,
            FormatDetector.detect(null, FormatDetector.DOCX_MIME_TYPE, ByteArray(0)),
        )
    }

    @Test
    fun `真实构造的 DOCX 文件头部判定为 DOCX`() {
        // Word 典型顺序：[Content_Types].xml 打头，需扩展名佐证
        val file = File.createTempFile("detector", ".bin")
        try {
            TestDocx.write(file, TestDocx.minimal())
            val head = file.inputStream().use { it.readNBytes(64 * 1024) }
            assertEquals(BookFormat.DOCX, FormatDetector.detect("随便.docx", null, head))
        } finally {
            file.delete()
        }
        // word/ 打头的 zip 无需佐证（有些生成器不保证 Content_Types 在最前）
        val entries = TestDocx.minimal()
        val wordFirst = linkedMapOf<String, String>()
        wordFirst["word/document.xml"] = entries.getValue("word/document.xml")
        wordFirst.putAll(entries - "word/document.xml")
        val file2 = File.createTempFile("detector", ".bin")
        try {
            TestDocx.write(file2, wordFirst)
            val head = file2.inputStream().use { it.readNBytes(64 * 1024) }
            assertEquals(BookFormat.DOCX, FormatDetector.detect("随便.bin", null, head))
        } finally {
            file2.delete()
        }
    }

    @Test
    fun `DOCX 判定不误伤 EPUB 与普通 zip`() {
        // EPUB 魔数判定在 DOCX 之前：即使扩展名是 docx 也按 EPUB
        val epubHead = zipHead("mimetype", epubMagic)
        assertEquals(BookFormat.EPUB, FormatDetector.detect("x.docx", null, epubHead))
        // 普通 zip（首条目既非 word/ 也非 [Content_Types].xml）仍按漫画容器
        val plainZip = zipHead("AndroidManifest.xml", ByteArray(0))
        assertEquals(BookFormat.COMIC, FormatDetector.detect("app.zip", null, plainZip))
        assertFalse(FormatDetector.isDocx(plainZip, "app.zip", null))
    }

    @Test
    fun `HTML 按 doctype 与 html 根标签判定（大小写不敏感）`() {
        assertEquals(
            BookFormat.HTML,
            FormatDetector.detect(null, null, "<!DOCTYPE html><html><body>x</body></html>".toByteArray()),
        )
        assertEquals(BookFormat.HTML, FormatDetector.detect(null, null, "<!doctype html>".toByteArray()))
        // HTML4 的 PUBLIC 写法也被前缀覆盖
        assertEquals(
            BookFormat.HTML,
            FormatDetector.detect(
                null,
                null,
                "<!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML 1.0//EN\" \"x\">".toByteArray(),
            ),
        )
        assertEquals(
            BookFormat.HTML,
            FormatDetector.detect(null, null, "<html lang=\"zh\"><head></head></html>".toByteArray()),
        )
        assertEquals(BookFormat.HTML, FormatDetector.detect(null, null, "<HTML>".toByteArray()))
    }

    @Test
    fun `HTML 判定容忍前导空白 BOM 与 XML 声明`() {
        assertEquals(BookFormat.HTML, FormatDetector.detect(null, null, "  \n<!DOCTYPE html>".toByteArray()))
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        assertEquals(BookFormat.HTML, FormatDetector.detect(null, null, bom + "<html>".toByteArray()))
        assertEquals(
            BookFormat.HTML,
            FormatDetector.detect(
                null,
                null,
                "<?xml version=\"1.0\"?><html xmlns=\"http://www.w3.org/1999/xhtml\">".toByteArray(),
            ),
        )
        assertEquals(
            BookFormat.HTML,
            FormatDetector.detect(null, null, "<!-- comment -->\n<html>".toByteArray()),
        )
    }

    @Test
    fun `html htm 扩展名与 HTML MIME 判定`() {
        assertEquals(BookFormat.HTML, FormatDetector.detect("page.html", null, ByteArray(0)))
        assertEquals(BookFormat.HTML, FormatDetector.detect("page.HTM", null, ByteArray(0)))
        assertEquals(BookFormat.HTML, FormatDetector.detect(null, "text/html", ByteArray(0)))
        assertEquals(BookFormat.HTML, FormatDetector.detect(null, "application/xhtml+xml", ByteArray(0)))
    }

    @Test
    fun `HTML 判定不误伤 FB2 与普通 XML`() {
        // FB2 判定在 HTML 之前，根标签 FictionBook 先命中
        val fb2 = "<FictionBook xmlns=\"http://www.gribuser.ru/xml/fictionbook/2.0\">".toByteArray()
        assertEquals(BookFormat.FB2, FormatDetector.detect(null, null, fb2))
        // 非 html 的 DOCTYPE 与普通 XML 根标签不认作 HTML（识别不出返回 null，上层按 TXT）
        assertNull(
            FormatDetector.detect("x", null, "<?xml version=\"1.0\"?><rss version=\"2.0\">".toByteArray()),
        )
        assertNull(FormatDetector.detect("x", null, "<!DOCTYPE svg><svg/>".toByteArray()))
        // zip 不认作 HTML
        assertFalse(FormatDetector.isHtml(zipHead("word/document.xml", ByteArray(0))))
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
        assertNull(FormatDetector.detect("x", null, "<?xml version=\"1.0\"?><htmlx>".toByteArray()))
        // 但 <html> 根标签现在由 HTML 格式接管（xhtml 判定）
        assertEquals(
            BookFormat.HTML,
            FormatDetector.detect("x", null, "<?xml version=\"1.0\"?><html>".toByteArray()),
        )
    }

    @Test
    fun `FB2 zip 变体靠首条目名与扩展名判定`() {
        val head = zipHead("story.fb2", "x".toByteArray())
        assertEquals(BookFormat.FB2, FormatDetector.detect(null, null, head))
        assertEquals(BookFormat.FB2, FormatDetector.detect("a.fb2.zip", null, ByteArray(0)))
        assertEquals(BookFormat.FB2, FormatDetector.detect("b.FB2", null, ByteArray(0)))
    }

    @Test
    fun `漫画容器按扩展名与魔数判定`() {
        val rar4 = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00, 0x01)
        val sevenZip = byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C, 0, 0)
        val tar = ByteArray(512).also { "ustar".toByteArray(Charsets.US_ASCII).copyInto(it, 257) }

        assertEquals(BookFormat.COMIC, FormatDetector.detect("book.cbz", null, ByteArray(0)))
        assertEquals(BookFormat.COMIC, FormatDetector.detect("book.cbr", null, ByteArray(0)))
        assertEquals(BookFormat.COMIC, FormatDetector.detect("book.cbt", null, ByteArray(0)))
        assertEquals(BookFormat.COMIC, FormatDetector.detect("book.cb7", null, ByteArray(0)))
        assertEquals(BookFormat.COMIC, FormatDetector.detect(null, null, rar4))
        assertEquals(BookFormat.COMIC, FormatDetector.detect(null, null, sevenZip))
        assertEquals(BookFormat.COMIC, FormatDetector.detect("book.tar", null, tar))
        assertEquals(BookFormat.COMIC, FormatDetector.detect(null, "application/vnd.comicbook+zip", ByteArray(0)))
    }

    @Test
    fun `tar 的 ustar 字节串出现在普通文本里不误判`() {
        // 文本文件偏移 257 恰好是 ustar：没有扩展名印证时不能当 tar
        val text = ByteArray(512) { 'a'.code.toByte() }
            .also { "ustar".toByteArray(Charsets.US_ASCII).copyInto(it, 257) }

        assertNull(FormatDetector.detect("readme.bin", null, text))
        assertEquals(BookFormat.TXT, FormatDetector.detect("readme.txt", null, text))
        assertEquals(BookFormat.COMIC, FormatDetector.detect("book.tar", null, text))
    }

    @Test
    fun `Markdown 按扩展名与 MIME 判定（无魔数）`() {
        assertEquals(BookFormat.MARKDOWN, FormatDetector.detect("notes.md", null, ByteArray(0)))
        assertEquals(BookFormat.MARKDOWN, FormatDetector.detect("notes.MARKDOWN", null, ByteArray(0)))
        assertEquals(BookFormat.MARKDOWN, FormatDetector.detect(null, "text/markdown", ByteArray(0)))
        assertEquals(BookFormat.MARKDOWN, FormatDetector.detect(null, "text/x-markdown", ByteArray(0)))
        // text/plain MIME 的 .md 走 Markdown 管线而不是 TXT
        assertEquals(BookFormat.MARKDOWN, FormatDetector.detect("notes.md", "text/plain", ByteArray(0)))
        // 无扩展名无 MIME 的纯文本不受影响
        assertNull(FormatDetector.detect("readme", null, "# 标题\n".toByteArray()))
    }
}

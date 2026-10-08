package com.llzx373.foldreader.core.format.html

import com.llzx373.foldreader.core.format.BookContent
import com.llzx373.foldreader.core.format.txt.TxtBookContent
import com.llzx373.foldreader.core.format.txt.TxtIndexer
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.charset.Charset
import java.nio.file.StandardOpenOption
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser
import org.xmlpull.v1.XmlPullParser

class HtmlBookParserTest {

    private val newParser: () -> XmlPullParser = { KXmlParser() }

    private fun newParserFor(dir: File): HtmlBookParser = HtmlBookParser(
        convertedDir = dir,
        openFlattenedContent = { file -> openTxt(file) },
        openChannel = { error("测试只走 File 内部方法") },
        displayNameOf = { null },
        newParser = newParser,
    )

    private fun openTxt(file: File): BookContent {
        val channel = RandomAccessFile(file, "r").channel
        val index = TxtIndexer.index(
            FileChannel.open(file.toPath(), StandardOpenOption.READ),
            Charsets.UTF_8,
        )
        return TxtBookContent(channel, Charsets.UTF_8, index.offsetIndex)
    }

    private fun tempHtml(bytes: ByteArray): File {
        val file = File.createTempFile("html-parser", ".html")
        file.deleteOnExit()
        file.writeBytes(bytes)
        return file
    }

    private fun tempHtml(text: String): File = tempHtml(text.toByteArray(Charsets.UTF_8))

    private fun tempDir(): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "html-test-${System.nanoTime()}")
        dir.mkdirs()
        return dir
    }

    companion object {
        const val BASIC_HTML = """<!DOCTYPE html>
<html><head><title>测试书</title><meta name="author" content="作者乙"></head>
<body>
<h1>第一章</h1><p>正文一。</p><h2>第一节</h2><p>正文二。</p><h3>小节一</h3><p>正文三。</p>
</body></html>"""

        /** BASIC_HTML 的压平结果（块间一个空行）。 */
        const val FULL_TEXT = "第一章\n\n正文一。\n\n第一节\n\n正文二。\n\n小节一\n\n正文三。"
    }

    @Test
    fun `端到端 压平后按 h 系列标题分章且边界文本正确`() = runBlocking {
        val html = tempHtml(BASIC_HTML)
        val parser = newParserFor(tempDir())

        val flattened = parser.ensureFlattenedFile(html)
        assertTrue(flattened.fresh)
        assertEquals(listOf("第一章", "第一节", "小节一"), flattened.chapters.map { it.title })
        assertEquals(listOf(0, 1, 2), flattened.chapters.map { it.depth })

        val content = parser.openContentFile(html)
        try {
            assertEquals(FULL_TEXT.length.toLong(), content.charCount)
            assertEquals(FULL_TEXT, content.read(0L until content.charCount))

            // 章节边界：charStart = 标题文本在正文中的位置
            val ch1 = flattened.chapters[0]
            val ch2 = flattened.chapters[1]
            val ch3 = flattened.chapters[2]
            assertEquals(0L, ch1.charStart)
            assertEquals(FULL_TEXT.indexOf("第一节").toLong(), ch2.charStart)
            assertEquals(FULL_TEXT.indexOf("小节一").toLong(), ch3.charStart)
            assertEquals(ch1.charEnd, ch2.charStart)
            assertEquals(ch2.charEnd, ch3.charStart)
            assertEquals(content.charCount, ch3.charEnd)

            // parseChapters 与压平时记录的边界一致（经 sidecar 缓存重放）
            assertEquals(flattened.chapters, parser.parseChaptersFile(html))
        } finally {
            (content as? java.io.Closeable)?.close()
        }
    }

    @Test
    fun `非 h 标签不进目录`() {
        val html = tempHtml(
            """<html><body>
<div class="title">大标题</div><p><b>加粗段</b></p><h1>真标题</h1><p>正文。</p>
</body></html>""",
        )
        val parser = newParserFor(tempDir())
        val chapters = parser.ensureFlattenedFile(html).chapters
        assertEquals(listOf("真标题"), chapters.map { it.title })
        assertEquals(listOf(0), chapters.map { it.depth })
    }

    @Test
    fun `无标题退化为单正文章`() = runBlocking {
        val html = tempHtml("<html><body><p>只有正文。</p></body></html>")
        val parser = newParserFor(tempDir())
        val chapters = parser.ensureFlattenedFile(html).chapters
        assertEquals(1, chapters.size)
        assertEquals("正文", chapters[0].title)
        assertEquals(0, chapters[0].depth)
        val content = parser.openContentFile(html)
        try {
            assertEquals(content.charCount, chapters[0].charEnd)
            assertEquals("只有正文。", content.read(0L until content.charCount))
        } finally {
            (content as? java.io.Closeable)?.close()
        }
    }

    @Test
    fun `消毒 危险标签与事件属性被剥 链接白名单生效`() {
        val result = HtmlSanitizer.sanitize(
            """<p onclick="evil()">正文</p><script>alert(1)</script>""" +
                """<iframe src="https://evil.example">框架</iframe>""" +
                """<a href="javascript:x()">恶链</a><a href="https://ok.example">好链</a>""" +
                """<a href="#section1">内链</a><a href="mailto:a@b.c">邮箱</a>""",
            isFragment = false,
        )
        val html = result.html
        assertFalse(html.contains("<script"))
        assertFalse(html.contains("alert(1)"))
        assertFalse(html.contains("<iframe"))
        assertFalse(html.contains("框架"))
        assertFalse(html.contains("onclick"))
        assertFalse(html.contains("javascript:"))
        assertTrue(html.contains("""href="https://ok.example""""))
        assertTrue(html.contains("""href="#section1""""))
        assertTrue(html.contains("""href="mailto:a@b.c""""))
        assertTrue(html.contains("正文"))
    }

    @Test
    fun `消毒 外链图剥掉 data 内联图保留`() {
        val result = HtmlSanitizer.sanitize(
            """<img src="https://e.example/a.png"><img src="data:image/png;base64,AAAA" srcset="x">""",
            isFragment = false,
        )
        assertFalse(result.html.contains("e.example"))
        assertFalse(result.html.contains("srcset"))
        assertTrue(result.html.contains("data:image/png;base64,AAAA"))
    }

    @Test
    fun `meta 元数据在消毒之前提取`() {
        val parser = newParserFor(tempDir())
        val meta = parser.metaOf(BASIC_HTML)
        assertEquals("测试书", meta.title)
        assertEquals("作者乙", meta.author)

        // 消毒会删 meta 标签：消毒产物里再取不到作者（验证「先取后消毒」的必要性）
        assertFalse(HtmlSanitizer.sanitize(BASIC_HTML, isFragment = false).html.contains("作者乙"))
    }

    @Test
    fun `消毒后正文不含 script 与 iframe 内容（全管线）`() = runBlocking {
        val html = tempHtml(
            """<html><body><h1>章</h1><p>可见正文。</p>""" +
                """<script>var secret = "不該出現";</script><iframe>框架文字</iframe></body></html>""",
        )
        val parser = newParserFor(tempDir())
        val content = parser.openContentFile(html)
        try {
            val text = content.read(0L until content.charCount)
            assertTrue(text.contains("可见正文。"))
            assertFalse(text.contains("不該出現"))
            assertFalse(text.contains("框架文字"))
        } finally {
            (content as? java.io.Closeable)?.close()
        }
    }

    @Test
    fun `GBK 无 BOM 样本正确探测并解码`() = runBlocking {
        val text = """<html><head><title>编码书</title></head><body>
<h1>章节</h1><p>这本书的正文内容来自一个简体中文页面，我们会在这里发现时间的关系。</p>
</body></html>"""
        val html = tempHtml(text.toByteArray(Charset.forName("GBK")))
        val parser = newParserFor(tempDir())
        val content = parser.openContentFile(html)
        try {
            val flat = content.read(0L until content.charCount)
            assertEquals("章节", parser.parseChaptersFile(html)[0].title)
            assertTrue(flat.contains("这本书的正文内容来自一个简体中文页面，我们会在这里发现时间的关系。"))
        } finally {
            (content as? java.io.Closeable)?.close()
        }
    }

    @Test
    fun `UTF-8 BOM 样本不泄漏到正文`() = runBlocking {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val html = tempHtml(bom + BASIC_HTML.toByteArray(Charsets.UTF_8))
        val parser = newParserFor(tempDir())
        val content = parser.openContentFile(html)
        try {
            assertEquals(FULL_TEXT, content.read(0L until content.charCount))
        } finally {
            (content as? java.io.Closeable)?.close()
        }
    }

    @Test
    fun `压平缓存命中时不重建`() {
        val html = tempHtml(BASIC_HTML)
        val parser = newParserFor(tempDir())
        val first = parser.ensureFlattenedFile(html)
        val second = parser.ensureFlattenedFile(html)
        assertTrue(first.fresh)
        assertFalse(second.fresh)
        assertEquals(first.file, second.file)
        assertEquals(first.chapters, second.chapters)
    }
}

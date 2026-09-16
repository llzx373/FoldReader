package com.llzx373.foldreader.core.format.fb2

import com.llzx373.foldreader.core.format.BookContent
import com.llzx373.foldreader.core.format.txt.TxtBookContent
import com.llzx373.foldreader.core.format.txt.TxtIndexer
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.charset.Charset
import java.nio.file.StandardOpenOption
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser
import org.xmlpull.v1.XmlPullParser

class Fb2BookParserTest {

    private val newParser: () -> XmlPullParser = { KXmlParser() }

    private fun newParserFor(dir: File): Fb2BookParser = Fb2BookParser(
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

    private fun tempDir(): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "fb2-test-${System.nanoTime()}")
        dir.mkdirs()
        return dir
    }

    private fun tempFb2(xml: String, charset: Charset = Charsets.UTF_8): File {
        val file = File.createTempFile("fb2-parser", ".fb2")
        file.deleteOnExit()
        file.writeBytes(xml.toByteArray(charset))
        return file
    }

    private fun tempFb2Zip(xml: String): File {
        val file = File.createTempFile("fb2-parser", ".fb2.zip")
        file.deleteOnExit()
        ZipOutputStream(file.outputStream().buffered()).use { zos ->
            zos.putNextEntry(ZipEntry("book.fb2"))
            zos.write(xml.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        return file
    }

    private val sampleFb2 = """<?xml version="1.0" encoding="UTF-8"?>
<FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0" xmlns:l="http://www.w3.org/1999/xlink">
<description>
 <title-info>
  <author><first-name>列夫</first-name><middle-name>尼古拉耶维奇</middle-name><last-name>托尔斯泰</last-name></author>
  <book-title>测试小说</book-title>
 </title-info>
</description>
<body>
 <section>
  <title><p>第一部</p></title>
  <p>开场白。</p>
  <empty-line/>
  <section>
   <title><p>第一章</p></title>
   <p>第一章<emphasis>正文</emphasis>。</p>
  </section>
  <section>
   <title><p>第二章</p></title>
   <poem><stanza><v>一行</v><v>二行</v></stanza></poem>
  </section>
 </section>
</body>
<body name="notes"><section><p>注释内容不应出现。</p></section></body>
<binary id="im1" content-type="image/png">aGVsbG8=</binary>
</FictionBook>"""

    // 压平结果：title/section 边界 → 段间空行；诗行单换行；empty-line → 空行；
    // 命名 body 与 binary 不进入文本
    private val sampleText =
        "第一部\n\n开场白。\n\n第一章\n\n第一章正文。\n\n第二章\n\n一行\n二行"

    @Test
    fun `元数据取 title-info 的 book-title 与 author 拼接`() {
        val parser = newParserFor(tempDir())
        val meta = parser.readMetaFile(tempFb2(sampleFb2))
        assertEquals("测试小说", meta.title)
        assertEquals("列夫 尼古拉耶维奇 托尔斯泰", meta.author)
    }

    @Test
    fun `端到端 层级章节拍平且边界文本正确`() = runBlocking {
        val parser = newParserFor(tempDir())
        val fb2 = tempFb2(sampleFb2)

        val flattened = parser.ensureFlattenedFile(fb2)
        assertTrue(flattened.fresh)
        assertEquals(listOf("第一部", "第一章", "第二章"), flattened.chapters.map { it.title })

        val content = parser.openContentFile(fb2)
        try {
            assertEquals(sampleText.length.toLong(), content.charCount)
            assertEquals(sampleText, content.read(0L until content.charCount))

            val (part, ch1, ch2) = flattened.chapters
            assertEquals(0L, part.charStart)
            assertEquals(part.charEnd, ch1.charStart)
            assertEquals(ch1.charEnd, ch2.charStart)
            assertEquals(content.charCount, ch2.charEnd)
            assertEquals("第一章\n\n第一章正文。\n\n", content.read(ch1.charStart until ch1.charEnd))
            assertEquals("第二章\n\n一行\n二行", content.read(ch2.charStart until ch2.charEnd))

            // 注释 body 不进入文本
            assertFalse(sampleText.contains("注释内容"))

            assertEquals(flattened.chapters, parser.parseChaptersFile(fb2))
        } finally {
            (content as? java.io.Closeable)?.close()
        }
    }

    @Test
    fun `压平缓存命中时不重建`() {
        val parser = newParserFor(tempDir())
        val fb2 = tempFb2(sampleFb2)
        val first = parser.ensureFlattenedFile(fb2)
        val second = parser.ensureFlattenedFile(fb2)
        assertTrue(first.fresh)
        assertFalse(second.fresh)
        assertEquals(first.file, second.file)
        assertEquals(first.chapters, second.chapters)
    }

    @Test
    fun `无 title 的 section 不产生章节 全文无章节退化单章`() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
<description><title-info><book-title>无章节书</book-title></title-info></description>
<body><section><p>甲。</p><section><p>乙。</p></section></section></body>
</FictionBook>"""
        val parser = newParserFor(tempDir())
        val chapters = parser.ensureFlattenedFile(tempFb2(xml)).chapters
        assertEquals(1, chapters.size)
        assertEquals(0L, chapters[0].charStart)
        assertEquals("甲。\n\n乙。".length.toLong(), chapters[0].charEnd)
    }

    @Test
    fun `windows-1251 声明编码正确解码`() = runBlocking {
        val cp1251 = Charset.forName("windows-1251")
        val xml = """<?xml version="1.0" encoding="windows-1251"?>
<FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
<description><title-info><book-title>Книга</book-title></title-info></description>
<body><section><title><p>Глава первая</p></title><p>Текст главы.</p></section></body>
</FictionBook>"""
        val parser = newParserFor(tempDir())
        val fb2 = tempFb2(xml, cp1251)

        assertEquals("Книга", parser.readMetaFile(fb2).title)

        val flattened = parser.ensureFlattenedFile(fb2)
        assertEquals(listOf("Глава первая"), flattened.chapters.map { it.title })
        val content = parser.openContentFile(fb2)
        try {
            assertEquals("Глава первая\n\nТекст главы.", content.read(0L until content.charCount))
        } finally {
            (content as? java.io.Closeable)?.close()
        }
    }

    @Test
    fun `fb2_zip 变体走同一压平器`() = runBlocking {
        val parser = newParserFor(tempDir())
        val fb2zip = tempFb2Zip(sampleFb2)
        val flattened = parser.ensureFlattenedFile(fb2zip)
        assertEquals(listOf("第一部", "第一章", "第二章"), flattened.chapters.map { it.title })
        val content = parser.openContentFile(fb2zip)
        try {
            assertEquals(sampleText, content.read(0L until content.charCount))
        } finally {
            (content as? java.io.Closeable)?.close()
        }
        assertEquals("测试小说", parser.readMetaFile(fb2zip).title)
    }

    @Test
    fun `空行折叠与多 p 标题`() = runBlocking {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
<description><title-info><book-title>t</book-title></title-info></description>
<body><section>
<title><p>长</p><p>标题</p></title>
<p>段一</p><empty-line/><empty-line/><p>段二</p>
</section></body>
</FictionBook>"""
        val parser = newParserFor(tempDir())
        val fb2 = tempFb2(xml)
        val flattened = parser.ensureFlattenedFile(fb2)
        assertEquals(listOf("长 标题"), flattened.chapters.map { it.title })
        val content = parser.openContentFile(fb2)
        try {
            // 连续 empty-line 折叠为一个空行
            assertEquals("长\n\n标题\n\n段一\n\n段二", content.read(0L until content.charCount))
        } finally {
            (content as? java.io.Closeable)?.close()
        }
    }

    @Test
    fun `无 description 时元数据回退 null`() {
        val xml = """<?xml version="1.0"?><FictionBook><body><p>正文。</p></body></FictionBook>"""
        val parser = newParserFor(tempDir())
        val meta = parser.readMetaFile(tempFb2(xml))
        assertNull(meta.title)
        assertNull(meta.author)
    }
}

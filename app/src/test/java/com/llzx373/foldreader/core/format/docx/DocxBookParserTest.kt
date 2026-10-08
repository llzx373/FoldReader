package com.llzx373.foldreader.core.format.docx

import com.llzx373.foldreader.core.format.BookContent
import com.llzx373.foldreader.core.format.TextSpanType
import com.llzx373.foldreader.core.format.epub.HtmlTextFlattener
import com.llzx373.foldreader.core.format.txt.TxtBookContent
import com.llzx373.foldreader.core.format.txt.TxtIndexer
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser
import org.xmlpull.v1.XmlPullParser

class DocxBookParserTest {

    private val newParser: () -> XmlPullParser = { KXmlParser() }

    /** 尺寸探测假实现：夹具 PNG 只有魔数头，BitmapFactory 解不了。 */
    private val fakeImageSizer: (ByteArray) -> IntArray = { intArrayOf(100, 50) }

    private fun newParserFor(dir: File): DocxBookParser = DocxBookParser(
        convertedDir = dir,
        openFlattenedContent = { file -> openTxt(file) },
        openChannel = { error("测试只走 File 内部方法") },
        displayNameOf = { null },
        newParser = newParser,
        imageSizer = fakeImageSizer,
    )

    private fun openTxt(file: File): BookContent {
        val channel = RandomAccessFile(file, "r").channel
        val index = TxtIndexer.index(
            FileChannel.open(file.toPath(), StandardOpenOption.READ),
            Charsets.UTF_8,
        )
        return TxtBookContent(channel, Charsets.UTF_8, index.offsetIndex)
    }

    private fun tempDocx(entries: Map<String, ByteArray>): File {
        val file = File.createTempFile("docx-parser", ".docx")
        file.deleteOnExit()
        TestDocx.writeRaw(file, entries)
        return file
    }

    private fun tempDocx(entries: LinkedHashMap<String, String>): File {
        val file = File.createTempFile("docx-parser", ".docx")
        file.deleteOnExit()
        TestDocx.write(file, entries)
        return file
    }

    private fun tempDir(): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "docx-test-${System.nanoTime()}")
        dir.mkdirs()
        return dir
    }

    @Test
    fun `端到端 压平后按标题分章且边界文本正确`() = runBlocking {
        val docx = tempDocx(TestDocx.minimal())
        val parser = newParserFor(tempDir())

        val flattened = parser.ensureFlattenedFile(docx)
        assertTrue(flattened.fresh)
        assertEquals(listOf(TestDocx.CH1_TITLE, TestDocx.CH2_TITLE), flattened.chapters.map { it.title })
        assertEquals(listOf(0, 1), flattened.chapters.map { it.depth })

        val content = parser.openContentFile(docx)
        try {
            assertEquals(TestDocx.FULL_TEXT.length.toLong(), content.charCount)
            assertEquals(TestDocx.FULL_TEXT, content.read(0L until content.charCount))

            // 章节边界：charStart = 标题文本起点（章间空行归入上一章尾部）
            val ch1 = flattened.chapters[0]
            val ch2 = flattened.chapters[1]
            assertEquals(0L, ch1.charStart)
            assertEquals(ch1.charEnd, ch2.charStart)
            assertEquals(
                TestDocx.FULL_TEXT.indexOf(TestDocx.CH2_TITLE).toLong(),
                ch2.charStart,
            )
            assertEquals(content.charCount, ch2.charEnd)
            assertEquals(
                "${TestDocx.CH1_TITLE}\n\n${TestDocx.BODY1}${TestDocx.BOLD_TEXT}\n\n",
                content.read(ch1.charStart until ch1.charEnd),
            )
            assertEquals(
                "${TestDocx.CH2_TITLE}\n\n${TestDocx.BODY2}",
                content.read(ch2.charStart until ch2.charEnd),
            )

            // parseChapters 与压平时记录的边界一致（经 sidecar 缓存重放）
            assertEquals(flattened.chapters, parser.parseChaptersFile(docx))

            // 加粗 run → BOLD 样式 span
            val bold = parser.textSpansFile(docx).filter { it.type == TextSpanType.BOLD }
            assertEquals(1, bold.size)
            assertEquals(TestDocx.BOLD_TEXT, content.read(bold[0].start until bold[0].end))
        } finally {
            (content as? java.io.Closeable)?.close()
        }
    }

    @Test
    fun `压平缓存命中时不重建`() {
        val docx = tempDocx(TestDocx.minimal())
        val parser = newParserFor(tempDir())
        val first = parser.ensureFlattenedFile(docx)
        val second = parser.ensureFlattenedFile(docx)
        assertTrue(first.fresh)
        assertFalse(second.fresh)
        assertEquals(first.file, second.file)
        assertEquals(first.chapters, second.chapters)
    }

    @Test
    fun `parseMeta 提取 core_xml 的标题与作者`() {
        val docx = tempDocx(TestDocx.minimal())
        val parser = newParserFor(tempDir())
        val meta = ZipFile(docx).use { parser.readCoreProperties(it) }
        assertEquals(TestDocx.META_TITLE, meta.title)
        assertEquals(listOf(TestDocx.META_CREATOR), meta.creators)
        assertEquals("简介文本。", meta.description)
        assertEquals("zh-CN", meta.language)
        assertEquals(listOf("科幻"), meta.subjects)
    }

    @Test
    fun `无 core_xml 时元数据留空`() {
        val docx = tempDocx(TestDocx.minimal().apply { remove("docProps/core.xml") })
        val parser = newParserFor(tempDir())
        val meta = ZipFile(docx).use { parser.readCoreProperties(it) }
        assertEquals(null, meta.title)
        assertTrue(meta.creators.isEmpty())
    }

    @Test
    fun `内嵌图片经 mammoth 转换器收集并随压平抽取到 images 目录`() = runBlocking {
        val docx = tempDocx(TestDocx.withImage())
        val parser = newParserFor(tempDir())

        val flattened = parser.ensureFlattenedFile(docx)
        val content = parser.openContentFile(docx)
        try {
            val text = content.read(0L until content.charCount)
            assertTrue(text.endsWith(HtmlTextFlattener.IMAGE_PLACEHOLDER.toString()))

            val images = flattened.spans.filter { it.type == TextSpanType.IMAGE }
            assertEquals(1, images.size)
            assertEquals("word/media/image-0.png", images[0].payload)
            assertEquals(100, images[0].width)
            assertEquals(50, images[0].height)

            // 字节已落盘（zip 路径打平为文件名），imageFile 能按路径取回
            val imageFile = parser.imageFileOf(docx, "word/media/image-0.png")
            assertNotNull(imageFile)
            assertTrue(TestDocx.PNG_BYTES.contentEquals(imageFile!!.readBytes()))
        } finally {
            (content as? java.io.Closeable)?.close()
        }
    }

    @Test
    fun `封面兜底取 word_media 第一张图`() {
        val docx = tempDocx(TestDocx.withImage())
        val parser = newParserFor(tempDir())
        val cover = parser.extractCoverFile(docx)
        assertNotNull(cover)
        assertEquals("png", cover!!.extension)
        assertTrue(TestDocx.PNG_BYTES.contentEquals(cover.bytes))
    }

    @Test
    fun `无图片时封面为 null`() {
        val docx = tempDocx(TestDocx.minimal())
        val parser = newParserFor(tempDir())
        assertEquals(null, parser.extractCoverFile(docx))
    }

    @Test
    fun `DOCTYPE 实体注入被拒绝`() {
        assertThrows(DocxSecurity.UnsafeXmlException::class.java) {
            tempDocx(TestDocx.withDoctype()).inputStream().use { DocxSecurity.validate(it) }
        }
        assertThrows(DocxSecurity.UnsafeXmlException::class.java) {
            tempDocx(TestDocx.withUtf16Doctype()).inputStream().use { DocxSecurity.validate(it) }
        }
    }

    @Test
    fun `DOCTYPE 书压平时抛出安全异常`() {
        val docx = tempDocx(TestDocx.withDoctype())
        val parser = newParserFor(tempDir())
        assertThrows(DocxSecurity.UnsafeXmlException::class.java) {
            parser.ensureFlattenedFile(docx)
        }
    }

    @Test
    fun `条目数与解压体积超限被拒绝`() {
        val manyEntries = (1..8).associate { "word/f$it.xml" to "x".toByteArray() }
        assertThrows(DocxSecurity.TooLargeException::class.java) {
            tempDocx(manyEntries).inputStream().use {
                DocxSecurity.validate(it, maxEntries = 4, maxUncompressedBytes = Long.MAX_VALUE)
            }
        }
        val bigEntry = mapOf("word/big.xml" to ByteArray(4096))
        assertThrows(DocxSecurity.TooLargeException::class.java) {
            tempDocx(bigEntry).inputStream().use {
                DocxSecurity.validate(it, maxEntries = 4096, maxUncompressedBytes = 1024)
            }
        }
        // 合法书正常通过
        tempDocx(TestDocx.minimal().mapValues { it.value.toByteArray(Charsets.UTF_8) })
            .inputStream().use { DocxSecurity.validate(it) }
    }
}

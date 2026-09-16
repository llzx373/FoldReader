package com.llzx373.foldreader.core.format.epub

import com.llzx373.foldreader.core.format.BookContent
import com.llzx373.foldreader.core.format.txt.TxtBookContent
import com.llzx373.foldreader.core.format.txt.TxtIndexer
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser
import org.xmlpull.v1.XmlPullParser

class EpubBookParserTest {

    private val newParser: () -> XmlPullParser = { KXmlParser() }

    private fun newParserFor(dir: File): EpubBookParser = EpubBookParser(
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

    private fun tempEpub(entries: LinkedHashMap<String, String>): File {
        val file = File.createTempFile("epub-parser", ".epub")
        file.deleteOnExit()
        TestEpubs.write(file, entries)
        return file
    }

    private fun tempDir(): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "epub-test-${System.nanoTime()}")
        dir.mkdirs()
        return dir
    }

    @Test
    fun `端到端 压平后按真实 TOC 分章且边界文本正确`() = runBlocking {
        val epub = tempEpub(TestEpubs.epub2())
        val parser = newParserFor(tempDir())

        val flattened = parser.ensureFlattenedFile(epub)
        assertTrue(flattened.fresh)
        // TOC 中「第一章 第一节」与「第一章」同文件（fragment 丢弃）→ 去重后两章
        assertEquals(listOf("第一章", "第二章"), flattened.chapters.map { it.title })

        val content = parser.openContentFile(epub)
        try {
            assertEquals(TestEpubs.FULL_TEXT.length.toLong(), content.charCount)
            assertEquals(TestEpubs.FULL_TEXT, content.read(0L until content.charCount))

            // 章节边界定义：charStart = 本章文本起点（章间空行归入上一章尾部）
            val ch1 = flattened.chapters[0]
            val ch2 = flattened.chapters[1]
            assertEquals(0L, ch1.charStart)
            assertEquals(ch1.charEnd, ch2.charStart)
            assertEquals(content.charCount, ch2.charEnd)
            assertEquals(TestEpubs.CH1_TEXT + "\n\n", content.read(ch1.charStart until ch1.charEnd))
            assertEquals(TestEpubs.CH2_TEXT, content.read(ch2.charStart until ch2.charEnd))

            // parseChapters 与压平时记录的边界一致（经 sidecar 缓存重放）
            assertEquals(flattened.chapters, parser.parseChaptersFile(epub))
        } finally {
            (content as? java.io.Closeable)?.close()
        }
    }

    @Test
    fun `压平缓存命中时不重建`() {
        val epub = tempEpub(TestEpubs.epub2())
        val parser = newParserFor(tempDir())
        val first = parser.ensureFlattenedFile(epub)
        val second = parser.ensureFlattenedFile(epub)
        assertTrue(first.fresh)
        assertFalse(second.fresh)
        assertEquals(first.file, second.file)
        assertEquals(first.chapters, second.chapters)
    }

    @Test
    fun `EPUB3 NAV 目录同样产出正确章节边界`() = runBlocking {
        val epub = tempEpub(TestEpubs.epub3())
        val parser = newParserFor(tempDir())
        val flattened = parser.ensureFlattenedFile(epub)
        assertEquals(listOf("甲章", "乙章"), flattened.chapters.map { it.title })
        val content = parser.openContentFile(epub)
        try {
            assertEquals(TestEpubs.FULL_TEXT, content.read(0L until content.charCount))
            val ch2 = flattened.chapters[1]
            assertEquals(TestEpubs.CH2_TEXT, content.read(ch2.charStart until ch2.charEnd))
        } finally {
            (content as? java.io.Closeable)?.close()
        }
    }

    @Test
    fun `无 TOC 时退化为按 spine 项分章`() {
        val epub = tempEpub(TestEpubs.noToc())
        val parser = newParserFor(tempDir())
        val chapters = parser.ensureFlattenedFile(epub).chapters
        assertEquals(listOf("ch1", "ch2"), chapters.map { it.title })
        assertEquals(0L, chapters[0].charStart)
        assertEquals(chapters[0].charEnd, chapters[1].charStart)
        assertEquals(TestEpubs.FULL_TEXT.length.toLong(), chapters[1].charEnd)
    }
}

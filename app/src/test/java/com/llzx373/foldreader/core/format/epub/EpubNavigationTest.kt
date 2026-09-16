package com.llzx373.foldreader.core.format.epub

import com.llzx373.foldreader.core.format.BookContent
import com.llzx373.foldreader.core.format.PageLabel
import com.llzx373.foldreader.core.format.pageLabelAt
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser
import org.xmlpull.v1.XmlPullParser

/**
 * 阶段 B 导航增强：TOC fragment 锚点、landmarks/guide 正文起点、page-list 纸书页码、
 * linear="no" 跳过、sidecar 往返与旧缓存升级。
 *
 * ANCHORED_CH1 压平文本 "甲章标题\n\n甲章正文续文。\n\n嵌套块。"（20 字符）的关键偏移：
 * #c1=0（块级）、#mid=10（行内，「续」）、#blk=15（嵌套块，「嵌」）；ch2 文件起点=21（+2 分隔换行）。
 */
class EpubNavigationTest {

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
        val file = File.createTempFile("epub-nav", ".epub")
        file.deleteOnExit()
        TestEpubs.write(file, entries)
        return file
    }

    private fun tempDir(): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "epub-nav-${System.nanoTime()}")
        dir.mkdirs()
        return dir
    }

    private fun parseStructure(epub: File): EpubStructure =
        ZipFile(epub).use { EpubStructure.parse(it, newParser) }

    @Test
    fun `元素锚点记录为压平偏移且处文本正确`() = runBlocking {
        val epub = tempEpub(TestEpubs.anchored())
        val parser = newParserFor(tempDir())
        val flattened = parser.ensureFlattenedFile(epub)

        assertEquals(0L, flattened.anchors.getValue("OEBPS/text/ch1.xhtml"))
        assertEquals(0L, flattened.anchors.getValue("OEBPS/text/ch1.xhtml#c1"))
        assertEquals(10L, flattened.anchors.getValue("OEBPS/text/ch1.xhtml#mid"))
        assertEquals(15L, flattened.anchors.getValue("OEBPS/text/ch1.xhtml#blk"))
        assertEquals(21L, flattened.anchors.getValue("OEBPS/text/ch2.xhtml"))

        val content = parser.openContentFile(epub)
        try {
            assertEquals(TestEpubs.ANCHORED_CH1_TEXT + "\n\n" + TestEpubs.CH2_TEXT,
                content.read(0L until content.charCount))
            assertEquals("续文。", content.read(10L until 13L))
            assertEquals("嵌套块。", content.read(15L until 19L))
            assertEquals(TestEpubs.CH2_TEXT, content.read(21L until content.charCount))
        } finally {
            (content as? java.io.Closeable)?.close()
        }
    }

    @Test
    fun `TOC fragment 命中锚点分章 缺失锚回退文件起点并被同偏移去重`() = runBlocking {
        val epub = tempEpub(TestEpubs.anchored())
        val parser = newParserFor(tempDir())
        val flattened = parser.ensureFlattenedFile(epub)

        // 「缺失锚」(#missing 未命中 → 回退 ch1 起点 0) 与「第一章」同偏移，保留先出现标题
        assertEquals(listOf("第一章", "续节", "第二章"), flattened.chapters.map { it.title })
        assertEquals(listOf(0L, 10L, 21L), flattened.chapters.map { it.charStart })

        val content = parser.openContentFile(epub)
        try {
            val (c1, c2, c3) = flattened.chapters
            assertEquals("甲章标题\n\n甲章正文", content.read(c1.charStart until c1.charEnd))
            assertEquals("续文。\n\n嵌套块。\n\n", content.read(c2.charStart until c2.charEnd))
            assertEquals(TestEpubs.CH2_TEXT, content.read(c3.charStart until c3.charEnd))
        } finally {
            (content as? java.io.Closeable)?.close()
        }
    }

    @Test
    fun `buildChapters 乱序锚点排序且单调 越界条目丢弃`() {
        val parser = newParserFor(tempDir())
        val structure = EpubStructure(
            meta = EpubMeta(title = "t"),
            spine = emptyList(),
            toc = listOf(
                EpubStructure.TocEntry("后", "f", fragment = "b"),
                EpubStructure.TocEntry("先", "f", fragment = "a"),
                EpubStructure.TocEntry("越界", "f", fragment = "far"),
                EpubStructure.TocEntry("未知文件", "nowhere"),
            ),
            coverFile = null,
        )
        val chapters = parser.buildChapters(
            structure,
            itemStarts = mapOf("f" to 0L),
            anchors = mapOf("f#a" to 5L, "f#b" to 2L, "f#far" to 99L),
            totalChars = 10L,
        )
        assertEquals(listOf("后", "先"), chapters.map { it.title })
        assertEquals(listOf(2L, 5L), chapters.map { it.charStart })
        assertEquals(listOf(5L, 10L), chapters.map { it.charEnd })
        assertTrue(chapters.zipWithNext().all { (a, b) -> a.charEnd == b.charStart && a.charEnd > a.charStart })
    }

    @Test
    fun `anchors 与 pages sidecar 缓存往返一致 缺失 sidecar 触发重压平升级`() {
        val epub = tempEpub(TestEpubs.pageList())
        val dir = tempDir()
        val parser = newParserFor(dir)

        val first = parser.ensureFlattenedFile(epub)
        assertTrue(first.fresh)
        val second = parser.ensureFlattenedFile(epub)
        assertFalse(second.fresh)
        assertEquals(first.anchors, second.anchors)
        assertEquals(first.pageLabels, second.pageLabels)
        assertTrue(second.pageLabels.isNotEmpty())

        // 旧版本缓存没有 .anchors：缓存判定失败 → 重压平
        val anchorsFiles = dir.listFiles { f -> f.name.endsWith(".anchors") }!!.filter { it.exists() }
        assertTrue(anchorsFiles.isNotEmpty())
        anchorsFiles.forEach { it.delete() }
        val third = parser.ensureFlattenedFile(epub)
        assertTrue(third.fresh)
        assertEquals(first.anchors, third.anchors)
        assertEquals(first.pageLabels, third.pageLabels)
    }

    @Test
    fun `压平版本不匹配的旧缓存自动重压平升级 v2 命中不重压`() {
        val epub = tempEpub(TestEpubs.epub2())
        val dir = tempDir()
        val parser = newParserFor(dir)

        assertTrue(parser.ensureFlattenedFile(epub).fresh)
        assertFalse(parser.ensureFlattenedFile(epub).fresh)

        // 模拟 v1 旧缓存：版本号低于当前 FLATTEN_VERSION → 重压平
        val versionFile = dir.listFiles { f -> f.name.endsWith(".version") }!!.single()
        versionFile.writeText("1")
        assertTrue(parser.ensureFlattenedFile(epub).fresh)
        // 重压后已写回当前版本 → 再次命中缓存
        assertFalse(parser.ensureFlattenedFile(epub).fresh)
    }

    @Test
    fun `landmarks bodymatter 指向锚点成为正文起点`() {
        val epub = tempEpub(TestEpubs.landmarks())
        val parser = newParserFor(tempDir())
        assertEquals(10L, parser.preferredStartOffsetFile(epub))
    }

    @Test
    fun `EPUB2 guide type=text 兜底正文起点`() {
        val epub = tempEpub(TestEpubs.guideText())
        val parser = newParserFor(tempDir())
        // guide 指向 ch2 文件级（无 fragment）→ ch2 压平起点
        assertEquals(TestEpubs.CH1_TEXT.length + 2L, parser.preferredStartOffsetFile(epub))
    }

    @Test
    fun `无 landmarks 与 guide 起点时 preferredStart 为 null`() {
        val parser = newParserFor(tempDir())
        assertNull(parser.preferredStartOffsetFile(tempEpub(TestEpubs.noToc())))
        assertNull(parser.preferredStartOffsetFile(tempEpub(TestEpubs.epub2())))
    }

    @Test
    fun `EPUB3 page-list 生成纸书页码`() {
        val epub = tempEpub(TestEpubs.pageList())
        val parser = newParserFor(tempDir())
        assertEquals(
            listOf(PageLabel("1", 0L), PageLabel("2", 10L), PageLabel("3", 21L)),
            parser.pageLabelsFile(epub),
        )
    }

    @Test
    fun `EPUB2 NCX pageList 生成纸书页码`() {
        val epub = tempEpub(TestEpubs.ncxPageList())
        val parser = newParserFor(tempDir())
        assertEquals(
            listOf(PageLabel("10", 10L), PageLabel("11", 21L)),
            parser.pageLabelsFile(epub),
        )
    }

    @Test
    fun `无 page-list 时 pageLabels 为 null`() {
        val parser = newParserFor(tempDir())
        assertNull(parser.pageLabelsFile(tempEpub(TestEpubs.epub2())))
    }

    @Test
    fun `pageLabelAt 边界 小于首个为 null 等于边界取该页 超过末尾取最后页`() {
        val labels = listOf(PageLabel("1", 10L), PageLabel("2", 20L), PageLabel("3", 30L))
        assertNull(pageLabelAt(emptyList(), 50L))
        assertNull(pageLabelAt(labels, 9L))
        assertEquals("1", pageLabelAt(labels, 10L)?.label)
        assertEquals("1", pageLabelAt(labels, 19L)?.label)
        assertEquals("2", pageLabelAt(labels, 20L)?.label)
        assertEquals("3", pageLabelAt(labels, 999L)?.label)
    }

    @Test
    fun `linear=no 的 spine 项不压平且指向它的 TOC 条目被丢弃`() = runBlocking {
        val epub = tempEpub(TestEpubs.linearNo())
        val parser = newParserFor(tempDir())
        val flattened = parser.ensureFlattenedFile(epub)

        assertEquals(listOf("第一章", "第二章"), flattened.chapters.map { it.title })
        val content = parser.openContentFile(epub)
        try {
            val text = content.read(0L until content.charCount)
            assertEquals(TestEpubs.FULL_TEXT, text)
            assertFalse(text.contains("注释"))
        } finally {
            (content as? java.io.Closeable)?.close()
        }
    }

    @Test
    fun `全部 spine 项 linear=no 时回退全部压平`() = runBlocking {
        val epub = tempEpub(TestEpubs.allLinearNo())
        val parser = newParserFor(tempDir())
        val content = parser.openContentFile(epub)
        try {
            assertEquals(TestEpubs.FULL_TEXT, content.read(0L until content.charCount))
        } finally {
            (content as? java.io.Closeable)?.close()
        }
    }

    @Test
    fun `结构解析 landmarks pageList guide linear 字段`() {
        val landmarks = parseStructure(tempEpub(TestEpubs.landmarks()))
        assertEquals(
            listOf(
                EpubStructure.NavLink("cover", "OEBPS/cover.xhtml", null),
                EpubStructure.NavLink("bodymatter", "OEBPS/text/ch1.xhtml", "mid"),
            ),
            landmarks.landmarks,
        )
        assertEquals(
            EpubStructure.TocEntry("bodymatter", "OEBPS/text/ch1.xhtml", "mid"),
            landmarks.preferredStartTarget(),
        )

        val pages = parseStructure(tempEpub(TestEpubs.pageList()))
        assertEquals(
            listOf(
                EpubStructure.TocEntry("1", "OEBPS/text/ch1.xhtml", null),
                EpubStructure.TocEntry("2", "OEBPS/text/ch1.xhtml", "mid"),
                EpubStructure.TocEntry("3", "OEBPS/text/ch2.xhtml", null),
            ),
            pages.pageList,
        )

        val guide = parseStructure(tempEpub(TestEpubs.guideText()))
        assertEquals(
            EpubStructure.GuideRef("text", "正文", "OEBPS/text/ch2.xhtml", null),
            guide.guide.firstOrNull { it.type == "text" },
        )
        assertEquals("OEBPS/text/ch2.xhtml", guide.preferredStartTarget()?.targetFile)

        val linear = parseStructure(tempEpub(TestEpubs.linearNo()))
        assertEquals(listOf(true, false, true), linear.spine.map { it.linear })
    }
}

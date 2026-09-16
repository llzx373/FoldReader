package com.llzx373.foldreader.core.format.epub

import com.llzx373.foldreader.core.format.BookContent
import com.llzx373.foldreader.core.format.TextSpanType
import com.llzx373.foldreader.core.format.txt.TxtBookContent
import com.llzx373.foldreader.core.format.txt.TxtIndexer
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser

/**
 * 压平规范 v3 端到端：样式/链接 span 经全书锚点表解析、spans sidecar 往返、图片抽取落盘。
 * TestEpubs.styled() 压平文本布局（详见 fixtures 注释）：
 * ch1 = "前文内部链外部链断链后文\n\n粗体斜体叠加\n\n￼"，ch2 起点 25，全长 36。
 */
class EpubSpansTest {

    private fun newParserFor(dir: File): EpubBookParser = EpubBookParser(
        convertedDir = dir,
        openFlattenedContent = { file -> openTxt(file) },
        openChannel = { error("测试只走 File 内部方法") },
        displayNameOf = { null },
        newParser = { KXmlParser() },
        // JVM 单测无 BitmapFactory：注入假尺寸探测（PNG_BYTES 本身不可解码）
        imageSizer = { intArrayOf(8, 8) },
    )

    private fun openTxt(file: File): BookContent {
        val channel = RandomAccessFile(file, "r").channel
        val index = TxtIndexer.index(
            FileChannel.open(file.toPath(), StandardOpenOption.READ),
            Charsets.UTF_8,
        )
        return TxtBookContent(channel, Charsets.UTF_8, index.offsetIndex)
    }

    private fun tempStyledEpub(): File {
        val file = File.createTempFile("epub-spans", ".epub")
        file.deleteOnExit()
        TestEpubs.writeRaw(file, TestEpubs.styled())
        return file
    }

    private fun tempDir(): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "epub-spans-${System.nanoTime()}")
        dir.mkdirs()
        return dir
    }

    @Test
    fun `样式与链接 span 端到端 链接经锚点表解析`() = runBlocking {
        val epub = tempStyledEpub()
        val parser = newParserFor(tempDir())
        val flattened = parser.ensureFlattenedFile(epub)
        assertTrue(flattened.fresh)

        val content = parser.openContentFile(epub)
        try {
            assertEquals(
                "前文内部链外部链断链后文\n\n粗体斜体叠加\n\n￼\n\n目标节\n\n正文二。回退",
                content.read(0L until content.charCount),
            )
        } finally {
            (content as? java.io.Closeable)?.close()
        }

        val spans = flattened.spans
        fun spanAt(type: TextSpanType, start: Long) =
            spans.singleOrNull { it.type == type && it.start == start }

        // 内部链接：锚点命中 → "#目标偏移"；fragment 未命中 → 回退文件级锚点；断链丢弃
        assertEquals("#25", spanAt(TextSpanType.LINK, 2L)?.payload)
        assertEquals(5L, spanAt(TextSpanType.LINK, 2L)?.end)
        assertEquals("https://example.com", spanAt(TextSpanType.LINK, 5L)?.payload)
        assertNull(spanAt(TextSpanType.LINK, 8L))
        assertEquals("#0", spanAt(TextSpanType.LINK, 34L)?.payload)

        // 样式：b/i 相邻不混、嵌套叠加各自成 span
        assertEquals(16L, spanAt(TextSpanType.BOLD, 14L)?.end)
        assertEquals(20L, spanAt(TextSpanType.BOLD, 18L)?.end)
        val italic = spanAt(TextSpanType.ITALIC, 16L)
        assertEquals(20L, italic?.end)

        // 图片：IMAGE span 覆盖占位符，带路径/alt/尺寸
        val image = spanAt(TextSpanType.IMAGE, 22L)
        assertEquals(23L, image?.end)
        assertEquals("OEBPS/images/pic.png", image?.payload)
        assertEquals("插图", image?.alt)
        assertEquals(8, image?.width)
        assertEquals(8, image?.height)
    }

    @Test
    fun `spans sidecar 往返一致 缓存命中不重压`() {
        val epub = tempStyledEpub()
        val parser = newParserFor(tempDir())
        val first = parser.ensureFlattenedFile(epub)
        val second = parser.ensureFlattenedFile(epub)
        assertTrue(first.fresh)
        assertFalse(second.fresh)
        assertEquals(first.spans, second.spans)
        assertTrue(second.spans.isNotEmpty())
    }

    @Test
    fun `图片字节抽取到 images 目录并可按 zip 路径取回`() {
        val epub = tempStyledEpub()
        val parser = newParserFor(tempDir())
        parser.ensureFlattenedFile(epub)
        val file = parser.imageFileOf(epub, "OEBPS/images/pic.png")
        assertTrue(file != null && file.isFile)
        assertTrue(file!!.readBytes().contentEquals(TestEpubs.PNG_BYTES))
        assertNull(parser.imageFileOf(epub, "OEBPS/images/missing.png"))
    }

    @Test
    fun `无样式的书 spans 为空`() {
        val epub = File.createTempFile("epub-plain", ".epub")
        epub.deleteOnExit()
        TestEpubs.write(epub, TestEpubs.epub2())
        val parser = newParserFor(tempDir())
        assertTrue(parser.ensureFlattenedFile(epub).spans.isEmpty())
        assertNull(parser.textSpansFile(epub).takeIf { it.isNotEmpty() })
    }
}

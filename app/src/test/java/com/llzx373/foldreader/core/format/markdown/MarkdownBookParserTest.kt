package com.llzx373.foldreader.core.format.markdown

import java.io.File
import java.nio.charset.Charset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownBookParserTest {

    private val parser = MarkdownBookParser(
        openChannel = { error("测试只走 File 内部方法") },
        displayNameOf = { null },
    )

    private fun tempMd(bytes: ByteArray): File {
        val file = File.createTempFile("md-parser", ".md")
        file.deleteOnExit()
        file.writeBytes(bytes)
        return file
    }

    private fun tempMd(text: String, charset: Charset = Charsets.UTF_8): File =
        tempMd(text.toByteArray(charset))

    companion object {
        /** 多级标题样本：h1/h2/h3 + 正文段落。 */
        const val HEADINGS_MD = "# 第一章\n\n正文一。\n\n## 第一节\n\n正文二。\n\n### 小节一\n\n正文三。\n"

        const val NO_HEADING_MD = "没有标题的纯文本。\n\n第二段。\n"
    }

    @Test
    fun `多级标题按级别分章且 depth 递减为 0 起`() {
        val chapters = parser.parseChaptersFile(tempMd(HEADINGS_MD))
        assertEquals(listOf("第一章", "第一节", "小节一"), chapters.map { it.title })
        assertEquals(listOf(0, 1, 2), chapters.map { it.depth })
    }

    @Test
    fun `章节锚点精确指向标题行首字符`() {
        val chapters = parser.parseChaptersFile(tempMd(HEADINGS_MD))
        assertEquals(HEADINGS_MD.indexOf("# 第一章").toLong(), chapters[0].charStart)
        assertEquals(HEADINGS_MD.indexOf("## 第一节").toLong(), chapters[1].charStart)
        assertEquals(HEADINGS_MD.indexOf("### 小节一").toLong(), chapters[2].charStart)
        // charEnd = 下一章起点；末章到文末
        assertEquals(chapters[1].charStart, chapters[0].charEnd)
        assertEquals(chapters[2].charStart, chapters[1].charEnd)
        assertEquals(HEADINGS_MD.length.toLong(), chapters[2].charEnd)
    }

    @Test
    fun `Setext 标题同样进目录且锚点在文本行首`() {
        val md = "大标题\n===\n\n正文\n\n次标题\n---\n\n结尾\n"
        val chapters = parser.parseChaptersFile(tempMd(md))
        assertEquals(listOf("大标题", "次标题"), chapters.map { it.title })
        assertEquals(md.indexOf("大标题").toLong(), chapters[0].charStart)
        assertEquals(md.indexOf("次标题").toLong(), chapters[1].charStart)
    }

    @Test
    fun `无标题退化为单个正文章覆盖全文`() {
        val chapters = parser.parseChaptersFile(tempMd(NO_HEADING_MD))
        assertEquals(1, chapters.size)
        assertEquals("正文", chapters[0].title)
        assertEquals(0L, chapters[0].charStart)
        assertEquals(NO_HEADING_MD.length.toLong(), chapters[0].charEnd)
    }

    @Test
    fun `书名取第一个标题的纯文本`() {
        assertEquals("第一章", parser.titleOfFile(tempMd(HEADINGS_MD)))
        assertNull(parser.titleOfFile(tempMd(NO_HEADING_MD)))
    }

    @Test
    fun `标题里的行内格式在书名与目录中被剥掉`() {
        val md = "# **粗体** 与 `代码` 标题\n\n正文\n"
        val file = tempMd(md)
        assertEquals("粗体 与 代码 标题", parser.titleOfFile(file))
        assertEquals("粗体 与 代码 标题", parser.parseChaptersFile(file)[0].title)
    }

    @Test
    fun `GBK 编码的章节标题与锚点正确`() {
        val md = "# 第一章\n\n正文一。\n\n# 第二章\n\n正文二。\n"
        val file = tempMd(md, charset("GBK"))
        val chapters = parser.parseChaptersFile(file)
        assertEquals(listOf("第一章", "第二章"), chapters.map { it.title })
        assertEquals(md.indexOf("# 第二章").toLong(), chapters[1].charStart)
    }

    @Test
    fun `UTF-8 BOM 不计入锚点偏移`() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val file = tempMd(bom + HEADINGS_MD.toByteArray(Charsets.UTF_8))
        val chapters = parser.parseChaptersFile(file)
        assertEquals(listOf("第一章", "第一节", "小节一"), chapters.map { it.title })
        assertEquals(0L, chapters[0].charStart)
        assertEquals(HEADINGS_MD.indexOf("## 第一节").toLong(), chapters[1].charStart)
    }

    @Test
    fun `openContent 读出全文且按范围截取`() = runBlocking {
        val content = parser.openContentFile(tempMd(HEADINGS_MD))
        assertEquals(HEADINGS_MD.length.toLong(), content.charCount)
        assertEquals(HEADINGS_MD, content.read(0L until content.charCount))
        assertEquals("正文一。", content.read(HEADINGS_MD.indexOf("正文一。").toLong()..HEADINGS_MD.indexOf("正文一。") + 3L))
        assertEquals("", content.read(10L..5L))
    }

    @Test
    fun `openContent 按调用方指定编码解码`() = runBlocking {
        val md = "# 标题\n\n正文\n"
        val content = parser.openContentFile(tempMd(md, charset("GBK")), charset("GBK"))
        assertEquals(md, content.read(0L until content.charCount))
    }

    @Test
    fun `块起始偏移升序且二分换算正确`() {
        val root = newMarkdownParser().parse(HEADINGS_MD)
        val blocks = topLevelBlocks(root)
        val starts = blockStartOffsets(blocks, HEADINGS_MD.length)
        assertEquals(blocks.size, starts.size)
        assertTrue(starts.asList().zipWithNext().all { (a, b) -> a <= b })
        // 第一个块 = h1，起点 0
        assertEquals(0L, starts[0])
        // 「正文二。」所在的段落块：偏移落在该块内
        val paragraphOffset = HEADINGS_MD.indexOf("正文二。").toLong()
        val index = blockIndexAtOffset(starts, paragraphOffset)
        val blockStart = starts[index]
        assertTrue(blockStart <= paragraphOffset)
        if (index + 1 < starts.size) assertTrue(paragraphOffset < starts[index + 1])
        // 边界：负偏移与越界偏移都钳到有效区间
        assertEquals(0, blockIndexAtOffset(starts, -1))
        assertEquals(blocks.size - 1, blockIndexAtOffset(starts, HEADINGS_MD.length.toLong() + 100))
    }

    @Test
    fun `引用块内的标题也进目录`() {
        val md = "# 正常标题\n\n> ## 引用里的标题\n\n正文\n"
        val chapters = parser.parseChaptersFile(tempMd(md))
        assertEquals(listOf("正常标题", "引用里的标题"), chapters.map { it.title })
        assertEquals(md.indexOf("## 引用里的标题").toLong(), chapters[1].charStart)
    }
}

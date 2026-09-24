package com.llzx373.foldreader.core.translate

import com.llzx373.foldreader.core.format.Chapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R12 切块器的不变量：块边界不跨段、全书无遗漏无重叠、超长单段独立成块、
 * 无章书 / 空章 / 孤儿文本都有确定口径。
 */
class TranslationUnitsTest {

    /** 造一段以 \n 结尾的段落文本（每段 [size] 字，不含换行符本身）。 */
    private fun paragraph(size: Int, seed: Char = 'a') = "$seed".repeat(size) + "\n"

    private fun readerOf(text: String): (LongRange) -> String =
        { range -> text.substring(range.first.toInt(), (range.last + 1).toInt()) }

    // ---------- splitIntoBlocks ----------

    @Test
    fun `块边界不跨段且拼接后逐字节等于原文`() {
        val text = paragraph(12) + paragraph(25) + paragraph(8) + paragraph(40) + paragraph(15)
        val blocks = splitIntoBlocks(text, baseOffset = 0, minChars = 10, maxChars = 40)

        // 闭区间连续无重叠
        assertEquals(0L, blocks.first().first)
        for (i in 1 until blocks.size) assertEquals(blocks[i - 1].last + 1, blocks[i].first)
        assertEquals(text.length - 1L, blocks.last().last)
        // 拼接 == 原文
        assertEquals(text, blocks.joinToString("") { readerOf(text)(it) })
        // 每块尾都落在段界（'\n' 之后或文末）
        for (block in blocks) {
            val endExclusive = (block.last + 1).toInt()
            assertTrue(endExclusive == text.length || text[endExclusive - 1] == '\n')
        }
    }

    @Test
    fun `块长不超 max 且除尾块外不小于 min`() {
        // 100 段 × 50 字（含换行 5100 字），min 2000 / max 4000
        val text = paragraph(49).repeat(100)
        val blocks = splitIntoBlocks(text, baseOffset = 0)

        for ((i, block) in blocks.withIndex()) {
            val len = block.last - block.first + 1
            assertTrue("块 $i 超 max：$len", len <= MAX_BLOCK_CHARS)
            if (i < blocks.size - 1) assertTrue("块 $i 不足 min：$len", len >= MIN_BLOCK_CHARS)
        }
        assertEquals(text, blocks.joinToString("") { readerOf(text)(it) })
    }

    @Test
    fun `单段超 maxChars 时该段独立成块`() {
        val text = paragraph(10) + paragraph(100) + paragraph(10)
        val blocks = splitIntoBlocks(text, baseOffset = 0, minChars = 20, maxChars = 40)

        assertEquals(3, blocks.size)
        assertEquals(11..111L, blocks[1]) // 超长段独立，允许超 max
        assertEquals(text, blocks.joinToString("") { readerOf(text)(it) })
    }

    @Test
    fun `空文本返回空列表`() {
        assertEquals(emptyList<LongRange>(), splitIntoBlocks("", baseOffset = 0))
    }

    @Test
    fun `baseOffset 平移到原文流偏移且兼容 CRLF`() {
        val text = "段落一\r\n段落二\r\n" // 每段 5 字符（3 字 + \r\n），共 10
        val blocks = splitIntoBlocks(text, baseOffset = 100, minChars = 1, maxChars = 4)
        // maxChars=4 时每段独立成块
        assertEquals(2, blocks.size)
        assertEquals(100L, blocks[0].first)
        assertEquals(105L, blocks[1].first)
        assertEquals(109L, blocks[1].last)
    }

    // ---------- computeUnits ----------

    @Test
    fun `无章书全书按块且「第 N 节」连续编号`() {
        val text = paragraph(499).repeat(30) // 15000 字
        val units = computeUnits(emptyList(), text.length.toLong(), readerOf(text))

        assertTrue(units.size >= 4)
        assertTrue(units.all { it.kind == UnitKind.BLOCK })
        units.forEachIndexed { i, unit -> assertEquals("第 ${i + 1} 节", unit.title) }
        assertFullCoverage(units, text.length.toLong())
    }

    @Test
    fun `短章为 CHAPTER 单位且用章标题`() {
        val text = paragraph(99).repeat(10) // 1000 字
        val chapters = listOf(
            Chapter(title = "第一章", charStart = 0, charEnd = 500),
            Chapter(title = "第二章", charStart = 500, charEnd = 1000),
        )
        val units = computeUnits(chapters, text.length.toLong(), readerOf(text))

        assertEquals(2, units.size)
        assertTrue(units.all { it.kind == UnitKind.CHAPTER })
        assertEquals("第一章", units[0].title)
        assertEquals(0L, units[0].charStart)
        assertEquals(500L, units[0].charEnd)
        assertFullCoverage(units, text.length.toLong())
    }

    @Test
    fun `超长章按段落边界切块且标题为「章名 · 节 k」`() {
        val text = paragraph(499).repeat(20) // 10000 字，单章
        val chapters = listOf(Chapter(title = "正文", charStart = 0, charEnd = 10000))
        val units = computeUnits(chapters, text.length.toLong(), readerOf(text))

        assertTrue(units.size >= 3)
        assertTrue(units.all { it.kind == UnitKind.BLOCK })
        units.forEachIndexed { i, unit -> assertEquals("正文 · 节 ${i + 1}", unit.title) }
        assertFullCoverage(units, text.length.toLong())
    }

    @Test
    fun `章间与首尾孤儿文本纳入切块且全书无遗漏`() {
        val text = paragraph(99).repeat(12) // 1200 字
        val chapters = listOf(
            Chapter(title = "第一章", charStart = 200, charEnd = 600),
            Chapter(title = "第二章", charStart = 800, charEnd = 1000),
        )
        val units = computeUnits(chapters, text.length.toLong(), readerOf(text))

        // 首部孤儿 [0,200)、第一章、章间孤儿 [600,800)、第二章、尾部孤儿 [1000,1200)
        assertEquals(5, units.size)
        assertEquals(UnitKind.BLOCK, units[0].kind)
        assertEquals("第 1 节", units[0].title)
        assertEquals(UnitKind.CHAPTER, units[1].kind)
        assertEquals(UnitKind.BLOCK, units[2].kind)
        assertEquals("第 2 节", units[2].title)
        assertEquals(UnitKind.CHAPTER, units[3].kind)
        assertEquals(UnitKind.BLOCK, units[4].kind)
        assertEquals("第 3 节", units[4].title)
        assertFullCoverage(units, text.length.toLong())
    }

    @Test
    fun `空章与越界章不参与切块`() {
        val text = paragraph(99).repeat(5) // 500 字
        val chapters = listOf(
            Chapter(title = "空章", charStart = 100, charEnd = 100),
            Chapter(title = "页式章", charStart = 0, charEnd = 0), // PDF 页式目录：无字符坐标
            Chapter(title = "真章", charStart = 100, charEnd = 400),
        )
        val units = computeUnits(chapters, text.length.toLong(), readerOf(text))

        assertEquals(3, units.size) // 首部孤儿 + 真章 + 尾部孤儿
        assertEquals("真章", units[1].title)
        assertFullCoverage(units, text.length.toLong())
    }

    @Test
    fun `空书返回空单位列表`() {
        assertEquals(emptyList<TranslationUnit>(), computeUnits(emptyList(), 0, readerOf("")))
    }

    // ---------- splitIntoParagraphs ----------

    @Test
    fun `段落切分按换行且过滤空白段`() {
        val text = "  第一段  \n\n\n第二段\r\n   \n第三段"
        assertEquals(listOf("第一段", "第二段", "第三段"), splitIntoParagraphs(text))
        assertEquals(emptyList<String>(), splitIntoParagraphs("  \n \n"))
    }

    private fun assertFullCoverage(units: List<TranslationUnit>, charCount: Long) {
        assertEquals(0L, units.first().charStart)
        assertEquals(charCount, units.last().charEnd)
        for (i in 1 until units.size) {
            assertEquals("单位 $i 与前单位有缝隙或重叠", units[i - 1].charEnd, units[i].charStart)
            assertEquals(i, units[i].index)
        }
    }
}

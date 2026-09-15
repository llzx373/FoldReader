package com.llzx373.foldreader.core.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锚点稳定性核心：行 × 标注区间求交 + 触摸命中。
 * 固定字宽 10px，行高 20px，无段距/缩进/边距，便于手工演算。
 */
class PageGeometryTest {

    private val measure: (String) -> Float = { it.length * 10f }

    // 三行：L0 [100,110) 10 字（非段尾→两端对齐），L1 [110,120) 10 字（段尾），L2 [120,131) 段尾含换行 text 10 字
    private val page = Page(
        charStart = 100,
        charEnd = 131,
        lines = listOf(
            PageLine(100, 110, "甲乙丙丁戊己庚辛壬癸", isParagraphStart = true, isParagraphEnd = false),
            PageLine(110, 120, "子丑寅卯辰巳午未申酉", isParagraphStart = false, isParagraphEnd = true),
            PageLine(120, 131, "天地玄黄宇宙洪荒日月", isParagraphStart = true, isParagraphEnd = true),
        ),
        paddingLeft = 0f,
        paddingRight = 0f,
    )

    private fun boxes(
        textWidthPx: Float = 100f,
        justify: Boolean = false,
        topPadPx: Float = 0f,
    ) = buildLineBoxes(
        page = page,
        lineHeightPx = 20f,
        paragraphSpacingPx = 0f,
        indentPx = 0f,
        topPadPx = topPadPx,
        leftPadPx = 0f,
        textWidthPx = textWidthPx,
        justify = justify,
        measure = measure,
    )

    @Test
    fun `segments within single line`() {
        // 区间 [103, 106) → L0 第 3..5 字：x 30..60
        val segs = segmentsForRange(boxes(), 103, 106)
        assertEquals(1, segs.size)
        assertEquals(0, segs[0].lineIndex)
        assertEquals(30f, segs[0].xStart, 0.001f)
        assertEquals(60f, segs[0].xEnd, 0.001f)
    }

    @Test
    fun `segments across lines and page edges`() {
        // 跨行 [108, 122)：L0 末两字 x80..100；L1 整行 x0..100；L2 前两字 x0..20
        val segs = segmentsForRange(boxes(), 108, 122)
        assertEquals(3, segs.size)
        assertEquals(80f, segs[0].xStart, 0.001f)
        assertEquals(100f, segs[0].xEnd, 0.001f)
        assertEquals(0f, segs[1].xStart, 0.001f)
        assertEquals(100f, segs[1].xEnd, 0.001f)
        assertEquals(0f, segs[2].xStart, 0.001f)
        assertEquals(20f, segs[2].xEnd, 0.001f)
    }

    @Test
    fun `range clamped to page bounds and newline excluded`() {
        // 区间超出页首：L0 从行首开始
        val segs = segmentsForRange(boxes(), 50, 102)
        assertEquals(1, segs.size)
        assertEquals(0f, segs[0].xStart, 0.001f)
        assertEquals(20f, segs[0].xEnd, 0.001f)
        // L2 charEnd=131 含换行符，区间到 131 也只划到第 10 字（x 100）
        val tail = segmentsForRange(boxes(), 128, 131)
        assertEquals(1, tail.size)
        assertEquals(80f, tail[0].xStart, 0.001f)
        assertEquals(100f, tail[0].xEnd, 0.001f)
        // 完全落在换行符上：无片段
        assertTrue(segmentsForRange(boxes(), 130, 131).isEmpty())
        // 空区间/反向区间：无片段
        assertTrue(segmentsForRange(boxes(), 105, 105).isEmpty())
    }

    @Test
    fun `justify gap widens segment`() {
        // L0 非段尾 10 字 natural=100，文本宽 110 → gap=(110-100)/9 ≈ 1.111
        val segs = segmentsForRange(boxes(textWidthPx = 110f, justify = true), 100, 101)
        assertEquals(1, segs.size)
        assertEquals(0f, segs[0].xStart, 0.001f)
        assertEquals(10f + 10f / 9f, segs[0].xEnd, 0.001f)
        // 段尾行不两端对齐：L1 单字宽仍为 10
        val noJust = segmentsForRange(boxes(textWidthPx = 110f, justify = true), 110, 111)
        assertEquals(10f, noJust[0].xEnd - noJust[0].xStart, 0.001f)
    }

    @Test
    fun `caret hit test`() {
        val bs = boxes()
        // L0 第 3 字 x∈[30,40)，中点 35：x=34 → caret 103，x=36 → caret 104
        assertEquals(103L, caretAt(bs, 34f, 5f))
        assertEquals(104L, caretAt(bs, 36f, 5f))
        // y 选行：第二行 y∈[20,40)
        assertEquals(110L, caretAt(bs, 0f, 25f))
        // x 超出行尾 → 行尾光标位（textLength=10 → 110）
        assertEquals(110L, caretAt(bs, 500f, 5f))
        // x 在行首前 → 行首
        assertEquals(100L, caretAt(bs, -50f, 5f))
        // y 超底 → 最后一行行尾（光标位 130 = charStart+textLength，不含换行）
        assertEquals(130L, caretAt(bs, 500f, 999f))
        // 空页
        assertNull(caretAt(emptyList(), 0f, 0f))
        // 段间距：topPad 体现整体下移
        val padded = boxes(topPadPx = 8f)
        assertEquals(103L, caretAt(padded, 34f, 13f))
    }

    @Test
    fun `paragraph indent shifts first line only`() {
        val bs = buildLineBoxes(
            page = page,
            lineHeightPx = 20f,
            paragraphSpacingPx = 6f,
            indentPx = 20f,
            topPadPx = 0f,
            leftPadPx = 4f,
            textWidthPx = 100f,
            justify = false,
            measure = measure,
        )
        // L0 段首：x0 = 4 + 20 = 24；L2 段首且前面有 L1 → 段距 6 生效
        assertEquals(24f, bs[0].x0, 0.001f)
        assertEquals(4f, bs[1].x0, 0.001f)
        assertEquals(24f, bs[2].x0, 0.001f)
        assertEquals(46f, bs[2].yTop, 0.001f) // 20 + 20 + 6
    }

    @Test
    fun `paragraph starting with whitespace is not indented again`() {
        val indentedPage = Page(
            charStart = 0,
            charEnd = 21,
            lines = listOf(
                PageLine(0, 11, "　甲乙丙丁戊己庚辛壬", isParagraphStart = true, isParagraphEnd = false),
                PageLine(11, 21, "癸子丑寅卯辰巳午未申", isParagraphStart = false, isParagraphEnd = true),
            ),
            paddingLeft = 0f,
            paddingRight = 0f,
        )
        val bs = buildLineBoxes(
            page = indentedPage,
            lineHeightPx = 20f,
            paragraphSpacingPx = 0f,
            indentPx = 20f,
            topPadPx = 0f,
            leftPadPx = 4f,
            textWidthPx = 100f,
            justify = false,
            measure = measure,
        )
        // 段首行自带全角空格 → 不叠加缩进；indentPx=0（开关关闭）时普通段首也不缩进
        assertEquals(4f, bs[0].x0, 0.001f)
        val noIndent = buildLineBoxes(
            page = page,
            lineHeightPx = 20f,
            paragraphSpacingPx = 0f,
            indentPx = 0f,
            topPadPx = 0f,
            leftPadPx = 4f,
            textWidthPx = 100f,
            justify = false,
            measure = measure,
        )
        assertEquals(4f, noIndent[0].x0, 0.001f)
    }
}

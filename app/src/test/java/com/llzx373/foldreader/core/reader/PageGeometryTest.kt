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

    @Test
    fun `paragraph starting with unicode space is not indented again`() {
        // NBSP / EN SPACE / EM SPACE / NNBSP / MMSP 等非 ASCII 空格同样算「已有缩进」，不叠加
        val leading = listOf('\u00A0', '\u2002', '\u2003', '\u202F', '\u205F', '\u3000', ' ', '\t')
        for (c in leading) {
            val p = Page(
                charStart = 0,
                charEnd = 5,
                lines = listOf(
                    PageLine(0, 5, "${c}甲乙丙丁", isParagraphStart = true, isParagraphEnd = true),
                ),
                paddingLeft = 0f,
                paddingRight = 0f,
            )
            val bs = buildLineBoxes(
                page = p,
                lineHeightPx = 20f,
                paragraphSpacingPx = 0f,
                indentPx = 20f,
                topPadPx = 0f,
                leftPadPx = 4f,
                textWidthPx = 100f,
                justify = false,
                measure = measure,
            )
            assertEquals("U+%04X".format(c.code), 4f, bs[0].x0, 0.001f)
        }
    }

    @Test
    fun `collapse whitespace folds the paragraph prefix and keeps the standard indent`() {
        // 7 半角空格 + 2 全角空格（《战败被俘的勇者小姐还会幸福吗》96% 正文行的形态）
        val p = Page(
            charStart = 0,
            charEnd = 12,
            lines = listOf(
                PageLine(0, 12, "       \u3000\u3000甲乙丙", isParagraphStart = true, isParagraphEnd = true),
            ),
            paddingLeft = 0f,
            paddingRight = 0f,
        )
        val bs = buildLineBoxes(
            page = p,
            lineHeightPx = 20f,
            paragraphSpacingPx = 0f,
            indentPx = 20f,
            topPadPx = 0f,
            leftPadPx = 4f,
            textWidthPx = 100f,
            justify = false,
            measure = measure,
            collapseWhitespace = true,
        )
        // 前缀零宽、不占位；可见文字从标准缩进处开始，两侧口径一致
        assertEquals(24f, bs[0].x0, 0.001f)
        for (i in 0 until 9) assertEquals("index $i", 0f, bs[0].charWidths[i], 0.001f)
        assertEquals(10f, bs[0].charWidths[9], 0.001f)
        assertEquals(24f, bs[0].boundaryX(9), 0.001f)
        assertEquals(54f, bs[0].boundaryX(12), 0.001f)
        assertEquals(12, bs[0].textLength)
    }

    @Test
    fun `collapse whitespace keeps pixel behaviour when disabled`() {
        val p = Page(
            charStart = 0,
            charEnd = 12,
            lines = listOf(
                PageLine(0, 12, "       \u3000\u3000甲乙丙", isParagraphStart = true, isParagraphEnd = true),
            ),
            paddingLeft = 0f,
            paddingRight = 0f,
        )
        val bs = buildLineBoxes(
            page = p,
            lineHeightPx = 20f,
            paragraphSpacingPx = 0f,
            indentPx = 20f,
            topPadPx = 0f,
            leftPadPx = 4f,
            textWidthPx = 100f,
            justify = false,
            measure = measure,
        )
        assertNull(bs[0].collapsedMask)
        assertEquals(4f, bs[0].x0, 0.001f)
        assertEquals(10f, bs[0].charWidths[0], 0.001f)
    }

    @Test
    fun `collapse whitespace spreads justify gap over visible chars only`() {
        val p = Page(
            charStart = 0,
            charEnd = 19,
            lines = listOf(
                PageLine(0, 19, "       \u3000\u3000甲乙丙丁戊己庚辛壬癸", isParagraphStart = true, isParagraphEnd = false),
            ),
            paddingLeft = 0f,
            paddingRight = 0f,
        )
        fun gap(collapse: Boolean) = buildLineBoxes(
            page = p,
            lineHeightPx = 20f,
            paragraphSpacingPx = 0f,
            indentPx = 20f,
            topPadPx = 0f,
            leftPadPx = 0f,
            textWidthPx = 200f,
            justify = true,
            measure = measure,
            collapseWhitespace = collapse,
        )[0].gapPx
        // 归一化：自然宽 10 字×10px，余量 80 摊在 9 个字距上
        assertEquals(80f / 9f, gap(true), 0.001f)
        // 不归一化：前缀照样占宽，余量 200-190=10 摊在 18 个字距上
        assertEquals(10f / 18f, gap(false), 0.001f)
    }

    /**
     * 渲染端走 [buildLineBoxes] 的 fillCharWidths 批量路径（Paint.getTextWidths），
     * 单测走逐字 measure 路径；两条路径必须产出完全相同的几何，否则划线/光标会错位。
     * 这里用一个「字符串宽度 = 各字符宽度之和」的假字宽表模拟真实字体。
     */
    @Test
    fun `批量取字宽与逐字度量产出相同几何`() {
        val charWidth: (Char) -> Float = { ch -> if (ch in '0'..'9') 6f else 10f }
        val measureBySum: (String) -> Float = { s ->
            var sum = 0f
            for (ch in s) sum += charWidth(ch)
            sum
        }
        val fillByTable: (CharSequence, FloatArray) -> Unit = { text, out ->
            for (i in text.indices) out[i] = charWidth(text[i])
        }

        fun boxes(fill: ((CharSequence, FloatArray) -> Unit)?) = buildLineBoxes(
            page = page,
            lineHeightPx = 20f,
            paragraphSpacingPx = 6f,
            indentPx = 20f,
            topPadPx = 4f,
            leftPadPx = 4f,
            textWidthPx = 110f,
            justify = true,
            measure = measureBySum,
            fillCharWidths = fill,
        )

        val perChar = boxes(null)
        val batched = boxes(fillByTable)

        assertEquals(perChar.size, batched.size)
        perChar.forEachIndexed { index, expected ->
            val actual = batched[index]
            assertEquals(expected.x0, actual.x0, 0.001f)
            assertEquals(expected.yTop, actual.yTop, 0.001f)
            assertEquals(expected.lineHeightPx, actual.lineHeightPx, 0.001f)
            assertEquals(expected.gapPx, actual.gapPx, 0.001f)
            assertEquals(expected.charWidths.size, actual.charWidths.size)
            expected.charWidths.indices.forEach { i ->
                assertEquals(expected.charWidths[i], actual.charWidths[i], 0.001f)
            }
            // 段落末行的两端对齐字距必须为 0（不拉伸最后一行）
            assertEquals(expected.line.charStart + expected.textLength, actual.line.charStart + actual.textLength)
        }
    }

    @Test
    fun `批量取字宽时空行不调用填充`() {
        val emptyLinePage = Page(
            charStart = 0,
            charEnd = 2,
            lines = listOf(
                PageLine(0, 1, "", isParagraphStart = true, isParagraphEnd = true),
                PageLine(1, 2, "甲", isParagraphStart = true, isParagraphEnd = true),
            ),
            paddingLeft = 0f,
            paddingRight = 0f,
        )
        val calls = mutableListOf<Int>()
        val boxes = buildLineBoxes(
            page = emptyLinePage,
            lineHeightPx = 20f,
            paragraphSpacingPx = 0f,
            indentPx = 0f,
            topPadPx = 0f,
            leftPadPx = 0f,
            textWidthPx = 100f,
            justify = false,
            measure = { it.length * 10f },
            fillCharWidths = { text, _ -> calls += text.length },
        )
        assertEquals(listOf(0, 1), calls)
        assertEquals(2, boxes.size)
    }
}

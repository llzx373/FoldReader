package com.llzx373.foldreader.core.reader

import com.llzx373.foldreader.core.format.TextSpan
import com.llzx373.foldreader.core.format.TextSpanType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageSpansTest {

    private fun span(type: TextSpanType, start: Long, end: Long, payload: String? = null) =
        TextSpan(type, start, end, payload)

    @Test
    fun `行内 span 原样保留 跨界截断 行外丢弃`() {
        val spans = listOf(
            span(TextSpanType.BOLD, 5, 10),
            span(TextSpanType.ITALIC, 0, 30),
            span(TextSpanType.BOLD, 100, 200),
        )
        val sliced = sliceSpansForLine(spans, 10L, 20L)
        // BOLD[5,10) 与行 [10,20) 仅端点相接 → 空区间丢弃；ITALIC 截断为 [10,20)
        assertEquals(
            listOf(span(TextSpanType.ITALIC, 10, 20)),
            sliced,
        )
    }

    @Test
    fun `跨行 span 按行拆段`() {
        val spans = listOf(span(TextSpanType.BOLD, 5, 25))
        assertEquals(listOf(span(TextSpanType.BOLD, 5, 10)), sliceSpansForLine(spans, 0L, 10L))
        assertEquals(listOf(span(TextSpanType.BOLD, 10, 20)), sliceSpansForLine(spans, 10L, 20L))
        assertEquals(listOf(span(TextSpanType.BOLD, 20, 25)), sliceSpansForLine(spans, 20L, 30L))
    }

    @Test
    fun `IMAGE span 不参与文本行切片`() {
        val spans = listOf(span(TextSpanType.IMAGE, 0, 1, "a.png"))
        assertTrue(sliceSpansForLine(spans, 0L, 10L).isEmpty())
    }

    @Test
    fun `空 span 列表切片为空`() {
        assertTrue(sliceSpansForLine(emptyList(), 0L, 10L).isEmpty())
    }

    private fun line(text: String, start: Long, spans: List<TextSpan>) =
        PageLine(start, start + text.length, text, isParagraphStart = false, isParagraphEnd = true, spans = spans)

    @Test
    fun `styleRuns 无 span 单区间`() {
        val runs = styleRuns(line("abcd", 0L, emptyList()))
        assertEquals(listOf(StyleRun(0, 4, emptySet())), runs)
    }

    @Test
    fun `styleRuns 按行内掩码分段 叠加组合`() {
        // "a粗叠体c"：BOLD[1,4) ITALIC[2,3)（绝对偏移）
        val l = line(
            "a粗叠体c", 100L,
            listOf(
                TextSpan(TextSpanType.BOLD, 101, 104),
                TextSpan(TextSpanType.ITALIC, 102, 103),
            ),
        )
        val runs = styleRuns(l)
        assertEquals(
            listOf(
                StyleRun(0, 1, emptySet()),
                StyleRun(1, 2, setOf(TextSpanType.BOLD)),
                StyleRun(2, 3, setOf(TextSpanType.BOLD, TextSpanType.ITALIC)),
                StyleRun(3, 4, setOf(TextSpanType.BOLD)),
                StyleRun(4, 5, emptySet()),
            ),
            runs,
        )
    }

    @Test
    fun `styleRuns 链接携带 payload`() {
        val l = line(
            "去这里", 0L,
            listOf(TextSpan(TextSpanType.LINK, 0, 2, "#42")),
        )
        val runs = styleRuns(l)
        assertEquals(StyleRun(0, 2, setOf(TextSpanType.LINK), "#42"), runs[0])
        assertEquals(StyleRun(2, 3, emptySet()), runs[1])
    }
}

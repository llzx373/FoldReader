package com.llzx373.foldreader.core.reader

import android.graphics.Typeface
import com.llzx373.foldreader.core.format.BookContent
import com.llzx373.foldreader.core.format.ChapterScanner
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class StringBookContent(private val text: String) : BookContent {
    override val charCount: Long get() = text.length.toLong()
    override suspend fun read(range: LongRange): String {
        val from = range.first.coerceIn(0, text.length.toLong()).toInt()
        val to = (range.last + 1).coerceIn(0, text.length.toLong()).toInt()
        return text.substring(from, maxOf(from, to))
    }
}

private class FixedWidthMeasurer(private val charWidthPx: Int = 10) : TextMeasurer {
    override fun measureLineBreaks(
        text: CharSequence,
        widthPx: Int,
        indentPx: Int,
        fontSizePx: Float,
        letterSpacingEm: Float,
        typeface: Typeface?,
    ): IntArray {
        if (text.isEmpty()) return IntArray(0)
        val first = maxOf(1, (widthPx - indentPx) / charWidthPx)
        val rest = maxOf(1, widthPx / charWidthPx)
        val out = ArrayList<Int>()
        var end = minOf(first, text.length)
        out += end
        while (end < text.length) {
            end = minOf(end + rest, text.length)
            out += end
        }
        return out.toIntArray()
    }
}

private class LetterSpacingRecorder(private val delegate: FixedWidthMeasurer = FixedWidthMeasurer()) : TextMeasurer {
    val seen = mutableListOf<Float>()

    override fun measureLineBreaks(
        text: CharSequence,
        widthPx: Int,
        indentPx: Int,
        fontSizePx: Float,
        letterSpacingEm: Float,
        typeface: Typeface?,
    ): IntArray {
        seen += letterSpacingEm
        return delegate.measureLineBreaks(text, widthPx, indentPx, fontSizePx, letterSpacingEm, typeface)
    }
}

class PaginatorTest {

    private fun paginator(
        text: String,
        fontSizeSp: Float = 10f,
        widthPx: Int = 200,
        heightPx: Int = 100,
        maxLineChars: Int = 40,
        avoidance: PageAvoidance = PageAvoidance(),
    ) = Paginator(
        content = StringBookContent(text),
        config = LayoutConfig(
            fontSizeSp = fontSizeSp,
            lineSpacingMultiplier = 1f,
            paragraphSpacingEm = 0.4f,
            marginLeftDp = 0f,
            marginTopDp = 0f,
            marginRightDp = 0f,
            marginBottomDp = 0f,
            firstLineIndentChars = 0,
            maxLineChars = maxLineChars,
        ),
        measurer = FixedWidthMeasurer(),
        widthPx = widthPx,
        heightPx = heightPx,
        density = 1f,
        scaledDensity = 1f,
        avoidance = avoidance,
    )

    private fun paginateAll(p: Paginator, charCount: Long): List<Page> = runBlocking {
        val pages = mutableListOf<Page>()
        var page = p.pageAt(0)
        pages += page
        while (page.charEnd < charCount) {
            page = p.pageAt(page.charEnd)
            pages += page
        }
        pages
    }

    private fun mixedText(): String = buildString {
        repeat(30) { i ->
            append("第${i}段内容".repeat((i % 5) + 3))
            append("with some english words mixed in")
            append('\n')
            if (i % 7 == 3) append('\n')
        }
    }

    @Test
    fun `camera avoidance reduces odd page capacity by one line`() = runBlocking {
        // 单段长文：行高 10px、页高 100px → 满页 10 行；奇数页顶部预留 1 行后奇数页 9 行
        val text = "字".repeat(2000)
        val pages = paginateAll(paginator(text, avoidance = PageAvoidance(oddTopLines = 1)), text.length.toLong())
        assertTrue(pages.size > 3)
        for ((i, page) in pages.withIndex()) {
            if (page.charEnd >= text.length.toLong()) continue // 末页允许不满
            assertEquals("page $i capacity", if (i % 2 == 1) 9 else 10, page.lines.size)
        }
        for (i in 1 until pages.size) {
            assertEquals(pages[i - 1].charEnd, pages[i].charStart)
        }
        assertEquals(text.length.toLong(), pages.last().charEnd)
        Unit
    }

    @Test
    fun `no avoidance keeps uniform capacity`() = runBlocking {
        val text = "字".repeat(2000)
        val pages = paginateAll(paginator(text), text.length.toLong())
        assertTrue(pages.size > 3)
        for (page in pages) {
            if (page.charEnd >= text.length.toLong()) continue
            assertEquals(10, page.lines.size)
        }
        Unit
    }

    @Test
    fun `full pass loses and duplicates no characters`() = runBlocking {
        val text = mixedText()
        val pages = paginateAll(paginator(text), text.length.toLong())
        assertTrue(pages.size > 1)
        for (i in 1 until pages.size) {
            assertEquals(pages[i - 1].charEnd, pages[i].charStart)
        }
        assertEquals(0L, pages.first().charStart)
        assertEquals(text.length.toLong(), pages.last().charEnd)
        for (page in pages) {
            assertEquals(page.charStart, page.lines.first().charStart)
            assertEquals(page.charEnd, page.lines.last().charEnd)
            for (i in 1 until page.lines.size) {
                assertEquals(page.lines[i - 1].charEnd, page.lines[i].charStart)
            }
            val source = text.substring(page.charStart.toInt(), page.charEnd.toInt())
                .replace("\r", "").replace("\n", "")
            assertEquals(source, page.lines.joinToString("") { it.text })
        }
        Unit
    }

    @Test
    fun `line-start forbidden punctuation hangs on previous line`() = runBlocking {
        val text = "一".repeat(20) + "。" + "续".repeat(30)
        val page = paginator(text).pageAt(0)
        assertEquals(21, page.lines[0].text.length)
        assertTrue(page.lines[0].text.endsWith("。"))
    }

    @Test
    fun `line-end forbidden bracket moves to next line`() = runBlocking {
        val text = "字".repeat(19) + "（" + "内".repeat(30)
        val page = paginator(text).pageAt(0)
        assertEquals(19, page.lines[0].text.length)
        assertTrue(page.lines[1].text.startsWith("（"))
    }

    @Test
    fun `larger font yields more pages`() = runBlocking {
        val text = mixedText()
        val small = paginateAll(paginator(text, fontSizeSp = 10f), text.length.toLong())
        val large = paginateAll(paginator(text, fontSizeSp = 20f), text.length.toLong())
        assertTrue(large.size > small.size)
    }

    @Test
    fun `forward then backward round trip returns identical pages`() = runBlocking {
        val text = mixedText()
        val p = paginator(text)
        val forward = mutableListOf<Page>()
        var page = p.pageAt(0)
        repeat(8) {
            forward += page
            page = p.pageAt(page.charEnd)
        }
        assertNull(p.pageBefore(0))
        for (i in forward.indices.reversed().drop(1)) {
            val back = p.pageBefore(forward[i + 1].charStart)
            assertEquals(forward[i], back)
        }
    }

    @Test
    fun `max line chars caps line length and centers text`() = runBlocking {
        val text = "天地玄黄宇宙洪荒日月盈昃辰宿列张".repeat(30)
        val p = paginator(text, widthPx = 2000, maxLineChars = 10)
        val pages = paginateAll(p, text.length.toLong())
        for (page in pages) {
            assertEquals(950f, page.paddingLeft)
            assertEquals(950f, page.paddingRight)
            for (line in page.lines) {
                assertTrue(line.text.length <= 10)
            }
        }
    }

    @Test
    fun `chapter starts align with paragraph-start lines`() = runBlocking {
        val text = buildString {
            for (c in 1..5) {
                append("第${c}章 标题\n")
                append("正文内容".repeat(30))
                append("\n\n")
            }
        }
        val scanner = ChapterScanner()
        scanner.feed(text)
        val chapters = scanner.finish()
        assertTrue(chapters.size >= 5)
        val p = paginator(text)
        val paraStarts = paginateAll(p, text.length.toLong())
            .flatMap { it.lines }
            .filter { it.isParagraphStart }
            .map { it.charStart }
            .toSet()
        for (chapter in chapters) {
            assertTrue("chapter '${chapter.title}' at ${chapter.charStart}", chapter.charStart in paraStarts)
        }
    }

    private fun paginatorWithMargins(
        text: String,
        marginLeftDp: Float,
        marginRightDp: Float,
        widthPx: Int = 200,
        maxLineChars: Int = 40,
        firstLineIndentChars: Int = 0,
        measurer: TextMeasurer = FixedWidthMeasurer(),
        letterSpacingEm: Float = 0f,
    ) = Paginator(
        content = StringBookContent(text),
        config = LayoutConfig(
            fontSizeSp = 10f,
            lineSpacingMultiplier = 1f,
            letterSpacingEm = letterSpacingEm,
            paragraphSpacingEm = 0.4f,
            marginLeftDp = marginLeftDp,
            marginTopDp = 0f,
            marginRightDp = marginRightDp,
            marginBottomDp = 0f,
            firstLineIndentChars = firstLineIndentChars,
            maxLineChars = maxLineChars,
        ),
        measurer = measurer,
        widthPx = widthPx,
        heightPx = 100,
        density = 1f,
        scaledDensity = 1f,
    )

    @Test
    fun `independent left and right margins are not averaged`() = runBlocking {
        val text = "天地玄黄".repeat(50)
        val page = paginatorWithMargins(text, marginLeftDp = 10f, marginRightDp = 30f).pageAt(0)
        assertEquals(10f, page.paddingLeft, 0.001f)
        assertEquals(30f, page.paddingRight, 0.001f)
    }

    @Test
    fun `line cap centers slack without touching asymmetric margins`() = runBlocking {
        val text = "天地玄黄".repeat(50)
        // 页宽 2000，左右边距 10/30，上限 10 字×10px=100 → 余量 (1960-100)/2=930 均分居中
        val page = paginatorWithMargins(
            text, marginLeftDp = 10f, marginRightDp = 30f, widthPx = 2000, maxLineChars = 10,
        ).pageAt(0)
        assertEquals(940f, page.paddingLeft, 0.001f)
        assertEquals(960f, page.paddingRight, 0.001f)
    }

    @Test
    fun `paragraph longer than scan window yields no false paragraph start`() = runBlocking {
        val text = "字".repeat(5000)
        val p = paginatorWithMargins(
            text, marginLeftDp = 0f, marginRightDp = 0f, firstLineIndentChars = 2,
        )
        val pages = paginateAll(p, text.length.toLong())
        assertTrue(pages.size > 1)
        val starts = pages.flatMap { it.lines }.filter { it.isParagraphStart }
        assertEquals(1, starts.size)
        assertEquals(0L, starts[0].charStart)
        val ends = pages.flatMap { it.lines }.filter { it.isParagraphEnd }
        assertEquals(1, ends.size)
        assertEquals(text.length.toLong(), ends[0].charEnd)
    }

    @Test
    fun `letter spacing em reaches the measurer`() = runBlocking {
        val recorder = LetterSpacingRecorder()
        val text = "天地玄黄".repeat(20)
        paginatorWithMargins(
            text, marginLeftDp = 0f, marginRightDp = 0f,
            measurer = recorder, letterSpacingEm = 0.15f,
        ).pageAt(0)
        assertTrue(recorder.seen.isNotEmpty())
        assertEquals(0.15f, recorder.seen.first(), 0.0001f)
    }

    private class IndentRecorder(private val delegate: FixedWidthMeasurer = FixedWidthMeasurer()) : TextMeasurer {
        val seen = mutableListOf<Int>()

        override fun measureLineBreaks(
            text: CharSequence,
            widthPx: Int,
            indentPx: Int,
            fontSizePx: Float,
            letterSpacingEm: Float,
            typeface: Typeface?,
        ): IntArray {
            seen += indentPx
            return delegate.measureLineBreaks(text, widthPx, indentPx, fontSizePx, letterSpacingEm, typeface)
        }
    }

    private fun indentPaginator(
        text: String,
        autoIndentEnabled: Boolean,
        measurer: TextMeasurer,
    ) = Paginator(
        content = StringBookContent(text),
        config = LayoutConfig(
            fontSizeSp = 10f,
            lineSpacingMultiplier = 1f,
            paragraphSpacingEm = 0.4f,
            marginLeftDp = 0f,
            marginTopDp = 0f,
            marginRightDp = 0f,
            marginBottomDp = 0f,
            firstLineIndentChars = 2,
            autoIndentEnabled = autoIndentEnabled,
            maxLineChars = 40,
        ),
        measurer = measurer,
        widthPx = 200,
        heightPx = 100,
        density = 1f,
        scaledDensity = 1f,
    )

    @Test
    fun `auto indent skips paragraphs already starting with whitespace`() = runBlocking {
        val recorder = IndentRecorder()
        // 第一段无缩进 → indent=20；后三段分别以全角空格/半角空格/制表符开头 → indent=0
        val text = "正文一段\n　已有缩进\n 半角缩进\n\t制表缩进"
        indentPaginator(text, autoIndentEnabled = true, measurer = recorder).pageAt(0)
        assertEquals(listOf(20, 0, 0, 0), recorder.seen)
    }

    @Test
    fun `auto indent disabled passes zero indent for all paragraphs`() = runBlocking {
        val recorder = IndentRecorder()
        val text = "正文一段\n另一段"
        indentPaginator(text, autoIndentEnabled = false, measurer = recorder).pageAt(0)
        assertEquals(listOf(0, 0), recorder.seen)
    }
}

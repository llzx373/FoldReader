package com.llzx373.foldreader.core.reader

import com.llzx373.foldreader.core.format.TextSpan
import com.llzx373.foldreader.core.format.TextSpanType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 书内链接点按命中 + 脚注弹注截取。
 * 几何沿用 PageGeometryTest 的约定：固定字宽 10px，行高 20px，便于手工演算。
 */
class LinkHitTestingTest {

    private val measure: (String) -> Float = { it.length * 10f }

    // L0 [100,110) 10 字：LINK span [102,105) payload "#500"；L1 [110,120) 无链接
    private val page = Page(
        charStart = 100,
        charEnd = 120,
        lines = listOf(
            PageLine(
                100, 110, "甲乙丙丁戊己庚辛壬癸",
                isParagraphStart = true, isParagraphEnd = false,
                spans = listOf(TextSpan(TextSpanType.LINK, 102, 105, payload = "#500")),
            ),
            PageLine(110, 120, "子丑寅卯辰巳午未申酉", isParagraphStart = false, isParagraphEnd = true),
        ),
        paddingLeft = 0f,
        paddingRight = 0f,
    )

    private fun boxes(
        p: Page = page,
        leftPadPx: Float = 0f,
        paragraphSpacingPx: Float = 0f,
    ) = buildLineBoxes(
        page = p,
        lineHeightPx = 20f,
        paragraphSpacingPx = paragraphSpacingPx,
        indentPx = 0f,
        topPadPx = 0f,
        leftPadPx = leftPadPx,
        textWidthPx = 100f,
        justify = false,
        measure = measure,
    )

    @Test
    fun `tap inside link span hits internal link`() {
        val bs = boxes()
        // 第 2 字（offset 102 = span.start）：x∈[20,30)
        assertEquals(LinkHit.Internal(500), linkHitAt(bs, 25f, 5f))
        // 恰在字符左边界 x=20 仍命中（span.start 侧含边界）
        assertEquals(LinkHit.Internal(500), linkHitAt(bs, 20f, 5f))
        // 第 4 字（offset 104，span 内最后一字）：x∈[40,50)
        assertEquals(LinkHit.Internal(500), linkHitAt(bs, 49f, 5f))
    }

    @Test
    fun `tap outside link span misses`() {
        val bs = boxes()
        // 第 1 字（offset 101 < span.start）
        assertNull(linkHitAt(bs, 15f, 5f))
        // 第 5 字（offset 105 == span.end，半开区间不含）
        assertNull(linkHitAt(bs, 50f, 5f))
        assertNull(linkHitAt(bs, 55f, 5f))
        // 第二行无 span
        assertNull(linkHitAt(bs, 25f, 25f))
        // 行尾空白死区（x 超出文本右边界 100）
        assertNull(linkHitAt(bs, 150f, 5f))
        // 行间段距死区（第二段段首前有 10px 段距）
        val twoPara = Page(
            charStart = 0,
            charEnd = 20,
            lines = listOf(
                PageLine(
                    0, 10, "甲乙丙丁戊己庚辛壬癸",
                    isParagraphStart = true, isParagraphEnd = true,
                    spans = listOf(TextSpan(TextSpanType.LINK, 2, 5, payload = "#500")),
                ),
                PageLine(10, 20, "子丑寅卯辰巳午未申酉", isParagraphStart = true, isParagraphEnd = true),
            ),
            paddingLeft = 0f,
            paddingRight = 0f,
        )
        val spaced = boxes(p = twoPara, paragraphSpacingPx = 10f)
        // L1 yTop = 20 + 10 = 30；y=25 落在段距里
        assertNull(linkHitAt(spaced, 25f, 25f))
        assertEquals(LinkHit.Internal(500), linkHitAt(spaced, 25f, 5f))
    }

    @Test
    fun `link kinds decoded from payload`() {
        assertEquals(LinkHit.Internal(42), linkHitOf(TextSpanType.LINK, "#42"))
        assertEquals(LinkHit.Note(42), linkHitOf(TextSpanType.NOTEREF, "#42"))
        assertEquals(
            LinkHit.External("https://example.com/a"),
            linkHitOf(TextSpanType.LINK, "https://example.com/a"),
        )
        assertEquals(
            LinkHit.External("http://example.com"),
            linkHitOf(TextSpanType.NOTEREF, "http://example.com"),
        )
        // 不可解析：不消费点按
        assertNull(linkHitOf(TextSpanType.LINK, null))
        assertNull(linkHitOf(TextSpanType.LINK, "#abc"))
        assertNull(linkHitOf(TextSpanType.LINK, "#"))
        assertNull(linkHitOf(TextSpanType.LINK, "chapter2.xhtml"))
        assertNull(linkHitOf(TextSpanType.BOLD, "#1"))
    }

    @Test
    fun `noteref tap resolves to note hit`() {
        val noterefPage = Page(
            charStart = 0,
            charEnd = 10,
            lines = listOf(
                PageLine(
                    0, 10, "天地玄黄宇宙洪荒日月",
                    isParagraphStart = true, isParagraphEnd = true,
                    spans = listOf(TextSpan(TextSpanType.NOTEREF, 3, 5, payload = "#77")),
                ),
            ),
            paddingLeft = 0f,
            paddingRight = 0f,
        )
        val bs = boxes(p = noterefPage)
        assertEquals(LinkHit.Note(77), linkHitAt(bs, 35f, 5f))
        assertNull(linkHitAt(bs, 25f, 5f))
    }

    @Test
    fun `image line height participates in geometry`() {
        // L0 图片行（行高 100），L1 文本行带 LINK [11,13)
        val imagePage = Page(
            charStart = 0,
            charEnd = 20,
            lines = listOf(
                PageLine(
                    0, 1, "￼",
                    isParagraphStart = true, isParagraphEnd = true,
                    heightPx = 100f, imagePath = "img/a.png",
                ),
                PageLine(
                    1, 20, "甲乙丙丁戊己庚辛壬癸子丑寅卯辰巳午未申",
                    isParagraphStart = true, isParagraphEnd = true,
                    spans = listOf(TextSpan(TextSpanType.LINK, 11, 13, payload = "#9")),
                ),
            ),
            paddingLeft = 0f,
            paddingRight = 0f,
        )
        val bs = buildLineBoxes(
            page = imagePage,
            lineHeightPx = 20f,
            paragraphSpacingPx = 0f,
            indentPx = 0f,
            topPadPx = 0f,
            leftPadPx = 0f,
            textWidthPx = 190f,
            justify = false,
            measure = measure,
        )
        // 文本行在 y∈[100,120)：offset 11 是行内第 10 字，x∈[100,110)
        assertEquals(LinkHit.Internal(9), linkHitAt(bs, 105f, 110f))
        // 图片行高度参与几何：y=50（图片行中部）不会错落到文本行
        assertNull(linkHitAt(bs, 105f, 50f))
        // 图片行本身无链接 span
        assertNull(linkHitAt(bs, 5f, 50f))
    }

    @Test
    fun `dual page right page uses local coordinates`() {
        // 模拟双页右页：调用方已把触点换算为页内局部坐标（扣掉右页起点与居中 inset）
        val bs = boxes(leftPadPx = 40f)
        // 局部坐标下第 2 字 x∈[60,70)
        assertEquals(LinkHit.Internal(500), linkHitAt(bs, 65f, 5f))
        // 页左留白（inset 区）是死区
        assertNull(linkHitAt(bs, 20f, 5f))
    }

    @Test
    fun `excerpt takes paragraphs up to limits`() {
        val text = "第一段。\n\n第二段。\n第三段。\n第四段。"
        // 空行跳过，最多 3 段
        assertEquals("第一段。\n第二段。\n第三段。", excerptNote(text))
        assertEquals("第一段。\n第二段。", excerptNote(text, maxParagraphs = 2))
        assertEquals("第一段。", excerptNote(text, maxParagraphs = 1))
    }

    @Test
    fun `excerpt truncates at paragraph boundary when over char limit`() {
        val p1 = "甲".repeat(100)
        val p2 = "乙".repeat(100)
        val p3 = "丙".repeat(100)
        // 加上第三段会超 250 → 在段落边界收束为前两段（100+1+100=201）
        assertEquals("$p1\n$p2", excerptNote("$p1\n$p2\n$p3", maxChars = 250))
        // 第一段本身超长 → 硬截断补省略号
        val long = "字".repeat(400)
        val cut = excerptNote(long)
        assertEquals(NOTE_EXCERPT_MAX_CHARS + 1, cut.length)
        assertTrue(cut.endsWith("…"))
        assertTrue(cut.startsWith("字"))
    }

    @Test
    fun `excerpt handles target near end of book`() {
        // 目标在文末：窗口内内容不足上限，原样返回（首尾空白修整）
        assertEquals("结尾注释。", excerptNote("结尾注释。"))
        assertEquals("尾声", excerptNote("\n\n尾声\n"))
        assertEquals("", excerptNote("\n \n\t\n"))
        assertEquals("", excerptNote(""))
    }
}

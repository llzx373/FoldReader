package com.llzx373.foldreader.core.reader

import android.graphics.Paint
import android.graphics.Typeface
import com.llzx373.foldreader.core.format.BookContent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 空白归一化要在真实排版器/真实字体度量上验：零宽占位符不占宽，掩码后的首行能装下更多正文字符，
 * 断行下标仍是原文下标，且绘制几何与分页几何对得上（首行铺满、可见文字从标准缩进处开始）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WhitespaceNormalizationLayoutTest {

    /** 7 半角空格 + 2 全角空格 + 正文（《战败被俘的勇者小姐还会幸福吗》正文行的形态）。 */
    private val body = "       \u3000\u3000" + "黑色的长枪贯穿赤发龙女的胸口。".repeat(4)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 20f
        typeface = Typeface.DEFAULT
    }

    private class StringContent(private val text: String) : BookContent {
        override val charCount: Long get() = text.length.toLong()
        override suspend fun read(range: LongRange): String {
            val from = range.first.toInt().coerceIn(0, text.length)
            val to = (range.last + 1).toInt().coerceIn(from, text.length)
            return text.substring(from, to)
        }
    }

    @Test
    fun `零宽占位符确实不占宽`() {
        // 防退化守卫：legacy 图形模式下 measureText 只返回字符数，那样这条断言毫无意义
        assertTrue("度量应有真实字宽", paint.measureText("黑") > 4f)
        assertEquals(0f, paint.measureText("\u200B"), 0.0001f)
    }

    @Test
    fun `掩码段首后首行能容纳更多正文且断行仍是原文下标`() {
        val measurer = StaticLayoutTextMeasurer()
        val plain = measurer.measureLineBreaks(body, 600, 40, 20f, 0f, Typeface.DEFAULT)
        val masked = measurer.measureLineBreaks(
            maskedForMeasure(body), 600, 40, 20f, 0f, Typeface.DEFAULT,
        )

        assertTrue(
            "掩码后首行应容纳更多字符：${plain.first()} -> ${masked.first()}",
            masked.first() > plain.first(),
        )
        assertEquals("断行下标仍是原文下标", body.length, masked.last())
        assertTrue("掩码后不应需要更多行", masked.size <= plain.size)
    }

    @Test
    fun `归一化后首行铺满且可见文字从标准缩进处开始`() = runBlocking {
        val config = LayoutConfig(
            fontSizeSp = 20f,
            lineSpacingMultiplier = 1f,
            paragraphSpacingEm = 0f,
            marginLeftDp = 0f,
            marginTopDp = 0f,
            marginRightDp = 0f,
            marginBottomDp = 0f,
            firstLineIndentChars = 2,
            autoIndentEnabled = true,
            normalizeWhitespaceEnabled = true,
            maxLineChars = 40,
        )
        val page = Paginator(
            content = StringContent(body),
            config = config,
            measurer = StaticLayoutTextMeasurer(),
            widthPx = 600,
            heightPx = 1000,
            density = 1f,
            scaledDensity = 1f,
        ).pageAt(0)

        val boxes = buildLineBoxes(
            page = page,
            lineHeightPx = 20f,
            paragraphSpacingPx = 0f,
            indentPx = 2 * 20f,
            topPadPx = 0f,
            leftPadPx = page.paddingLeft,
            textWidthPx = 600f,
            justify = true,
            measure = { paint.measureText(it) },
            fillCharWidths = { text, out -> paint.getTextWidths(text, 0, text.length, out) },
            collapseWhitespace = true,
        )
        val first = boxes.first()
        // 行文本仍是原文子串：段首那 9 个空白还在，偏移不受影响
        assertTrue(first.line.text.startsWith("       \u3000\u3000"))
        // 折叠掉的前缀不占宽，可见文字正好从标准缩进处开始
        assertEquals(40f, first.x0, 0.5f)
        assertEquals(40f, first.boundaryX(9), 0.5f)
        // 两端对齐下首行铺满：绘制几何与分页几何没有错配（不会短一截、也不会溢出）
        assertEquals(600f, first.boundaryX(first.textLength), 4f)
    }
}

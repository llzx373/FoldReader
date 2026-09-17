package com.llzx373.foldreader.core.reader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.llzx373.foldreader.feature.reader.PageSpread
import com.llzx373.foldreader.feature.reader.ReaderColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 渲染层需要真实 Android 图形 API（Paint 字宽 / Bitmap / Canvas），故走 Robolectric。
 * 覆盖两件事：批量取字宽与逐字度量等价；行几何缓存的命中与失效条件。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PageRendererTest {

    private lateinit var config: LayoutConfig

    @Before
    fun setUp() {
        config = LayoutConfig(
            fontSizeSp = 16f,
            lineSpacingMultiplier = 1.5f,
            letterSpacingEm = 0f,
            paragraphSpacingEm = 0.4f,
            marginLeftDp = 8f,
            marginTopDp = 12f,
            marginRightDp = 8f,
            marginBottomDp = 12f,
            firstLineIndentChars = 2,
            maxLineChars = 40,
            typeface = Typeface.DEFAULT,
        )
    }

    private fun testPage(charStart: Long = 0L, text: String = "床前明月光，疑是地上霜。"): Page = Page(
        charStart = charStart,
        charEnd = charStart + text.length,
        lines = listOf(
            PageLine(charStart, charStart + text.length, text, isParagraphStart = true, isParagraphEnd = true),
        ),
        paddingLeft = 8f,
        paddingRight = 8f,
    )

    private fun drawInto(
        page: Page,
        cfg: LayoutConfig = config,
        textColor: Int = 0xFF000000.toInt(),
    ): List<LineBox> {
        val bitmap = Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888)
        return drawPageInto(
            canvas = Canvas(bitmap),
            page = page,
            config = cfg,
            textColorArgb = textColor,
            density = 2f,
            scaledDensity = 2f,
            widthPx = 600f,
        )
    }

    @Test
    fun `批量取字宽之和等于整行测量`() {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 40f
            typeface = Typeface.DEFAULT
            letterSpacing = 0.05f
        }
        val text = "床前明月光，疑是地上霜。ABC 123"
        val widths = FloatArray(text.length)
        paint.getTextWidths(text, 0, text.length, widths)

        // 防退化守卫：legacy 图形模式下 measureText 只返回字符串长度、getTextWidths 全为 0，
        // 那样下面的等式会毫无意义地成立。必须先确认拿到了真实度量。
        assertTrue("度量应有真实字宽，实际 ${paint.measureText(text)}", paint.measureText(text) > text.length * 4f)
        assertTrue("逐字宽度不应全为 0", widths.sum() > 0f)

        // 渲染层正是靠这条等式用 getTextWidths 替换逐字 measureText
        assertEquals(paint.measureText(text), widths.sum(), 0.01f)
    }

    @Test
    fun `真实 Paint 下批量路径与逐字路径几何一致`() {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = config.fontSizeSp * 2f
            typeface = config.typeface
        }
        val page = testPage()

        val perChar = buildLineBoxes(
            page = page,
            lineHeightPx = 48f,
            paragraphSpacingPx = 0f,
            indentPx = 32f,
            topPadPx = 0f,
            leftPadPx = 8f,
            textWidthPx = 500f,
            justify = true,
            measure = { paint.measureText(it) },
        )
        val batched = buildLineBoxes(
            page = page,
            lineHeightPx = 48f,
            paragraphSpacingPx = 0f,
            indentPx = 32f,
            topPadPx = 0f,
            leftPadPx = 8f,
            textWidthPx = 500f,
            justify = true,
            measure = { paint.measureText(it) },
            fillCharWidths = { text, out -> paint.getTextWidths(text, 0, text.length, out) },
        )

        assertEquals(perChar.size, batched.size)
        assertTrue("逐字宽度不应全为 0", batched[0].charWidths.all { it > 0f })
        perChar.forEachIndexed { index, expected ->
            val actual = batched[index]
            assertEquals(expected.x0, actual.x0, 0.01f)
            assertEquals(expected.gapPx, actual.gapPx, 0.01f)
            assertEquals(expected.charWidths.size, actual.charWidths.size)
            expected.charWidths.indices.forEach { i ->
                assertEquals(expected.charWidths[i], actual.charWidths[i], 0.01f)
            }
        }
    }

    @Test
    fun `同一页重复绘制命中几何缓存`() {
        val page = testPage()

        val first = drawInto(page)
        val second = drawInto(page)

        assertTrue(first.isNotEmpty())
        // 同一 List 实例 = 第二次没有重新度量（未走 buildLineBoxes）
        assertSame(first, second)
    }

    @Test
    fun `版式变化使几何缓存失效`() {
        val page = testPage(0L)

        val first = drawInto(page, config)
        val second = drawInto(page, config.copy(fontSizeSp = 24f))

        assertNotSame(first, second)
        assertNotEquals(first[0].charWidths[0], second[0].charWidths[0], 0.001f)
    }

    @Test
    fun `仅文字颜色变化不影响几何缓存`() {
        val page = testPage(0L)

        val dark = drawInto(page, config, textColor = 0xFF000000.toInt())
        val light = drawInto(page, config, textColor = 0xFFFFFFFF.toInt())

        assertSame(dark, light)
    }

    @Test
    fun `离屏对页位图渲染复用同一几何缓存且不为空白`() {
        val colors = ReaderColors(
            background = Color(0xFFFFFFFF),
            text = Color(0xFF000000),
            accent = Color(0xFF33691E),
        )
        val spread = PageSpread(left = testPage(0L), right = null)
        val geom = SpreadGeom(
            dual = false,
            splitLeftPx = 0f,
            splitRightPx = 0f,
            leftInsetPx = 0f,
            rightInsetPx = 0f,
            innerPadPx = 0f,
            pageWidthPx = 600f,
        )

        // 先在线程内画一次，再走离屏位图路径：两条路径必须一致
        val inline = drawInto(spread.left)
        val bitmap = renderSpreadToBitmap(
            spread = spread,
            config = config,
            colors = colors,
            geom = geom,
            leftHighlights = emptyList(),
            rightHighlights = emptyList(),
            density = 2f,
            scaledDensity = 2f,
            widthPx = 600,
            heightPx = 800,
        )

        assertEquals(600, bitmap.width)
        assertEquals(800, bitmap.height)
        assertEquals(Bitmap.Config.RGB_565, bitmap.config)
        // 文本已画上：与纯背景色不同的像素必然存在
        val background = colors.background.toArgb()
        var painted = 0
        var untouched = 0
        for (x in 0 until bitmap.width step 7) {
            for (y in 0 until bitmap.height step 7) {
                if (bitmap.getPixel(x, y) != background) painted++ else untouched++
            }
        }
        // 两个方向都要断言：只断言 painted>0 在 legacy 图形模式（drawColor 也是空操作、
        // 位图保持全透明）下会假通过；只断言 untouched>0 则正文没画上也发现不了。
        assertTrue("离屏渲染应画出正文，实际非背景像素 $painted", painted > 0)
        assertTrue("离屏渲染应铺满背景，实际背景像素 $untouched", untouched > 0)
        assertTrue(inline.isNotEmpty())
    }
}

package com.llzx373.foldreader.feature.reader

import android.graphics.Typeface
import androidx.compose.ui.geometry.Rect
import com.llzx373.foldreader.core.foldable.FoldingPosture
import com.llzx373.foldreader.core.foldable.HingeOrientation
import com.llzx373.foldreader.core.foldable.Posture
import com.llzx373.foldreader.core.format.BookContent
import com.llzx373.foldreader.core.reader.LayoutConfig
import com.llzx373.foldreader.core.reader.Paginator
import com.llzx373.foldreader.core.reader.TextMeasurer
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class SwitchTestContent(private val text: String) : BookContent {
    override val charCount: Long get() = text.length.toLong()
    override suspend fun read(range: LongRange): String {
        val from = range.first.coerceIn(0, text.length.toLong()).toInt()
        val to = (range.last + 1).coerceIn(0, text.length.toLong()).toInt()
        return text.substring(from, maxOf(from, to))
    }
}

private class SwitchTestMeasurer : TextMeasurer {
    override fun measureLineBreaks(
        text: CharSequence,
        widthPx: Int,
        indentPx: Int,
        fontSizePx: Float,
        letterSpacingEm: Float,
        typeface: Typeface?,
    ): IntArray {
        if (text.isEmpty()) return IntArray(0)
        val perLine = maxOf(1, (widthPx / fontSizePx).toInt())
        val out = ArrayList<Int>()
        var end = minOf(perLine, text.length)
        out += end
        while (end < text.length) {
            end = minOf(end + perLine, text.length)
            out += end
        }
        return out.toIntArray()
    }
}

class ViewportSwitchTest {

    private val text = buildString {
        repeat(200) { i -> append("第${i}段正文内容测试文字".repeat(6)); append('\n') }
    }

    private fun paginator(widthPx: Int, heightPx: Int, fontSizeSp: Float) = Paginator(
        content = SwitchTestContent(text),
        config = LayoutConfig(
            fontSizeSp = fontSizeSp,
            lineSpacingMultiplier = 1.5f,
            marginLeftDp = 0f,
            marginTopDp = 0f,
            marginRightDp = 0f,
            marginBottomDp = 0f,
            firstLineIndentChars = 0,
        ),
        measurer = SwitchTestMeasurer(),
        widthPx = widthPx,
        heightPx = heightPx,
        density = 1f,
        scaledDensity = 1f,
    )

    @Test
    fun `anchor survives 20 viewport and mode switches`() = runBlocking {
        val random = Random(42)
        var paginator = paginator(400, 200, 10f)
        val anchor = paginator.pageAt(5000L).charStart
        assertTrue(anchor in 1..5000L)

        repeat(20) {
            val width = 200 + random.nextInt(2000)
            val height = 300 + random.nextInt(1500)
            val font = 8f + random.nextInt(24)
            paginator = paginator(width, height, font)
            val located = paginator.pageAt(anchor)
            assertTrue("anchor $anchor must be inside located page", located.charStart <= anchor)
            assertTrue(located.charEnd >= anchor)
        }

        val finalPage = paginator.pageAt(anchor)
        assertTrue(finalPage.charStart <= anchor && anchor <= finalPage.charEnd)
        Unit
    }

    @Test
    fun `dual spread alternation stays seamless`() = runBlocking {
        val paginator = paginator(400, 200, 10f)
        var left = paginator.pageAt(0L)
        var count = 0
        while (left.charEnd < text.length && count < 50) {
            val right = paginator.pageAt(left.charEnd)
            assertEquals(left.charEnd, right.charStart)
            left = paginator.pageAt(right.charEnd)
            count++
        }
        assertTrue(count > 5)
    }

    @Test
    fun `half-opened content rect avoids hinge`() {
        val horizontal = FoldingPostureHalfOpened(HingeOrientation.HORIZONTAL)
        val rectH = contentRectFor(horizontal, Rect(0f, 800f, 1000f, 830f), 1000f, 2000f)
        assertEquals(ContentRect(0f, 0f, 1000f, 800f), rectH)

        val vertical = FoldingPostureHalfOpened(HingeOrientation.VERTICAL)
        val rectV = contentRectFor(vertical, Rect(400f, 0f, 430f, 2000f), 1000f, 2000f)
        assertEquals(ContentRect(430f, 0f, 570f, 2000f), rectV)

        val wideLeft = contentRectFor(vertical, Rect(700f, 0f, 730f, 2000f), 1000f, 2000f)
        assertEquals(ContentRect(0f, 0f, 700f, 2000f), wideLeft)

        val flat = horizontal.copy(posture = Posture.FLAT)
        assertEquals(ContentRect(0f, 0f, 1000f, 2000f), contentRectFor(flat, null, 1000f, 2000f))
    }

    private fun FoldingPostureHalfOpened(orientation: HingeOrientation) =
        FoldingPosture(
            posture = Posture.HALF_OPENED,
            hingeBounds = Rect(0f, 0f, 1f, 1f),
            hingeOrientation = orientation,
        )
}

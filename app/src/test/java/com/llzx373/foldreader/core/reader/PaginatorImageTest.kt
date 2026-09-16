package com.llzx373.foldreader.core.reader

import com.llzx373.foldreader.core.format.BookContent
import com.llzx373.foldreader.core.format.TextSpan
import com.llzx373.foldreader.core.format.TextSpanType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class StringContent(private val text: String) : BookContent {
    override val charCount: Long get() = text.length.toLong()
    override suspend fun read(range: LongRange): String {
        val from = range.first.coerceIn(0, text.length.toLong()).toInt()
        val to = (range.last + 1).coerceIn(0, text.length.toLong()).toInt()
        return text.substring(from, maxOf(from, to))
    }
}

private class ImageFixedWidthMeasurer(private val charWidthPx: Int = 10) : TextMeasurer {
    override fun measureLineBreaks(
        text: CharSequence,
        widthPx: Int,
        indentPx: Int,
        fontSizePx: Float,
        letterSpacingEm: Float,
        typeface: android.graphics.Typeface?,
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

/**
 * 图片行分页：占位段落（单 U+FFFC）+ images 表 → 图片行按实际缩放高占页；
 * 纯文本路径（无 images）行为与既有 PaginatorTest 完全一致（那边一字未改）。
 */
class PaginatorImageTest {

    private fun paginator(
        text: String,
        images: Map<Long, TextSpan> = emptyMap(),
        heightPx: Int = 100,
    ) = Paginator(
        content = StringContent(text),
        config = LayoutConfig(
            fontSizeSp = 10f,
            lineSpacingMultiplier = 1f,
            paragraphSpacingEm = 0.4f,
            marginLeftDp = 0f,
            marginTopDp = 0f,
            marginRightDp = 0f,
            marginBottomDp = 0f,
            firstLineIndentChars = 0,
            maxLineChars = 40,
        ),
        measurer = ImageFixedWidthMeasurer(),
        widthPx = 200,
        heightPx = heightPx,
        density = 1f,
        scaledDensity = 1f,
        images = images,
    )

    @Test
    fun `imageLineHeightPx 按页宽等比缩放并封顶 尺寸未知回退行高`() {
        // 100x50 图放进 200px 宽 → 放大 2 倍高 100；上限 60 → 60
        assertEquals(60f, imageLineHeightPx(100, 50, 200f, 60f, 10f), 0.001f)
        // 400x100 图 → 缩 0.5 倍高 50
        assertEquals(50f, imageLineHeightPx(400, 100, 200f, 60f, 10f), 0.001f)
        // 尺寸未知 → 回退一行文本高度
        assertEquals(10f, imageLineHeightPx(0, 0, 200f, 60f, 10f), 0.001f)
        // 极小图不为 0
        assertEquals(1f, imageLineHeightPx(1000, 1, 100f, 60f, 10f), 0.001f)
    }

    @Test
    fun `图片占位段落产出图片行且高度为缩放后实际值`() = runBlocking {
        // "段一内容"(0..3) \n\n(4,5) ￼(6) \n\n(7,8) "尾"(9)
        val text = "段一内容\n\n￼\n\n尾"
        val images = mapOf(
            6L to TextSpan(TextSpanType.IMAGE, 6, 7, payload = "p.png", alt = "图", width = 400, height = 100),
        )
        val page = paginator(text, images).pageAt(0)
        val imageLine = page.lines.single { it.imagePath != null }
        assertEquals("p.png", imageLine.imagePath)
        assertEquals("图", imageLine.imageAlt)
        assertEquals(6L, imageLine.charStart)
        assertEquals(8L, imageLine.charEnd)
        assertEquals("￼", imageLine.text)
        assertEquals(50f, imageLine.heightPx!!, 0.001f)
        assertTrue(imageLine.isParagraphStart)
        assertTrue(imageLine.isParagraphEnd)
    }

    @Test
    fun `图片行放不进剩余页高时整体移到下一页`() = runBlocking {
        // 85 字 9 行（90px）+ 空行 10px → 已满 100px；图片 50px 放不进 → 下一页首行
        val text = "字".repeat(85) + "\n\n￼\n\n尾"
        val imageOffset = 87L
        val images = mapOf(
            imageOffset to TextSpan(TextSpanType.IMAGE, imageOffset, imageOffset + 1, payload = "big.png", width = 400, height = 100),
        )
        val p = paginator(text, images)
        val page1 = p.pageAt(0)
        assertEquals(imageOffset, page1.charEnd)
        assertTrue(page1.lines.none { it.imagePath != null })
        val page2 = p.pageAt(imageOffset)
        assertEquals("big.png", page2.lines.first().imagePath)
        // 页边界连续、字符不丢不重
        assertEquals(page1.charEnd, page2.charStart)
        assertEquals(text.length.toLong(), page2.charEnd)
    }

    @Test
    fun `图片行与文本行混排不溢出页高`() = runBlocking {
        // 页高 120：文本行 10 + 空行 14 + 图片 54 + 空行 14 + 尾行 14 = 106 <= 120 → 同页
        val text = "字".repeat(10) + "\n\n￼\n\n尾"
        val imageOffset = 12L
        val images = mapOf(
            imageOffset to TextSpan(TextSpanType.IMAGE, imageOffset, imageOffset + 1, payload = "m.png", width = 400, height = 100),
        )
        val page = paginator(text, images, heightPx = 120).pageAt(0)
        assertEquals(text.length.toLong(), page.charEnd)
        assertEquals("m.png", page.lines.single { it.imagePath != null }.imagePath)
        assertEquals("尾", page.lines.last().text)
    }

    @Test
    fun `无 images 表时占位符当普通文本处理`() = runBlocking {
        val text = "前\n\n￼\n\n后"
        val page = paginator(text).pageAt(0)
        val line = page.lines.single { it.text == "￼" }
        assertNull(line.heightPx)
        assertNull(line.imagePath)
    }
}

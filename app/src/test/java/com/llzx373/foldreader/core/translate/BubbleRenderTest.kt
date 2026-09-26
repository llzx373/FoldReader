package com.llzx373.foldreader.core.translate

import com.llzx373.foldreader.core.ocr.OcrRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 覆盖层排版：采样点位、中位色抗噪、文字色、折行与字号收缩。 */
class BubbleRenderTest {

    @Test
    fun `采样点位在气泡外周且裁到页内`() {
        val rect = OcrRect.of(0.4f, 0.4f, 0.2f, 0.1f)
        val points = BubbleRender.samplePoints(rect, margin = 0.01f, pointsPerEdge = 4)
        assertEquals(16, points.size)
        // 所有点都不落在原矩形内部（外扩一圈）
        points.forEach { (x, y) ->
            assertTrue("($x,$y) 不应在气泡内", !rect.containsPoint(x, y) ||
                x == rect.left || x == rect.right || y == rect.top || y == rect.bottom)
            assertTrue(x in 0f..1f && y in 0f..1f)
        }
        // 贴边的气泡：外扩后仍裁在 0..1
        val edge = OcrRect.of(0f, 0f, 0.1f, 0.1f)
        BubbleRender.samplePoints(edge).forEach { (x, y) ->
            assertTrue(x in 0f..1f && y in 0f..1f)
        }
    }

    @Test
    fun `中位色逐通道挤掉文字噪点`() {
        // 白底气泡：多数白 + 两个黑字像素
        val colors = listOf(
            0xFFFFFFFF.toInt(), 0xFFFEFEFE.toInt(), 0xFFFFFFFF.toInt(),
            0xFF101010.toInt(), 0xFF202020.toInt(),
            0xFFFFFFFF.toInt(), 0xFFFDFDFD.toInt(),
        )
        val median = BubbleRender.medianColor(colors)!!
        // R 通道排序 10,20,FD,FE,FF,FF,FF → 中位 FE
        assertEquals(0xFE, (median ushr 16) and 0xFF)
        assertNull(BubbleRender.medianColor(emptyList()))
    }

    @Test
    fun `文字色随底色明暗反转`() {
        assertEquals(0xFF1A1A1A.toInt(), BubbleRender.textColorFor(0xFFFFFFFF.toInt()))
        assertEquals(0xFFF5F5F5.toInt(), BubbleRender.textColorFor(0xFF202020.toInt()))
    }

    @Test
    fun `全角半角宽度估算分级`() {
        assertEquals(1.0f, BubbleRender.charEm('あ'))
        assertEquals(1.0f, BubbleRender.charEm('汉'))
        assertEquals(1.0f, BubbleRender.charEm('！'))
        assertEquals(0.55f, BubbleRender.charEm('a'))
        assertTrue(BubbleRender.charEm(' ') < BubbleRender.charEm('a'))
    }

    @Test
    fun `折行按宽度贪心断开且尊重显式换行`() {
        // 10px 字号：全角 10px/字，宽 45px 每行至多 4 个全角字
        val lines = BubbleRender.wrapLines("一二三四五六七", fontSize = 10f, maxWidth = 45f)
        assertEquals(listOf("一二三四", "五六七"), lines)
        val explicit = BubbleRender.wrapLines("ab\ncd", fontSize = 10f, maxWidth = 1000f)
        assertEquals(listOf("ab", "cd"), explicit)
    }

    @Test
    fun `字号收缩到恰好装下`() {
        // 气泡 100x60px：内边距后可用 84x50.4；行高 1.15 倍
        // 字号 17 → 每行 4 个全角字 × 3 行 = 58.65px 超；字号 16 → 每行 5 字 × 2 行 = 36.8px 装下
        val font = BubbleRender.fitFontSize(
            text = "一二三四五六七八九十",
            rectWidth = 100f,
            rectHeight = 60f,
            maxFont = 40f,
        )
        assertEquals(16f, font, 0.01f)
    }

    @Test
    fun `短文本直接用上限或高度允许的最大字号`() {
        val font = BubbleRender.fitFontSize("好", 200f, 100f, maxFont = 40f)
        assertEquals(40f, font, 0.01f)
        // 高度卡死：可用高 50.4 → 单行最大 50.4/1.15 ≈ 43.8，但 maxFont=40 更小
        val tall = BubbleRender.fitFontSize("好", 200f, 20f, maxFont = 40f)
        assertTrue(tall <= 20f / BubbleRender.LINE_SPACING + 1f)
    }

    @Test
    fun `装不下时落到字号下限`() {
        val font = BubbleRender.fitFontSize("一".repeat(200), 50f, 30f, maxFont = 40f)
        assertEquals(BubbleRender.MIN_FONT_PX, font, 0.01f)
    }

    @Test
    fun `layout 返回的折行与字号自洽`() {
        val layout = BubbleRender.layout("一二三四五六七八九十", 100f, 60f, maxFont = 40f)
        val usableW = 100f * (1f - BubbleRender.INNER_PADDING * 2)
        assertEquals(
            BubbleRender.wrapLines("一二三四五六七八九十", layout.fontSize, usableW),
            layout.lines,
        )
        assertTrue(layout.textHeight <= 60f)
    }
}

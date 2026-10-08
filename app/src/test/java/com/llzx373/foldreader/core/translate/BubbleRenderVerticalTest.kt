package com.llzx373.foldreader.core.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 竖排译文渲染（M31）：竖排判定、分列、字号收缩与横竖排选择。 */
class BubbleRenderVerticalTest {

    @Test
    fun `竖排判定：RTL 默认竖排，瘦长气泡无论方向都竖排`() {
        // 日漫 RTL：方形气泡也竖排（原文本就是竖排气泡）
        assertTrue(BubbleRender.preferVertical(rtl = true, rectWidth = 100f, rectHeight = 80f))
        // LTR 但高大于宽：横排每行塞不下几个字，竖排
        assertTrue(BubbleRender.preferVertical(rtl = false, rectWidth = 60f, rectHeight = 100f))
        // LTR 横宽气泡：横排
        assertFalse(BubbleRender.preferVertical(rtl = false, rectWidth = 100f, rectHeight = 60f))
        // LTR 方形：横排
        assertFalse(BubbleRender.preferVertical(rtl = false, rectWidth = 100f, rectHeight = 100f))
    }

    @Test
    fun `分列按可用高度贪心断列且尊重显式换行`() {
        // 10px 字号：竖排推进 10.6px/字，可用高 45px 每列至多 4 个全角字
        val columns = BubbleRender.wrapColumns("一二三四五六七", fontSize = 10f, maxHeight = 45f)
        assertEquals(listOf("一二三四", "五六七"), columns)
        val explicit = BubbleRender.wrapColumns("ab\ncd", fontSize = 10f, maxHeight = 1000f)
        assertEquals(listOf("ab", "cd"), explicit)
    }

    @Test
    fun `竖排字号收缩到宽度恰好装下所有列`() {
        // 气泡 50x100px：内边距后可用 42x84。扫描起点 = 42/1.18 ≈ 35.59，逐 1 递减。
        // 字号 16.59 → 每列 4 字 × 3 列 = 58.7px 宽超；字号 15.59 → 每列 5 字 × 2 列 = 36.8px 装下
        val font = BubbleRender.fitFontSizeVertical(
            text = "一二三四五六七八九十",
            rectWidth = 50f,
            rectHeight = 100f,
            maxFont = 40f,
        )
        assertEquals(35.593220f - 20f, font, 0.001f)
    }

    @Test
    fun `竖排装不下时落到字号下限`() {
        val font = BubbleRender.fitFontSizeVertical("一".repeat(500), 30f, 40f, maxFont = 40f)
        assertEquals(BubbleRender.MIN_FONT_PX, font, 0.01f)
    }

    @Test
    fun `layoutVertical 返回的分列与字号自洽`() {
        val layout = BubbleRender.layoutVertical("一二三四五六七八九十", 50f, 100f, maxFont = 40f)
        assertTrue(layout.vertical)
        val usableH = 100f * (1f - BubbleRender.INNER_PADDING * 2)
        assertEquals(
            BubbleRender.wrapColumns("一二三四五六七八九十", layout.fontSize, usableH),
            layout.lines,
        )
        assertTrue(layout.textWidth <= 50f)
    }

    @Test
    fun `layoutAuto 按方向与气泡形状选横竖排`() {
        val vertical = BubbleRender.layoutAuto("一二三四五六七八九十", 50f, 100f, maxFont = 40f, rtl = true)
        assertTrue(vertical.vertical)
        val horizontal = BubbleRender.layoutAuto("一二三四五六七八九十", 100f, 50f, maxFont = 40f, rtl = false)
        assertFalse(horizontal.vertical)
        // LTR 瘦长气泡同样竖排
        val tall = BubbleRender.layoutAuto("一二三四五六七八九十", 50f, 100f, maxFont = 40f, rtl = false)
        assertTrue(tall.vertical)
    }
}

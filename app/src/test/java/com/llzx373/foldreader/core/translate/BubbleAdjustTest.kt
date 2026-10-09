package com.llzx373.foldreader.core.translate

import com.llzx373.foldreader.core.ocr.OcrBubble
import com.llzx373.foldreader.core.ocr.OcrRect
import com.llzx373.foldreader.core.ocr.OcrTextLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 气泡微调：命中判定（重叠取小）、移动/缩放夹取、套用覆盖。 */
class BubbleAdjustTest {

    private fun bubble(index: Int, left: Float, top: Float, right: Float, bottom: Float) =
        OcrBubble(
            index = index,
            rect = OcrRect(left, top, right, bottom),
            lines = listOf(OcrTextLine("x", OcrRect(left, top, right, bottom), 0.9f)),
            confidence = 0.9f,
        )

    @Test
    fun `命中取重叠中面积最小的气泡`() {
        val big = bubble(0, 0.1f, 0.1f, 0.9f, 0.9f)
        val small = bubble(1, 0.4f, 0.4f, 0.6f, 0.6f)
        val hit = BubbleAdjust.bubbleAt(listOf(big, small), 0.5f, 0.5f)
        assertEquals(1, hit?.index)
        assertEquals(0, BubbleAdjust.bubbleAt(listOf(big, small), 0.2f, 0.2f)?.index)
        assertNull(BubbleAdjust.bubbleAt(listOf(big, small), 0.95f, 0.95f))
    }

    @Test
    fun `移动平移整框且夹取页内`() {
        val rect = OcrRect(0.2f, 0.2f, 0.4f, 0.4f)
        assertEquals(OcrRect(0.3f, 0.3f, 0.5f, 0.5f), BubbleAdjust.move(rect, 0.1f, 0.1f))
        // 右下出页 → 贴边停（宽高不变）
        assertEquals(OcrRect(0.8f, 0.8f, 1.0f, 1.0f), BubbleAdjust.move(rect, 0.9f, 0.9f))
        assertEquals(OcrRect(0f, 0f, 0.2f, 0.2f), BubbleAdjust.move(rect, -0.9f, -0.9f))
    }

    @Test
    fun `缩放夹最小边长与页界`() {
        val rect = OcrRect(0.2f, 0.2f, 0.4f, 0.4f)
        assertEquals(OcrRect(0.2f, 0.2f, 0.6f, 0.5f), BubbleAdjust.resize(rect, 0.6f, 0.5f))
        // 拖过左上 → 最小边长
        val min = BubbleAdjust.resize(rect, 0.0f, 0.0f)
        assertEquals(BubbleAdjust.MIN_SIZE, min.width, 1e-6f)
        assertEquals(BubbleAdjust.MIN_SIZE, min.height, 1e-6f)
        // 拖出页 → 夹到页边
        assertEquals(OcrRect(0.2f, 0.2f, 1.0f, 1.0f), BubbleAdjust.resize(rect, 2f, 2f))
    }

    @Test
    fun `超界宽高先钳制不再抛空区间异常`() {
        // 宽超过整页（损坏的 .adjust.json）：钳回页内贴边，而不是 coerceIn(0, 负数) 崩掉
        val wide = BubbleAdjust.move(OcrRect(-0.5f, 0.1f, 1.5f, 0.4f), 0.3f, 0f)
        assertEquals(0f, wide.left, 1e-6f)
        assertEquals(1f, wide.right, 1e-6f)
        // 左上角本身贴底：left+MIN_SIZE 越过 1f 时下限钳住，缩放照常可用
        val edge = BubbleAdjust.resize(OcrRect(0.99f, 0.99f, 1.2f, 1.2f), 0.5f, 0.5f)
        assertTrue(edge.right >= edge.left)
        assertTrue(edge.bottom >= edge.top)
        assertTrue(edge.right <= 1f && edge.bottom <= 1f)
    }

    @Test
    fun `套用只换有序号覆盖的矩形`() {
        val bubbles = listOf(
            bubble(0, 0.1f, 0.1f, 0.2f, 0.2f),
            bubble(1, 0.5f, 0.5f, 0.6f, 0.6f),
        )
        val adjusted = BubbleAdjust.apply(
            bubbles,
            BubbleAdjustments(rects = mapOf(1 to OcrRect(0.7f, 0.7f, 0.9f, 0.9f))),
        )
        assertEquals(bubbles[0].rect, adjusted[0].rect)
        assertEquals(OcrRect(0.7f, 0.7f, 0.9f, 0.9f), adjusted[1].rect)
        assertEquals(1, adjusted[1].index)
        // 空微调原样返回（同一引用，不白拷）
        assertTrue(BubbleAdjust.apply(bubbles, null) === bubbles)
        assertTrue(BubbleAdjust.apply(bubbles, BubbleAdjustments()) === bubbles)
    }
}

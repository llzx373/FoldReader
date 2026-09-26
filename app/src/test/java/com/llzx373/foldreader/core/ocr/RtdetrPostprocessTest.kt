package com.llzx373.foldreader.core.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RT-DETR 气泡检测后处理：得分过滤、cxcywh→xyxy、归一化、越界裁剪。
 */
class RtdetrPostprocessTest {

    @Test
    fun `超阈值检测转成归一化框`() {
        // 原图 800x1200；一个气泡 cx=400 cy=300 w=200 h=100
        val labels = intArrayOf(RtdetrPostprocess.CLASS_BUBBLE)
        val boxes = floatArrayOf(400f, 300f, 200f, 100f)
        val scores = floatArrayOf(0.9f)
        val dets = RtdetrPostprocess.decode(labels, boxes, scores, 1, 800, 1200)
        assertEquals(1, dets.size)
        val rect = dets[0].rect
        assertEquals(0.375f, rect.left, 0.001f)  // (400-100)/800
        assertEquals(0.625f, rect.right, 0.001f) // (400+100)/800
        assertEquals(0.2083f, rect.top, 0.001f)  // (300-50)/1200
        assertEquals(0.2917f, rect.bottom, 0.001f)
        assertEquals(0.9f, dets[0].score, 0.001f)
    }

    @Test
    fun `低分与零面积检测被过滤`() {
        val labels = intArrayOf(0, 1, 2)
        val boxes = floatArrayOf(
            100f, 100f, 50f, 50f,   // 低分
            200f, 200f, 0f, 50f,    // 零宽
            300f, 300f, 60f, 60f,   // 有效
        )
        val scores = floatArrayOf(0.3f, 0.9f, 0.8f)
        val dets = RtdetrPostprocess.decode(labels, boxes, scores, 3, 800, 1200)
        assertEquals(1, dets.size)
        assertEquals(2, dets[0].classId)
    }

    @Test
    fun `越界框裁剪进页内`() {
        val labels = intArrayOf(0)
        val boxes = floatArrayOf(-10f, -10f, 100f, 100f) // 中心在页外
        val scores = floatArrayOf(0.9f)
        val dets = RtdetrPostprocess.decode(labels, boxes, scores, 1, 800, 1200)
        val rect = dets[0].rect
        assertTrue(rect.left >= 0f && rect.top >= 0f)
        assertTrue(rect.right <= 1f && rect.bottom <= 1f)
    }
}

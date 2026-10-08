package com.llzx373.foldreader.core.inpaint

import com.llzx373.foldreader.core.ocr.OcrRect
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 抹除几何（M31）：ROI 并集与外扩、输入尺寸缩放与 8 对齐、掩码膨胀与 ROI 映射、裁框坐标映射。
 */
class InpaintMaskTest {

    @Test
    fun `空气泡列表没有 ROI`() {
        assertNull(InpaintMask.roiOf(emptyList()))
    }

    @Test
    fun `ROI 是全部气泡的并集外扩后钳到页内`() {
        val roi = InpaintMask.roiOf(
            listOf(
                OcrRect(0.1f, 0.1f, 0.3f, 0.2f),
                OcrRect(0.5f, 0.6f, 0.98f, 0.98f),
            ),
        )!!
        assertEquals(0.06f, roi.left, 1e-6f)
        assertEquals(0.06f, roi.top, 1e-6f)
        // 外扩后越界的边钳到 1
        assertEquals(1f, roi.right, 1e-6f)
        assertEquals(1f, roi.bottom, 1e-6f)
    }

    @Test
    fun `输入尺寸等比缩放且对齐 8 的倍数`() {
        // 2000x3000 → 长边 1024：683x1024 → 对齐后 688x1024
        val (w, h) = InpaintMask.inputSizeOf(2000, 3000)
        assertTrue(w % InpaintMask.ALIGN == 0)
        assertTrue(h % InpaintMask.ALIGN == 0)
        assertEquals(688, w)
        assertEquals(1024, h)
        // 长宽比基本保持（对齐只引入个位数像素偏差）
        assertEquals(2000f / 3000f, w.toFloat() / h, 0.02f)
    }

    @Test
    fun `小 ROI 不放大只对齐`() {
        val (w, h) = InpaintMask.inputSizeOf(100, 63)
        assertEquals(104, w)
        assertEquals(64, h)
    }

    @Test
    fun `掩码把气泡矩形映射进 ROI 并膨胀`() {
        // ROI 恰好是页上半部分；气泡 0.1..0.3 × 0.02..0.06 → 输入坐标 20..60 x 8..24
        val roi = OcrRect(0f, 0f, 1f, 0.5f)
        val mask = InpaintMask.maskBytes(
            rects = listOf(OcrRect(0.1f, 0.02f, 0.3f, 0.06f)),
            roi = roi,
            width = 200,
            height = 200,
            dilatePx = 4,
        )
        assertEquals(200 * 200, mask.size)
        // 膨胀带 16..63 x 4..27：边缘命中
        assertEquals(255.toByte(), mask[10 * 200 + 25])
        assertEquals(255.toByte(), mask[4 * 200 + 16])
        assertEquals(255.toByte(), mask[27 * 200 + 63])
        // 膨胀带之外不命中
        assertEquals(0.toByte(), mask[3 * 200 + 50])
        assertEquals(0.toByte(), mask[10 * 200 + 64])
        assertEquals(0.toByte(), mask[28 * 200 + 50])
    }

    @Test
    fun `与 ROI 不相交的气泡落空且不影响其余`() {
        val roi = OcrRect(0f, 0f, 0.5f, 1f)
        val mask = InpaintMask.maskBytes(
            rects = listOf(OcrRect(0.6f, 0.1f, 0.9f, 0.2f)),
            roi = roi,
            width = 100,
            height = 100,
            dilatePx = 4,
        )
        // 膨胀带越过 ROI 右边界的部分被钳掉，右半页区域绝不命中
        assertArrayEquals(ByteArray(100 * 100), mask)
    }

    @Test
    fun `裁框映射把原页坐标换算到裁后坐标系`() {
        // 裁掉四边各 10%：裁后坐标 = (x - 0.1) / 0.8
        val box = floatArrayOf(0.1f, 0.1f, 0.9f, 0.9f)
        val r = InpaintMask.remapToCrop(OcrRect(0.3f, 0.3f, 0.5f, 0.5f), box)!!
        assertEquals(0.25f, r.left, 1e-6f)
        assertEquals(0.25f, r.top, 1e-6f)
        assertEquals(0.5f, r.right, 1e-6f)
        assertEquals(0.5f, r.bottom, 1e-6f)
    }

    @Test
    fun `完全落在裁掉区域的气泡映射为 null`() {
        val box = floatArrayOf(0.1f, 0.1f, 0.9f, 0.9f)
        assertNull(InpaintMask.remapToCrop(OcrRect(0f, 0f, 0.09f, 0.3f), box))
        assertNull(InpaintMask.remapToCrop(OcrRect(0.3f, 0.3f, 0.5f, 0.5f), floatArrayOf(0.5f, 0.5f, 0.4f, 0.9f)))
    }
}

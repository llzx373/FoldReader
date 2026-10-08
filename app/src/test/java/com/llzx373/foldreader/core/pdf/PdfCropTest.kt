package com.llzx373.foldreader.core.pdf

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** PDF 裁边（M32）：裁框渲染尺寸、裁剪像素矩形、裁后 ↔ 原页坐标换算。 */
class PdfCropTest {

    private val box = floatArrayOf(0.2f, 0.1f, 0.8f, 0.9f) // 左右各裁 20%，上下各裁 10%

    @Test
    fun `裁框渲染放大整页使裁后区域铺满槽位`() {
        // A4 595x842pt，槽位 1000x1600：裁框 0.6x0.8 → 高度方向先到槽（842*0.8→1600），
        // 整页放大到 1413x2000，裁后区域 848x1600（宽度方向等比跟走）
        val size = PdfCrop.enlargedPageSize(595f, 842f, 1000, 1600, box)!!
        assertEquals(1413, size[0])
        assertEquals(2000, size[1])
    }

    @Test
    fun `放大倍数受 MAX_ENLARGE 钳制`() {
        // 裁框只有 5% 见方：按裁框算要放大 200 倍，钳到基准（10 倍）的 4 倍 → 长边 4000
        val tiny = floatArrayOf(0f, 0f, 0.051f, 0.051f)
        val size = PdfCrop.enlargedPageSize(100f, 100f, 1000, 1000, tiny)!!
        assertEquals(4000, size[0])
    }

    @Test
    fun `整页长边受 MAX_LONG_SIDE 钳制且保持比例`() {
        val size = PdfCrop.enlargedPageSize(595f, 842f, 3900, 3900, box)!!
        assertTrue(size[1] <= PdfCrop.MAX_LONG_SIDE)
        // 宽高比与页面一致（595:842）
        assertEquals(595f / 842f, size[0].toFloat() / size[1], 0.01f)
    }

    @Test
    fun `非法输入返回 null（回退整页渲染）`() {
        assertNull(PdfCrop.enlargedPageSize(0f, 842f, 1000, 1600, box))
        assertNull(PdfCrop.enlargedPageSize(595f, 842f, 0, 1600, box))
        assertNull(PdfCrop.enlargedPageSize(595f, 842f, 1000, 1600, null))
        assertNull(PdfCrop.enlargedPageSize(595f, 842f, 1000, 1600, floatArrayOf(0.5f, 0f, 0.52f, 1f)))
    }

    @Test
    fun `裁剪矩形映射到放大整页像素坐标`() {
        val clip = PdfCrop.clipRect(1000, 2000, box)!!
        assertArrayEquals(intArrayOf(200, 200, 800, 1800), clip)
    }

    @Test
    fun `裁剪矩形退化时返回 null`() {
        assertNull(PdfCrop.clipRect(1000, 2000, null))
        assertNull(PdfCrop.clipRect(1, 2000, box))
        assertNull(PdfCrop.clipRect(1000, 2000, floatArrayOf(0.5f, 0.5f, 0.5f, 0.9f)))
    }

    @Test
    fun `裁后点换算回原页`() {
        val (x, y) = PdfCrop.pointToOriginal(0.5f, 0.5f, box)
        assertEquals(0.5f, x, 1e-4f)
        assertEquals(0.5f, y, 1e-4f)
        val (x0, y0) = PdfCrop.pointToOriginal(0f, 0f, box)
        assertEquals(0.2f, x0, 1e-4f)
        assertEquals(0.1f, y0, 1e-4f)
    }

    @Test
    fun `裁后矩形整宽换算回原页`() {
        val rect = PdfCrop.rectToOriginal(0f, 0f, 1f, 1f, box)
        assertArrayEquals(box, rect, 1e-4f)
    }

    @Test
    fun `原页矩形换算回裁后坐标系`() {
        val rect = PdfCrop.rectToCrop(0.2f, 0.1f, 0.8f, 0.9f, box)!!
        assertArrayEquals(floatArrayOf(0f, 0f, 1f, 1f), rect, 1e-4f)
    }

    @Test
    fun `部分相交的原页矩形夹到裁框边缘`() {
        // 原页 0.0~0.5 与裁框左缘 0.2 相交：裁后左缘贴 0
        val rect = PdfCrop.rectToCrop(0f, 0.3f, 0.5f, 0.6f, box)!!
        assertEquals(0f, rect[0], 1e-4f)
        assertEquals(0.5f, rect[2], 1e-4f) // (0.5-0.2)/0.6
    }

    @Test
    fun `完全落在裁掉区域的矩形返回 null`() {
        assertNull(PdfCrop.rectToCrop(0f, 0f, 0.1f, 0.5f, box))
        assertNull(PdfCrop.rectToCrop(0f, 0f, 0.5f, 0.05f, box))
    }

    @Test
    fun `坐标换算往返一致`() {
        val original = floatArrayOf(0.25f, 0.3f, 0.7f, 0.75f)
        val cropped = PdfCrop.rectToCrop(original[0], original[1], original[2], original[3], box)
        assertNotNull(cropped)
        val back = PdfCrop.rectToOriginal(cropped!![0], cropped[1], cropped[2], cropped[3], box)
        assertArrayEquals(original, back, 1e-4f)
    }
}

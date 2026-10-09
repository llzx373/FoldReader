package com.llzx373.foldreader.core.ocr.android

import android.graphics.Bitmap
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * OCR 位图回收守卫（B3）：整页检测框时 createBitmap 恒等优化返回源位图本身，
 * 回收守卫不得连带回收调用方的页位图；det 输入缩放图必须回收、源图除外。
 */
@RunWith(RobolectricTestRunner::class)
class OcrEngineBitmapRecycleTest {

    private fun bitmap(w: Int = 100, h: Int = 200): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

    @Test
    fun `整页框（crop 即源位图）回收守卫不动调用方位图`() {
        val page = bitmap()
        val scaledLine = bitmap(100, 48)

        OcrEngine.recycleLineChain(page, crop = page, line = scaledLine)

        assertFalse("整页框时页位图不能被回收", page.isRecycled)
        assertTrue(scaledLine.isRecycled)
    }

    @Test
    fun `整页框且缩放恒等（line 即源位图）什么都不回收`() {
        val page = bitmap()

        OcrEngine.recycleLineChain(page, crop = page, line = page)

        assertFalse(page.isRecycled)
    }

    @Test
    fun `普通裁剪：crop 副本与缩放图都回收，页位图不动`() {
        val page = bitmap()
        val crop = Bitmap.createBitmap(page, 10, 10, 50, 20)
        val line = bitmap(120, 48)

        OcrEngine.recycleLineChain(page, crop, line)

        assertFalse(page.isRecycled)
        assertTrue(crop.isRecycled)
        assertTrue(line.isRecycled)
    }

    @Test
    fun `det 输入缩放图回收，与源同尺寸（恒等）时不回收源`() {
        val page = bitmap()
        val scaled = bitmap(64, 64)

        OcrEngine.recycleDetInput(page, scaled)
        assertTrue(scaled.isRecycled)
        assertFalse(page.isRecycled)

        OcrEngine.recycleDetInput(page, page)
        assertFalse(page.isRecycled)
    }
}

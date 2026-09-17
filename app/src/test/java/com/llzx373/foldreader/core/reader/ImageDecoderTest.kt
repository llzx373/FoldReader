package com.llzx373.foldreader.core.reader

import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 插图解码：倍率选择是纯逻辑，直接断言；实际解码只断言尺寸与失败返回 null。
 *
 * 注意：Robolectric 的 BitmapFactory 不兑现 `inPreferredConfig`（解码结果恒为 ARGB_8888），
 * 所以这里只能断言「我们请求了 RGB_565」，不能断言拿到的位图就是 RGB_565。
 */
@RunWith(RobolectricTestRunner::class)
class ImageDecoderTest {

    private fun writePng(width: Int, height: Int): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val file = File.createTempFile("decode-sampled", ".png").apply { deleteOnExit() }
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file
    }

    @Test
    fun `倍率取 2 的幂且解码后不小于目标`() {
        // 400x300 → 目标 100x75：/2 与 /4 都还够大，/8 不够
        assertEquals(4, sampleSizeFor(400, 300, 100, 75))
        // 恰好整除时取到临界值，不能多降一级
        assertEquals(4, sampleSizeFor(400, 400, 100, 100))
        // 目标比原图还大：不降采样
        assertEquals(1, sampleSizeFor(120, 90, 400, 400))
        // 只有一个维度不够就停
        assertEquals(1, sampleSizeFor(400, 100, 100, 100))
        assertEquals(2, sampleSizeFor(400, 200, 100, 100))
    }

    @Test
    fun `解码选项请求 RGB_565 并按倍率降采样`() {
        val opts = imageDecodeOptions(400, 300, 100, 75)

        assertEquals(Bitmap.Config.RGB_565, opts.inPreferredConfig)
        assertEquals(4, opts.inSampleSize)
    }

    @Test
    fun `实际解码尺寸与倍率一致`() {
        val file = writePng(400, 300)

        val decoded = decodeSampledImage(file, targetW = 100, targetH = 75)

        assertNotNull(decoded)
        assertEquals(100, decoded!!.width)
        assertEquals(75, decoded.height)
    }

    @Test
    fun `目标小于原图时不放大`() {
        val file = writePng(120, 90)

        val decoded = decodeSampledImage(file, targetW = 400, targetH = 400)

        assertNotNull(decoded)
        assertEquals(120, decoded!!.width)
        assertEquals(90, decoded.height)
    }

    @Test
    fun `不可解码的文件返回 null`() {
        val file = File.createTempFile("decode-broken", ".png").apply { deleteOnExit() }
        file.writeBytes(byteArrayOf(1, 2, 3, 4, 5))

        assertNull(decodeSampledImage(file, targetW = 100, targetH = 100))
    }
}

package com.llzx373.foldreader.core.comic

import android.graphics.Bitmap
import androidx.exifinterface.media.ExifInterface
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 漫画页 EXIF 方向：宽高探测交换 90°/270° 的宽高，解码按标签旋转/镜像。
 * 带方向的 JPEG（手机扫描件常见）不该横躺，也不该被误判成跨页大图。
 */
@RunWith(RobolectricTestRunner::class)
class ComicExifOrientationTest {

    @Test
    fun `探测按 EXIF 90 度交换宽高`() {
        val bytes = ComicTestImages.jpegWithExifOrientation(48, 12, ExifInterface.ORIENTATION_ROTATE_90)
        assertEquals(ExifInterface.ORIENTATION_ROTATE_90, ComicImageSizing.exifOrientation(bytes))
        assertEquals(listOf(12, 48), ComicImageSizing.probe(bytes)?.toList())
    }

    @Test
    fun `探测按 EXIF 270 度交换宽高`() {
        val bytes = ComicTestImages.jpegWithExifOrientation(48, 12, ExifInterface.ORIENTATION_ROTATE_270)
        assertEquals(listOf(12, 48), ComicImageSizing.probe(bytes)?.toList())
    }

    @Test
    fun `探测对 EXIF 180 度与原方向不交换宽高`() {
        val rotated180 = ComicTestImages.jpegWithExifOrientation(48, 12, ExifInterface.ORIENTATION_ROTATE_180)
        assertEquals(listOf(48, 12), ComicImageSizing.probe(rotated180)?.toList())
        val normal = ComicTestImages.jpegWithExifOrientation(48, 12, ExifInterface.ORIENTATION_NORMAL)
        assertEquals(listOf(48, 12), ComicImageSizing.probe(normal)?.toList())
    }

    @Test
    fun `解码按 EXIF 90 度旋转输出展示尺寸`() {
        val bytes = jpegBytesWithExif(64, 32, ExifInterface.ORIENTATION_ROTATE_90)
        val decoded = ComicImageDecoder.decodeSampled(bytes, 1024, 1024)!!
        assertEquals("旋转 90° 后宽高应互换", listOf(32, 64), listOf(decoded.width, decoded.height))
    }

    @Test
    fun `解码对无方向标签的 JPEG 保持原尺寸`() {
        val bytes = jpegBytesWithExif(64, 32, ExifInterface.ORIENTATION_NORMAL)
        val decoded = ComicImageDecoder.decodeSampled(bytes, 1024, 1024)!!
        assertEquals(listOf(64, 32), listOf(decoded.width, decoded.height))
    }

    /** 真实 JPEG + 真实 EXIF 标签（走 ExifInterface 文件写入，覆盖标签解析的完整链路）。 */
    private fun jpegBytesWithExif(width: Int, height: Int, orientation: Int): ByteArray {
        val file = File.createTempFile("foldreader-exif", ".jpg")
        file.deleteOnExit()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        val exif = ExifInterface(file.absolutePath)
        exif.setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
        exif.saveAttributes()
        return file.readBytes()
    }
}

package com.llzx373.foldreader.core.comic

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream

/**
 * 测试用真实图片字节（JVM 侧用 ImageIO 现场生成，避免往仓库塞二进制样例）。
 * 尺寸可指定，用来验证头解析与页序。
 */
object ComicTestImages {

    fun png(width: Int, height: Int): ByteArray = encode("png", width, height)

    fun gif(width: Int, height: Int): ByteArray = encode("gif", width, height)

    fun jpeg(width: Int, height: Int): ByteArray = encode("jpeg", width, height)

    /**
     * 在 JPEG 的 SOI 之后插入一个最小 EXIF APP1 段（IFD0 只有方向标签）。
     * 模拟手机扫描件：像素排列不变，展示方向由标签决定。
     */
    fun jpegWithExifOrientation(width: Int, height: Int, orientation: Int): ByteArray {
        val jpeg = jpeg(width, height)
        check(jpeg[0] == 0xFF.toByte() && jpeg[1] == 0xD8.toByte()) { "不是 JPEG" }
        // "Exif"00 00 + TIFF 头(II*00, IFD0@8) + 1 个条目(tag 0x0112, SHORT, count 1) + 下一 IFD=0
        val payload = byteArrayOf(
            'E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0, 0,
            0x49, 0x49, 0x2A, 0x00, 0x08, 0x00, 0x00, 0x00,
            0x01, 0x00,
            0x12, 0x01, 0x03, 0x00, 0x01, 0x00, 0x00, 0x00,
            orientation.toByte(), 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
        )
        val length = payload.size + 2
        val app1 = byteArrayOf(
            0xFF.toByte(), 0xE1.toByte(),
            ((length shr 8) and 0xFF).toByte(), (length and 0xFF).toByte(),
        ) + payload
        return jpeg.copyOfRange(0, 2) + app1 + jpeg.copyOfRange(2, jpeg.size)
    }

    fun bmp(width: Int, height: Int): ByteArray = encode("bmp", width, height)

    /** WebP 扩展格式（VP8X）头：只有头，够 [ComicImageSizing] 判画布尺寸与动画标志。 */
    fun webpVp8x(width: Int, height: Int, animated: Boolean = false): ByteArray {
        val bytes = ByteArray(32)
        "RIFF".toByteArray(Charsets.US_ASCII).copyInto(bytes, 0)
        "WEBP".toByteArray(Charsets.US_ASCII).copyInto(bytes, 8)
        "VP8X".toByteArray(Charsets.US_ASCII).copyInto(bytes, 12)
        bytes[20] = if (animated) 0x02 else 0x00
        putLe24(bytes, 24, width - 1)
        putLe24(bytes, 27, height - 1)
        return bytes
    }

    private fun putLe24(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value and 0xFF).toByte()
        target[offset + 1] = ((value shr 8) and 0xFF).toByte()
        target[offset + 2] = ((value shr 16) and 0xFF).toByte()
    }

    private fun encode(format: String, width: Int, height: Int): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val out = ByteArrayOutputStream()
        check(javax.imageio.ImageIO.write(image, format, out)) { "无法生成 $format 测试图片" }
        return out.toByteArray()
    }
}

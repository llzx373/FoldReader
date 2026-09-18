package com.llzx373.foldreader.core.comic

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.Drawable
import com.llzx373.foldreader.core.reader.sampleSizeFor
import java.nio.ByteBuffer

/**
 * 漫画页解码。与正文插图（[com.llzx373.foldreader.core.reader.decodeSampledImage]）分开的原因：
 * 漫画页是整屏的，内存与缩放策略不同——固定 RGB_565 + 按目标尺寸幂次降采样，
 * 并且要支持动态 GIF/WebP（日漫/条漫里相当常见）。
 */
object ComicImageDecoder {

    /** 是否动画候选（GIF / 含 ANIM 块的 WebP）。只有这两种格式值得走 ImageDecoder 的 Drawable 路径。 */
    fun isAnimated(bytes: ByteArray): Boolean {
        if (bytes.size > 6 && bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() && bytes[3] == '8'.code.toByte()
        ) {
            return true
        }
        // WebP 扩展格式（VP8X）：flags 字节的 bit1 = 有动画
        if (bytes.size > 21 && bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() &&
            bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte() &&
            bytes[12] == 'V'.code.toByte() && bytes[13] == 'P'.code.toByte() &&
            bytes[14] == '8'.code.toByte() && bytes[15] == 'X'.code.toByte()
        ) {
            return (bytes[20].toInt() and 0x02) != 0
        }
        return false
    }

    /**
     * 静图降采样解码。
     *
     * 固定 `RGB_565`：漫画页不需要 alpha，每像素 2 字节而不是 4 字节，
     * 同样的 LRU 预算能多装一倍页，直接减少「解码 → 被挤掉 → 再解码」。
     * 边界尺寸优先用 [ComicImageSizing] 的头解析拿到（省掉一次 `inJustDecodeBounds` 全扫）。
     */
    fun decodeSampled(bytes: ByteArray, targetW: Int, targetH: Int): Bitmap? {
        val size = ComicImageSizing.probe(bytes) ?: probeWithFactory(bytes) ?: return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(size[0], size[1], targetW, targetH)
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) }.getOrNull()
    }

    /**
     * 动画 GIF / 动态 WebP 解成 Drawable（`ImageDecoder` 是唯一能出动画帧的路径）。
     * 认不出动画时返回 null，调用方退回 [decodeSampled]。
     * [targetW]/[targetH] 为 0 表示不降采样（自适应模式下按屏宽传即可）。
     */
    fun decodeAnimated(bytes: ByteArray, targetW: Int, targetH: Int): Drawable? = runCatching {
        ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            if (targetW > 0 && targetH > 0) {
                decoder.setTargetSampleSize(
                    sampleSizeFor(info.size.width, info.size.height, targetW, targetH),
                )
            }
        }
    }.getOrNull()

    private fun probeWithFactory(bytes: ByteArray): IntArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        return intArrayOf(bounds.outWidth, bounds.outHeight)
    }
}

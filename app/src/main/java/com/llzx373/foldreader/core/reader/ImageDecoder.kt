package com.llzx373.foldreader.core.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/**
 * 降采样倍率：取 2 的幂，优先保证解码结果不小于目标尺寸，再限制单图字节数。
 * 宁可大一点也不放大——放大只是插值出来的模糊，还更占内存。
 */
internal fun sampleSizeFor(
    srcW: Int,
    srcH: Int,
    targetW: Int,
    targetH: Int,
    maxBytes: Long = MAX_DECODED_IMAGE_BYTES,
): Int {
    if (srcW <= 0 || srcH <= 0 || targetW <= 0 || targetH <= 0) return 1
    var sample = 1
    while (sample <= MAX_SAMPLE / 2 &&
        srcW / (sample * 2) >= targetW && srcH / (sample * 2) >= targetH
    ) {
        sample *= 2
    }
    while (sample < MAX_SAMPLE && decodedByteCount(srcW, srcH, sample) > maxBytes) {
        sample *= 2
    }
    return sample
}

internal fun decodedByteCount(srcW: Int, srcH: Int, sampleSize: Int): Long {
    if (srcW <= 0 || srcH <= 0 || sampleSize <= 0) return Long.MAX_VALUE
    val width = (srcW.toLong() + sampleSize - 1) / sampleSize
    val height = (srcH.toLong() + sampleSize - 1) / sampleSize
    if (width > Long.MAX_VALUE / height) return Long.MAX_VALUE
    val pixels = width * height
    return if (pixels > Long.MAX_VALUE / 4) Long.MAX_VALUE else pixels * 4
}

internal const val MAX_DECODED_IMAGE_BYTES = 32L * 1024 * 1024
private const val MAX_SAMPLE = 1 shl 30

/**
 * 插图解码选项。
 * 固定 [Bitmap.Config.RGB_565]：正文插图不需要 alpha 通道，每像素 2 字节而不是
 * ARGB_8888 的 4 字节，同样大小的 LRU 能多装一倍图，减少「解码 → 被挤掉 → 再解码」。
 */
internal fun imageDecodeOptions(srcW: Int, srcH: Int, targetW: Int, targetH: Int): BitmapFactory.Options =
    BitmapFactory.Options().apply {
        inSampleSize = sampleSizeFor(srcW, srcH, targetW, targetH)
        inPreferredConfig = Bitmap.Config.RGB_565
    }

/** 解码内嵌插图：先探边界，再按 [imageDecodeOptions] 降采样读像素。 */
internal fun decodeSampledImage(file: File, targetW: Int, targetH: Int): Bitmap? {
    if (targetW <= 0 || targetH <= 0) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val opts = imageDecodeOptions(bounds.outWidth, bounds.outHeight, targetW, targetH)
    return BitmapFactory.decodeFile(file.absolutePath, opts)
}

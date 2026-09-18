package com.llzx373.foldreader.core.comic

import android.graphics.Bitmap
import com.llzx373.foldreader.core.format.CoverImage
import java.io.ByteArrayOutputStream

/**
 * 漫画封面：把首页降采样后编成小 JPEG 存盘。
 *
 * 直接存原图会往书架塞几百 KB 到几 MB 的整页图（解码还要再降采样一次），
 * 而封面在书架上最大也就几百像素，编一次省掉后续每次进书架的解码成本。
 */
object ComicCoverWriter {

    private const val TARGET_W = 480
    private const val TARGET_H = 640
    private const val JPEG_QUALITY = 85

    /** 解不出来（损坏页 / 不支持的格式）返回 null，调用方走标题占位封面。 */
    fun encode(pageBytes: ByteArray): CoverImage? {
        val bitmap = ComicImageDecoder.decodeSampled(pageBytes, TARGET_W, TARGET_H) ?: return null
        return encode(bitmap)
    }

    /**
     * 已经拿到位图时走这个（PDF 首页是渲染出来的，没有"页字节"可解）。
     * 尺寸由调用方保证，这里只负责编码并从**调用方手里接管**位图的回收。
     */
    fun encode(bitmap: Bitmap): CoverImage? = try {
        val out = ByteArrayOutputStream()
        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) null
        else CoverImage(out.toByteArray(), "jpg")
    } finally {
        bitmap.recycle()
    }
}

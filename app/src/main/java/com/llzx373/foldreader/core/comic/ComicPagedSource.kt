package com.llzx373.foldreader.core.comic

import android.graphics.Bitmap
import com.llzx373.foldreader.core.paged.PagedImageSource
import com.llzx373.foldreader.core.paged.PagedPageImage

/**
 * 把漫画容器适配成页式阅读器的内容来源。
 *
 * 解码（含动画判定）留在这里，因为那是漫画特有的：PDF 那一侧是渲染而不是解码。
 */
class ComicPagedSource(private val archive: ComicArchive) : PagedImageSource {

    override val pageCount: Int get() = archive.pages.size

    override suspend fun probeAspects(): FloatArray {
        val aspects = FloatArray(pageCount)
        for (index in aspects.indices) {
            val size = archive.pageSize(index) ?: continue
            if (size[0] > 0 && size[1] > 0) aspects[index] = size[0].toFloat() / size[1]
        }
        return aspects
    }

    override suspend fun loadPage(
        index: Int,
        targetWidth: Int,
        targetHeight: Int,
    ): PagedPageImage? {
        val bytes = archive.readPage(index)
        if (ComicImageDecoder.isAnimated(bytes)) {
            ComicImageDecoder.decodeAnimated(bytes, targetWidth, targetHeight)?.let { drawable ->
                return PagedPageImage.Animated(
                    drawable = drawable,
                    width = drawable.intrinsicWidth.coerceAtLeast(1),
                    height = drawable.intrinsicHeight.coerceAtLeast(1),
                )
            }
        }
        val bitmap = ComicImageDecoder.decodeSampled(bytes, targetWidth, targetHeight) ?: return null
        return PagedPageImage.Still(bitmap)
    }

    override suspend fun loadThumbnail(index: Int, width: Int, height: Int): Bitmap? {
        // 缩略图要的是静图：动画页取首帧即可，所以走 BitmapFactory 而不是动画解码
        val bytes = archive.readPage(index)
        return ComicImageDecoder.decodeSampled(bytes, width, height)
    }

    override fun close() = archive.close()
}

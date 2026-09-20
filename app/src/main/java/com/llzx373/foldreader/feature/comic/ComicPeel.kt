package com.llzx373.foldreader.feature.comic

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.llzx373.foldreader.core.data.settings.ComicFitMode
import com.llzx373.foldreader.core.paged.PagedPageImage
import com.llzx373.foldreader.feature.reader.peel.PeelBitmapFit
import com.llzx373.foldreader.feature.reader.peel.PeelLeaf
import kotlin.math.roundToInt

/**
 * 漫画仿真翻页的叶矩形。必须与 [ComicSpread] 的左右栏同宽同原点，
 * 否则 overlay 会盖偏一截铰链。
 *
 * [splitLeft] / [splitRight] 与内容区同一套坐标（内容 Box 已经 offset 到 contentRect）。
 */
fun comicPeelLeaves(
    dualLeaves: Boolean,
    contentWidth: Float,
    contentHeight: Float,
    splitLeft: Float,
    splitRight: Float,
): Pair<PeelLeaf, PeelLeaf?> {
    if (!dualLeaves || contentWidth <= 0f || contentHeight <= 0f) {
        return PeelLeaf(contentWidth.coerceAtLeast(1f), contentHeight.coerceAtLeast(1f)) to null
    }
    val leftW = splitLeft.coerceAtLeast(1f)
    val rightOrigin = splitRight.coerceAtLeast(0f)
    val rightW = (contentWidth - rightOrigin).coerceAtLeast(1f)
    val left = PeelLeaf(width = leftW, height = contentHeight, originX = 0f, originY = 0f)
    val right = PeelLeaf(width = rightW, height = contentHeight, originX = rightOrigin, originY = 0f)
    return left to right
}

/** 解码缓存里可直接当仿真源图用的静图；GIF / 动态 WebP 没有这一份。 */
fun pagedStillBitmap(image: PagedPageImage?): Bitmap? =
    (image as? PagedPageImage.Still)?.bitmap?.takeUnless { it.isRecycled }

/** 空页（缺栏白纸）或未回收的静图都可以跳过烘拷贝。 */
fun canReusePagedStill(image: PagedPageImage?): Boolean =
    image == null || pagedStillBitmap(image) != null

/**
 * 源图按适应模式落在 [dest] 里的矩形（与 [drawPagedImage] 同一套 contain / 居中）。
 * 仿真层拿这个矩形直接画缓存位图，不再 bake 一份叶尺寸拷贝。
 */
fun comicPeelLetterboxFit(
    imageWidth: Int,
    imageHeight: Int,
    destLeft: Float,
    destTop: Float,
    destRight: Float,
    destBottom: Float,
    fitMode: ComicFitMode,
): PeelBitmapFit {
    val destW = destRight - destLeft
    val destH = destBottom - destTop
    if (imageWidth <= 0 || imageHeight <= 0 || destW <= 0f || destH <= 0f) {
        return PeelBitmapFit(destLeft, destTop, destRight, destBottom)
    }
    val (baseW, baseH) = comicBaseSize(imageWidth, imageHeight, destW, destH, fitMode)
    if (baseW <= 0f || baseH <= 0f) {
        return PeelBitmapFit(destLeft, destTop, destRight, destBottom)
    }
    val left = destLeft + (destW - baseW) / 2f
    val top = destTop + (destH - baseH) / 2f
    return PeelBitmapFit(left, top, left + baseW, top + baseH)
}

fun comicPeelLetterboxFit(
    image: PagedPageImage?,
    destLeft: Float,
    destTop: Float,
    destRight: Float,
    destBottom: Float,
    fitMode: ComicFitMode,
): PeelBitmapFit {
    if (image == null) return PeelBitmapFit(destLeft, destTop, destRight, destBottom)
    return comicPeelLetterboxFit(
        imageWidth = image.width,
        imageHeight = image.height,
        destLeft = destLeft,
        destTop = destTop,
        destRight = destRight,
        destBottom = destBottom,
        fitMode = fitMode,
    )
}

/** 双叶仿真：一叶一页，整叶 letterbox。 */
fun comicPeelPageFit(
    image: PagedPageImage?,
    leafWidth: Float,
    leafHeight: Float,
    fitMode: ComicFitMode,
): PeelBitmapFit = comicPeelLetterboxFit(image, 0f, 0f, leafWidth, leafHeight, fitMode)

/**
 * 单叶仿真里一张图的落位，与 [renderComicSpreadBitmap] 的栏位一致：
 * 整宽 / 跨页大图铺满；落单封面占自己那半边。
 */
fun comicPeelSpreadPageFit(
    image: PagedPageImage?,
    pages: List<Int>,
    dual: Boolean,
    rtl: Boolean,
    wideSpan: Boolean,
    leafWidth: Float,
    leafHeight: Float,
    splitLeft: Float,
    splitRight: Float,
    fitMode: ComicFitMode,
): PeelBitmapFit {
    val destLeft: Float
    val destRight: Float
    val destTop = 0f
    val destBottom = leafHeight
    when {
        !dual || pages.isEmpty() || wideSpan -> {
            destLeft = 0f
            destRight = leafWidth
        }
        pages.size == 1 && rtl -> {
            destLeft = splitRight
            destRight = leafWidth
        }
        pages.size == 1 -> {
            destLeft = 0f
            destRight = splitLeft
        }
        else -> {
            destLeft = 0f
            destRight = leafWidth
        }
    }
    return comicPeelLetterboxFit(image, destLeft, destTop, destRight, destBottom, fitMode)
}

/** 把一页按适应模式居中画进叶尺寸。GIF 或必须合成时才走这条拷贝路径。 */
fun renderComicPageBitmap(
    image: PagedPageImage?,
    widthPx: Int,
    heightPx: Int,
    backgroundArgb: Int,
    fitMode: ComicFitMode,
): Bitmap {
    val w = widthPx.coerceAtLeast(1)
    val h = heightPx.coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    bitmap.eraseColor(backgroundArgb)
    if (image != null) {
        drawPagedImage(Canvas(bitmap), image, RectF(0f, 0f, w.toFloat(), h.toFloat()), fitMode)
    }
    return bitmap
}

/**
 * 把一整跨页画进内容区尺寸，版式与 [ComicSpread] 对齐：
 * 单页/宽图铺满；落单封面占自己那半边；成对双页按铰链分栏。
 */
fun renderComicSpreadBitmap(
    pages: List<Int>,
    images: Map<Int, PagedPageImage>,
    dual: Boolean,
    rtl: Boolean,
    wideSpan: Boolean,
    widthPx: Int,
    heightPx: Int,
    splitLeft: Float,
    splitRight: Float,
    backgroundArgb: Int,
    fitMode: ComicFitMode,
): Bitmap {
    val w = widthPx.coerceAtLeast(1)
    val h = heightPx.coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    bitmap.eraseColor(backgroundArgb)
    val canvas = Canvas(bitmap)
    val full = RectF(0f, 0f, w.toFloat(), h.toFloat())
    when {
        !dual || pages.isEmpty() -> {
            pages.firstOrNull()?.let { idx ->
                images[idx]?.let { drawPagedImage(canvas, it, full, fitMode) }
            }
        }
        pages.size == 1 && wideSpan -> {
            images[pages[0]]?.let { drawPagedImage(canvas, it, full, fitMode) }
        }
        pages.size == 1 -> {
            val dest = if (rtl) {
                RectF(splitRight, 0f, w.toFloat(), h.toFloat())
            } else {
                RectF(0f, 0f, splitLeft, h.toFloat())
            }
            images[pages[0]]?.let { drawPagedImage(canvas, it, dest, fitMode) }
        }
        else -> {
            val leftIndex = if (rtl) pages[1] else pages[0]
            val rightIndex = if (rtl) pages[0] else pages[1]
            images[leftIndex]?.let {
                drawPagedImage(canvas, it, RectF(0f, 0f, splitLeft, h.toFloat()), fitMode)
            }
            images[rightIndex]?.let {
                drawPagedImage(canvas, it, RectF(splitRight, 0f, w.toFloat(), h.toFloat()), fitMode)
            }
        }
    }
    return bitmap
}

private fun drawPagedImage(
    canvas: Canvas,
    image: PagedPageImage,
    dest: RectF,
    fitMode: ComicFitMode,
) {
    if (dest.width() <= 0f || dest.height() <= 0f) return
    val (baseW, baseH) = comicBaseSize(
        image.width,
        image.height,
        dest.width(),
        dest.height(),
        fitMode,
    )
    if (baseW <= 0f || baseH <= 0f) return
    val left = dest.left + (dest.width() - baseW) / 2f
    val top = dest.top + (dest.height() - baseH) / 2f
    val dst = RectF(left, top, left + baseW, top + baseH)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    when (image) {
        is PagedPageImage.Still -> {
            val src = image.bitmap
            if (src.isRecycled) return
            canvas.drawBitmap(src, null, dst, paint)
        }
        is PagedPageImage.Animated -> {
            val drawable = image.drawable
            val saved = Rect()
            drawable.copyBounds(saved)
            drawable.setBounds(
                dst.left.roundToInt(),
                dst.top.roundToInt(),
                dst.right.roundToInt(),
                dst.bottom.roundToInt(),
            )
            drawable.draw(canvas)
            drawable.bounds = saved
        }
    }
}

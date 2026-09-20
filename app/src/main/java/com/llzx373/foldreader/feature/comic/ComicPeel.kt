package com.llzx373.foldreader.feature.comic

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.llzx373.foldreader.core.data.settings.ComicFitMode
import com.llzx373.foldreader.core.paged.PagedPageImage
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

/** 把一页按适应模式居中画进叶尺寸。源图归 ViewModel，这里只出新位图。 */
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

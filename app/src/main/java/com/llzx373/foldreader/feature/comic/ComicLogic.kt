package com.llzx373.foldreader.feature.comic

import com.llzx373.foldreader.core.data.settings.ComicFitMode
import kotlin.math.roundToInt

/**
 * 漫画阅读的纯计算：跨页配对、进度、页码。
 *
 * 这里刻意不引入任何 Android 类型，页序与进度是漫画阅读里最容易错一位的地方，
 * 留在纯 JVM 单测范围内比在真机上翻书找错便宜得多。
 */

/** 宽高比超过该值视为「横向跨页大图」（原书里排成一整幅的双页图）。 */
const val COMIC_WIDE_ASPECT = 1.15f

fun isWidePage(width: Int, height: Int): Boolean =
    width > 0 && height > 0 && width.toFloat() / height >= COMIC_WIDE_ASPECT

/**
 * 跨页配对索引。
 *
 * 双页模式下「哪几页并排」不是简单的 `(2k, 2k+1)`：封面要单独成页、横向跨页大图要独占整宽，
 * 这两件事都会让之后的配对整体错位。所以配对一次性预计算成索引，页序号 ↔ 跨页序号变成纯查表——
 * 进度恢复、缩略图跳转、翻页步长都自动落在正确的跨页上，不必各自再算一遍。
 *
 * 尺寸未知时（还没探测完）用 [EMPTY_WIDE] 只做基础配对，探测完成后重建即可。
 */
class ComicSpreadIndex internal constructor(
    /** 跨页序号 → 首页码。 */
    private val spreadStarts: IntArray,
    /** 跨页序号 → 页数。 */
    private val spreadSizes: IntArray,
    /** 页序号 → 跨页序号。 */
    private val spreadOfPage: IntArray,
) {

    val spreadCount: Int get() = spreadStarts.size
    val pageCount: Int get() = spreadOfPage.size

    fun spreadOf(pageIndex: Int): Int =
        if (spreadOfPage.isEmpty()) 0 else spreadOfPage[pageIndex.coerceIn(0, spreadOfPage.lastIndex)]

    fun startOfSpread(ordinal: Int): Int =
        if (spreadStarts.isEmpty()) 0 else spreadStarts[ordinal.coerceIn(0, spreadStarts.lastIndex)]

    /** 该页所在跨页的首页码；单页模式下就是它自己。 */
    fun startOf(pageIndex: Int): Int = startOfSpread(spreadOf(pageIndex))

    /** 该页所在跨页的全部页。 */
    fun pagesOf(pageIndex: Int): List<Int> = pagesOfSpread(spreadOf(pageIndex))

    fun pagesOfSpread(ordinal: Int): List<Int> {
        if (spreadStarts.isEmpty()) return emptyList()
        val index = ordinal.coerceIn(0, spreadStarts.lastIndex)
        val start = spreadStarts[index]
        return (start until (start + spreadSizes[index]).coerceAtMost(spreadOfPage.size)).toList()
    }

    /** 下一页所在跨页的首页；已在末跨页返回 null。 */
    fun next(pageIndex: Int): Int? =
        (spreadOf(pageIndex) + 1).takeIf { it < spreadCount }?.let { startOfSpread(it) }

    /** 上一页所在跨页的首页；已在首跨页返回 null。 */
    fun previous(pageIndex: Int): Int? =
        (spreadOf(pageIndex) - 1).takeIf { it >= 0 }?.let { startOfSpread(it) }

    /** 是否已是最后一个跨页。 */
    fun isLastSpread(pageIndex: Int): Boolean = spreadOf(pageIndex) >= spreadCount - 1

    companion object {
        val EMPTY = ComicSpreadIndex(IntArray(0), IntArray(0), IntArray(0))

        /** 尺寸未知时的占位（只做基础配对，不做宽图独占）。 */
        val EMPTY_WIDE = BooleanArray(0)
    }
}

/**
 * 构建跨页索引。[wide] 为每页是否横向宽图；长度为 0 或与页数不符时不做宽图独占。
 *
 * @param dual 单页模式下每个跨页恒为一页。
 * @param coverAlone 首页单独成页（纸质书翻开的习惯：封面独占一屏）。
 */
fun buildComicSpreadIndex(
    pageCount: Int,
    dual: Boolean,
    coverAlone: Boolean,
    wide: BooleanArray = ComicSpreadIndex.EMPTY_WIDE,
): ComicSpreadIndex {
    if (pageCount <= 0) return ComicSpreadIndex.EMPTY
    val starts = ArrayList<Int>(pageCount)
    val sizes = ArrayList<Int>(pageCount)
    val ofPage = IntArray(pageCount)
    val wideKnown = wide.size == pageCount
    var page = 0
    while (page < pageCount) {
        val size = when {
            !dual -> 1
            coverAlone && page == 0 -> 1
            wideKnown && wide[page] -> 1
            page + 1 < pageCount -> 2
            else -> 1
        }
        val ordinal = starts.size
        starts += page
        sizes += size
        for (k in page until page + size) ofPage[k] = ordinal
        page += size
    }
    return ComicSpreadIndex(starts.toIntArray(), sizes.toIntArray(), ofPage)
}

/** 页序号（0 基）→ 0..1 进度。 */
fun comicProgressOf(pageIndex: Int, pageCount: Int): Float =
    if (pageCount <= 0) 0f else (pageIndex.toFloat() / pageCount).coerceIn(0f, 1f)

/**
 * 0..1 进度 → 页序号（夹在有效范围内）。
 *
 * 取**最近**的页而不是向下取整：进度条往返时 `page/count*count` 会因浮点误差
 * 略小于 `page`（如 3/37*37 = 2.9999998），截断会让拖到某页又弹回上一页；
 * 向下取整在「拖到某页中间」时也会让用户觉得差一页。
 */
fun comicPageFromFraction(fraction: Float, pageCount: Int): Int =
    if (pageCount <= 0) {
        0
    } else {
        (fraction.coerceIn(0f, 1f) * pageCount).roundToInt().coerceIn(0, pageCount - 1)
    }

/** 页脚页码文本：多页给区间（第 x–y/n 页），单页给单值。 */
fun comicPageNumberText(pages: List<Int>, pageCount: Int): String? {
    val first = pages.firstOrNull() ?: return null
    if (pageCount <= 0 || first !in 0 until pageCount) return null
    val start = first + 1
    return if (pages.size >= 2) {
        val end = (pages.last() + 1).coerceAtMost(pageCount)
        "第 $start–$end/$pageCount 页"
    } else {
        "第 $start/$pageCount 页"
    }
}

/**
 * 一页在容器里的基准显示尺寸（不含用户缩放），单位与容器一致（像素）。
 *
 * - FIT_PAGE：整页可见（等比contain），读画面用；
 * - FIT_WIDTH：铺满宽度，高度可溢出——扫描版漫画文字小，这一档最实用；
 * - FIT_HEIGHT：铺满高度；
 * - ORIGINAL：1 图片像素 = 1 屏幕像素，不看密度。
 */
fun comicBaseSize(
    imageW: Int,
    imageH: Int,
    containerW: Float,
    containerH: Float,
    fitMode: ComicFitMode,
): Pair<Float, Float> {
    if (imageW <= 0 || imageH <= 0 || containerW <= 0f || containerH <= 0f) return 0f to 0f
    val scale = when (fitMode) {
        ComicFitMode.FIT_PAGE -> minOf(containerW / imageW, containerH / imageH)
        ComicFitMode.FIT_WIDTH -> containerW / imageW
        ComicFitMode.FIT_HEIGHT -> containerH / imageH
        ComicFitMode.ORIGINAL -> 1f
    }
    return (imageW * scale) to (imageH * scale)
}

/**
 * 平移偏移钳制：内容比容器大时不让边缘露白（最多拖到边缘对齐），
 * 比容器小时恒为居中（偏移只能是 0）。
 */
fun clampComicOffset(offset: Float, contentSize: Float, containerSize: Float): Float {
    val limit = ((contentSize - containerSize) / 2f).coerceAtLeast(0f)
    return offset.coerceIn(-limit, limit)
}

/**
 * 一页在容器里的实际绘制矩形（像素）：居中落位之后叠加用户的缩放与平移。
 */
data class ComicDrawRect(val left: Float, val top: Float, val width: Float, val height: Float)

/**
 * 计算落位。
 *
 * 绘制、命中（[comicNormalizedAt]）、反向定位（[comicScreenPosition]）、缩放锚点
 * （[comicZoomAnchored]）全部以这里为唯一出处：绘制公式一改，其余三处必须跟着走，
 * 否则锚点会整体偏移——这类错位在单页小图上看不出来，一到双页或放大就露馅。
 */
fun comicDrawRect(
    containerW: Float,
    containerH: Float,
    baseW: Float,
    baseH: Float,
    scale: Float,
    offsetX: Float,
    offsetY: Float,
): ComicDrawRect {
    val drawW = baseW * scale
    val drawH = baseH * scale
    return ComicDrawRect(
        left = (containerW - drawW) / 2f + offsetX,
        top = (containerH - drawH) / 2f + offsetY,
        width = drawW,
        height = drawH,
    )
}

/**
 * 容器坐标 → 归一化页内坐标（0..1，页图片左上为原点）。落在页外返回 null。
 *
 * 存归一化而不是像素是刻意的：渲染尺寸随适配模式、缩放、窗口与分屏变化，
 * 锚点若存像素，换个适配模式或转个屏就全部错位。
 */
fun comicNormalizedAt(
    x: Float,
    y: Float,
    containerW: Float,
    containerH: Float,
    baseW: Float,
    baseH: Float,
    scale: Float,
    offsetX: Float,
    offsetY: Float,
): Pair<Float, Float>? {
    val rect = comicDrawRect(containerW, containerH, baseW, baseH, scale, offsetX, offsetY)
    if (rect.width <= 0f || rect.height <= 0f) return null
    val nx = (x - rect.left) / rect.width
    val ny = (y - rect.top) / rect.height
    if (nx < 0f || nx > 1f || ny < 0f || ny > 1f) return null
    return nx to ny
}

/** 归一化页内坐标 → 容器坐标；与 [comicNormalizedAt] 互逆（不夹取，越界交给调用方判断）。 */
fun comicScreenPosition(
    nx: Float,
    ny: Float,
    containerW: Float,
    containerH: Float,
    baseW: Float,
    baseH: Float,
    scale: Float,
    offsetX: Float,
    offsetY: Float,
): Pair<Float, Float> {
    val rect = comicDrawRect(containerW, containerH, baseW, baseH, scale, offsetX, offsetY)
    return (rect.left + nx * rect.width) to (rect.top + ny * rect.height)
}

/**
 * 缩放锚点：把缩放由 [fromScale] 变到 [toScale]，并让 [anchorX]/[anchorY] 下方的内容保持不动。
 *
 * 双指缩放与双击放大共用这一套数学。推导：内容点 nx 在屏幕上的位置是
 * `(W - drawW)/2 + offsetX + nx * drawW`，要求缩放前后 nx 仍落在同一屏幕位置，
 * 解出 `offsetX' = anchorX - (W - drawW')/2 - nx * drawW'`（ny 同理）。
 */
fun comicZoomAnchored(
    anchorX: Float,
    anchorY: Float,
    containerW: Float,
    containerH: Float,
    baseW: Float,
    baseH: Float,
    fromScale: Float,
    fromOffsetX: Float,
    fromOffsetY: Float,
    toScale: Float,
): Pair<Float, Float> {
    if (baseW <= 0f || baseH <= 0f || fromScale <= 0f || toScale <= 0f) {
        return fromOffsetX to fromOffsetY
    }
    val from = comicDrawRect(containerW, containerH, baseW, baseH, fromScale, fromOffsetX, fromOffsetY)
    if (from.width <= 0f || from.height <= 0f) return fromOffsetX to fromOffsetY
    val nx = (anchorX - from.left) / from.width
    val ny = (anchorY - from.top) / from.height
    val to = comicDrawRect(containerW, containerH, baseW, baseH, toScale, 0f, 0f)
    return (anchorX - to.left - nx * to.width) to (anchorY - to.top - ny * to.height)
}

package com.llzx373.foldreader.feature.reader

import androidx.compose.ui.geometry.Rect
import com.llzx373.foldreader.core.data.settings.DualPageMode
import com.llzx373.foldreader.core.data.settings.PageTurnMode
import com.llzx373.foldreader.core.foldable.FoldingPosture
import com.llzx373.foldreader.core.foldable.HingeOrientation
import com.llzx373.foldreader.core.foldable.Posture
import com.llzx373.foldreader.core.foldable.WidthCategory
import com.llzx373.foldreader.core.format.Chapter
import com.llzx373.foldreader.core.reader.PageAvoidance

enum class TapZone { PREVIOUS, MENU, NEXT }

enum class PageLayoutMode { SINGLE, DUAL }

/**
 * 有效翻页方式：用户显式设置过（explicit）则一切姿态用存储值；
 * 否则双页姿态默认仿真、单页默认覆盖（此时存储值不生效）。
 */
fun effectivePageTurnMode(
    storedMode: PageTurnMode,
    explicit: Boolean,
    dualPage: Boolean,
): PageTurnMode = when {
    explicit -> storedMode
    dualPage -> PageTurnMode.SIMULATION
    else -> PageTurnMode.COVER
}

data class ContentRect(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
)

private fun rectsOverlap(a: ContentRect, b: ContentRect): Boolean =
    a.left < b.left + b.width && a.left + a.width > b.left &&
        a.top < b.top + b.height && a.top + a.height > b.top

/**
 * 摄像头开孔规避行数（双页翻页模式，全部窗口坐标）：开孔与右页上半部分相交 →
 * 右页（奇数序页）顶部预留足够行数让开开孔；与左页下半部分相交 → 左页（偶数序页）
 * 底部预留。行数按完整覆盖开孔计算，上限 8 行防御异常 inset 数据。
 */
fun cameraAvoidanceLines(
    cutouts: List<ContentRect>,
    leftPage: ContentRect,
    rightPage: ContentRect,
    lineHeightPx: Float,
): PageAvoidance {
    if (lineHeightPx <= 0f) return PageAvoidance()
    var oddTop = 0
    var evenBottom = 0
    for (c in cutouts) {
        val cCenterY = c.top + c.height / 2f
        if (rectsOverlap(c, rightPage) && cCenterY < rightPage.top + rightPage.height / 2f) {
            val lines = kotlin.math.ceil((c.top + c.height - rightPage.top) / lineHeightPx).toInt()
            oddTop = maxOf(oddTop, lines)
        }
        if (rectsOverlap(c, leftPage) && cCenterY > leftPage.top + leftPage.height / 2f) {
            val lines = kotlin.math.ceil((leftPage.top + leftPage.height - c.top) / lineHeightPx).toInt()
            evenBottom = maxOf(evenBottom, lines)
        }
    }
    return PageAvoidance(
        oddTopLines = oddTop.coerceIn(0, 8),
        evenBottomLines = evenBottom.coerceIn(0, 8),
    )
}

data class TabletopLayout(
    val content: ContentRect,
    val panel: ContentRect,
)

fun resolveTabletopLayout(
    posture: FoldingPosture,
    hingeLocal: Rect?,
    widthPx: Float,
    heightPx: Float,
): TabletopLayout? {
    if (posture.posture != Posture.HALF_OPENED ||
        posture.hingeOrientation != HingeOrientation.HORIZONTAL ||
        hingeLocal == null
    ) {
        return null
    }
    val contentBottom = hingeLocal.top.coerceIn(0f, heightPx)
    val panelTop = hingeLocal.bottom.coerceIn(contentBottom, heightPx)
    if (contentBottom <= 0f || heightPx - panelTop <= 0f) return null
    return TabletopLayout(
        content = ContentRect(0f, 0f, widthPx, contentBottom),
        panel = ContentRect(0f, panelTop, widthPx, heightPx - panelTop),
    )
}

fun contentRectFor(
    posture: FoldingPosture,
    hingeLocal: Rect?,
    widthPx: Float,
    heightPx: Float,
): ContentRect {
    val full = ContentRect(0f, 0f, widthPx, heightPx)
    if (posture.posture != Posture.HALF_OPENED || hingeLocal == null) return full
    return when (posture.hingeOrientation) {
        HingeOrientation.HORIZONTAL -> ContentRect(
            left = 0f,
            top = 0f,
            width = widthPx,
            height = hingeLocal.top.coerceIn(0f, heightPx),
        )
        HingeOrientation.VERTICAL -> {
            val leftWidth = hingeLocal.left.coerceIn(0f, widthPx)
            val rightWidth = (widthPx - hingeLocal.right).coerceIn(0f, widthPx)
            if (leftWidth >= rightWidth) {
                ContentRect(0f, 0f, leftWidth, heightPx)
            } else {
                ContentRect(hingeLocal.right.coerceIn(0f, widthPx), 0f, rightWidth, heightPx)
            }
        }
        null -> full
    }
}

fun resolvePageLayoutMode(
    posture: FoldingPosture,
    widthCategory: WidthCategory,
    pref: DualPageMode,
    wideScreenDualPage: Boolean = false,
): PageLayoutMode {
    if (posture.posture == Posture.HALF_OPENED) return PageLayoutMode.SINGLE
    return when (pref) {
        DualPageMode.FORCE_SINGLE -> PageLayoutMode.SINGLE
        DualPageMode.FORCE_DUAL -> PageLayoutMode.DUAL
        DualPageMode.AUTO -> {
            // 铰链存在以 FoldingFeature 是否上报（方向非空）为准：不少设备 FLAT 时上报
            // 零面积 bounds（折痕不遮挡内容），要求 bounds 非空会让这些设备永远单页。
            val hingePresent = posture.hingeOrientation != null
            when {
                posture.posture == Posture.FLAT && hingePresent -> PageLayoutMode.DUAL
                widthCategory == WidthCategory.EXPANDED && wideScreenDualPage -> PageLayoutMode.DUAL
                else -> PageLayoutMode.SINGLE
            }
        }
    }
}

/** 双页左右安全区切分：竖铰按铰链 bounds，横铰（横贯全宽）与无铰链一律按屏幕中缝均分。 */
fun dualSplit(
    posture: FoldingPosture,
    hingeLocal: Rect?,
    widthPx: Float,
): Pair<Float, Float> {
    if (widthPx <= 0f) return 0f to 0f
    val center = widthPx / 2f
    if (posture.hingeOrientation != HingeOrientation.VERTICAL || hingeLocal == null) {
        return center to center
    }
    val left = hingeLocal.left.coerceIn(0f, widthPx)
    val right = hingeLocal.right.coerceIn(left, widthPx)
    return left to right
}

/** 双页共用同一分页流：页宽取左右安全区较小值，页在各自安全区内水平居中。 */
fun dualPageWidthPx(leftWidthPx: Int, rightWidthPx: Int): Int = minOf(leftWidthPx, rightWidthPx)

fun isDualColumnScroll(
    layoutMode: PageLayoutMode,
    scrollMode: Boolean,
    tabletopActive: Boolean,
): Boolean = layoutMode == PageLayoutMode.DUAL && scrollMode && !tabletopActive

fun tapZoneOf(
    x: Float,
    widthPx: Float,
    hotspotRatio: Float,
    y: Float = -1f,
    heightPx: Float = 0f,
): TapZone {
    if (widthPx <= 0f) return TapZone.MENU
    val ratio = hotspotRatio.coerceIn(0.05f, 0.45f)
    if (heightPx > 0f && y > heightPx * (1f - ratio)) return TapZone.NEXT
    return when {
        x < widthPx * ratio -> TapZone.PREVIOUS
        x > widthPx * (1f - ratio) -> TapZone.NEXT
        else -> TapZone.MENU
    }
}

enum class VolumeKeyDispatch { PAGE_PREV, PAGE_NEXT, SCROLL_BACK, SCROLL_FORTH }

fun volumeKeyDispatch(volumeUp: Boolean, scrollMode: Boolean): VolumeKeyDispatch = when {
    scrollMode && volumeUp -> VolumeKeyDispatch.SCROLL_BACK
    scrollMode -> VolumeKeyDispatch.SCROLL_FORTH
    volumeUp -> VolumeKeyDispatch.PAGE_PREV
    else -> VolumeKeyDispatch.PAGE_NEXT
}

/** 页脚页码文本：totalPages 未就绪返回 null（调用方降级只显示百分比）；双页给 spread 区间。 */
fun pageNumberText(leftPage: Int, hasRightPage: Boolean, totalPages: Int): String? {
    if (totalPages <= 0 || leftPage <= 0) return null
    val left = leftPage.coerceAtMost(totalPages)
    return if (hasRightPage && left < totalPages) {
        "第 $left–${left + 1}/$totalPages 页"
    } else {
        "第 $left/$totalPages 页"
    }
}

fun inChapterFraction(chapters: List<Chapter>, index: Int, anchor: Long): Float {
    val ch = chapters.getOrNull(index) ?: return -1f
    val span = ch.charEnd - ch.charStart
    if (span <= 0L) return -1f
    return ((anchor - ch.charStart).toFloat() / span).coerceIn(0f, 1f)
}

/** 章节进度文本：无目录（<=1 章）返回 null；inChapter < 0 时只给章序号。 */
fun chapterProgressText(index: Int, count: Int, inChapter: Float): String? {
    if (count <= 1) return null
    val base = "第 ${(index + 1).coerceIn(1, count)}/$count 章"
    return if (inChapter >= 0f) "$base · ${formatPercent(inChapter)}" else base
}

/**
 * 是否需要兜底补扫章节。
 *
 * TXT 的 `parseChapters` 是一次全量索引扫描，实时索引正在跑时再排一遍纯属浪费；
 * EPUB/FB2 只读压平产物旁的 `.toc` sidecar，很便宜。而章节只在「本次确实新压平」时才回填，
 * 一旦那次写库失败（异常此前被静默吞掉），用户看到的就是「没有目录」，
 * 且要等下一次打开才可能补上——所以这两种格式必须照常兜底，在本次打开内自愈。
 */
fun shouldScanChaptersInBackground(liveIndexing: Boolean, cheapChapterScan: Boolean): Boolean =
    !liveIndexing || cheapChapterScan

/**
 * [offset] 所属章节序号：取最后一个 `charStart <= offset` 的章节（章节按 charStart 升序）。
 * 二分查找：原实现用 `indexOfLast` 线性扫，滚动时每翻一页要调 4 次、搜索分组时每个命中调 1 次，
 * 章节多时是主线程热点。offset 落在首章之前时返回 0（与原 `coerceAtLeast(0)` 一致）。
 */
fun chapterIndexAt(chapters: List<Chapter>, offset: Long): Int {
    if (chapters.isEmpty()) return 0
    var lo = 0
    var hi = chapters.lastIndex
    var found = 0
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (chapters[mid].charStart <= offset) {
            found = mid
            lo = mid + 1
        } else {
            hi = mid - 1
        }
    }
    return found
}

fun progressPercentOf(offset: Long, totalChars: Long): Float =
    if (totalChars <= 0L) 0f else (offset.toFloat() / totalChars).coerceIn(0f, 1f)

fun formatPercent(fraction: Float): String =
    "%.1f%%".format(fraction.coerceIn(0f, 1f) * 100f)

fun marginDpFor(level: Int): Pair<Float, Float> = when (level.coerceIn(0, 2)) {
    0 -> 8f to 12f
    2 -> 24f to 36f
    else -> 16f to 24f
}

/** 页宽较窄时把行长上限收敛到页宽可容纳字数（不设下限，由调用方保证 >=1）。 */
fun capMaxLineChars(
    userMaxLineChars: Int,
    pageWidthPx: Float,
    horizontalMarginsPx: Float,
    fontSizePx: Float,
): Int {
    if (fontSizePx <= 0f) return userMaxLineChars
    val fitChars = ((pageWidthPx - horizontalMarginsPx) / fontSizePx).toInt()
    return minOf(userMaxLineChars, fitChars).coerceAtLeast(1)
}

package com.llzx373.foldreader.feature.reader

import androidx.compose.ui.geometry.Rect
import com.llzx373.foldreader.core.data.settings.DualPageMode
import com.llzx373.foldreader.core.data.settings.PdfReadingMode
import com.llzx373.foldreader.core.data.settings.TapAction
import com.llzx373.foldreader.core.foldable.FoldingPosture
import com.llzx373.foldreader.core.foldable.HingeOrientation
import com.llzx373.foldreader.core.foldable.Posture
import com.llzx373.foldreader.core.foldable.WidthCategory
import com.llzx373.foldreader.core.format.Chapter
import com.llzx373.foldreader.core.reader.PageAvoidance
import kotlin.math.abs

/**
 * 点击分区。中间区（[MIDDLE]）不是一个动作，而是一块**可配置**的区域：
 * 单击与双击各绑一个 [TapAction]，默认分别是「开关菜单」与「切换缩放」。
 */
enum class TapZone { PREVIOUS, MIDDLE, NEXT }

/**
 * 亮度手势的热区宽度（占屏宽比例）：只在屏幕最左侧这一条内起手才调到亮度。
 *
 * 原来是左 1/3，但左 1/3 同时也是「上一页」点击区。竖向滑动现在要按点击区解析动作
 * （见各阅读器的竖向手势），两个热区完全重叠会让左页竖滑永远只能调亮度、翻不了页。
 * 收窄到 1/6 之后，最左侧边缘仍可调亮度，其余左区竖滑照常回到上一页。
 */
const val BRIGHTNESS_EDGE_FRACTION = 1f / 6f

/**
 * 这次竖向手势是否算亮度手势：只看**起手点**与开关。
 *
 * 必须在起手时一次定死（而不是每帧看当前 x），否则抬手时无法回答"这次到底算不算调亮度"，
 * 也就没法把"非亮度"的竖向滑动交给点击区动作。两个阅读器共用同一份判定，避免各有各的边界。
 */
fun isBrightnessGesture(
    startX: Float,
    widthPx: Float,
    enabled: Boolean,
): Boolean = enabled && widthPx > 0f && startX <= widthPx * BRIGHTNESS_EDGE_FRACTION

/**
 * 某阅读器能否执行该动作。
 *
 * 用途不只是禁用菜单项：中间区只要配了双击动作就得挂一层点击层，而点击层会让**单击**
 * 也等一个双击超时。所以文本阅读器遇到自己不支持的动作（如 [TapAction.TOGGLE_ZOOM]）
 * 必须整层不挂，才能保住既有的零延迟点击。
 */
fun supportsTapAction(action: TapAction, paged: Boolean): Boolean = when (action) {
    TapAction.NONE -> false
    TapAction.TOGGLE_ZOOM -> paged
    else -> true
}

/**
 * 两次按下是否构成双击。
 *
 * 只服务中间区：左右翻页区不做双击等待（让每次单击都等一个双击超时会直接毁掉翻页跟手性）。
 */
fun isDoubleTap(
    firstX: Float,
    firstY: Float,
    secondX: Float,
    secondY: Float,
    elapsedMs: Long,
    timeoutMs: Long,
    slopPx: Float,
): Boolean {
    if (elapsedMs < 0L || elapsedMs > timeoutMs) return false
    return kotlin.math.abs(secondX - firstX) <= slopPx && kotlin.math.abs(secondY - firstY) <= slopPx
}

/** 中间区动作解析：双击动作未配置（[TapAction.NONE]）时，永远退回单击动作。 */
fun resolveMiddleTap(single: TapAction, double: TapAction, isDouble: Boolean): TapAction =
    if (isDouble && double != TapAction.NONE) double else single

enum class PageLayoutMode { SINGLE, DUAL }

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

/**
 * 单页 / 双页判定。
 *
 * [windowPortrait] = 窗口竖向（高 > 宽）。竖持时左右并排的两页各窄成一条，双页书式在物理上
 * 不成立，故自动模式一律单页——阔折叠展开后竖着拿正属此列：它上报水平铰链，且竖持宽度常常
 * 仍在 EXPANDED 断点之上，只看姿态或宽度类别都会误判成双页。窗口宽 ≥ 高（横持）才进双页。
 * "强制双页"是用户的显式选择，不受方向限制。
 */
fun resolvePageLayoutMode(
    posture: FoldingPosture,
    widthCategory: WidthCategory,
    windowPortrait: Boolean,
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
                windowPortrait -> PageLayoutMode.SINGLE
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

/** 热区比例的合法区间：太小点不准，太大就没有中间区了。 */
private fun hotspotRatioClamped(hotspotRatio: Float): Float = hotspotRatio.coerceIn(0.05f, 0.45f)

/**
 * 中间区矩形（相对阅读器根容器的 px）。
 *
 * 它就是「点击后判定为 [TapZone.MIDDLE] 的那块区域」——中间点击层照它铺，才能保证：
 * 左右翻页区（以及启用时的底边翻页条）不被点击层覆盖（那两处一旦被覆盖，单击就要等一个双击超时）。
 * 因此这里的几何必须与 [tapZoneOf] 完全一致，改一个就得改另一个。
 *
 * [bottomStripEnabled] 必须与调用方传给 [tapZoneOf] 的口径一致：有底边翻页条时那一条判的是
 * NEXT，中间区就不到屏幕底；漫画阅读器不用底边条（底边随左右热区分区），中间区就是整高。
 */
fun middleZoneRect(
    widthPx: Float,
    heightPx: Float,
    hotspotRatio: Float,
    bottomStripEnabled: Boolean = true,
): ContentRect {
    val ratio = hotspotRatioClamped(hotspotRatio)
    val left = widthPx * ratio
    val right = widthPx * (1f - ratio)
    val bottom = if (bottomStripEnabled) heightPx * (1f - ratio) else heightPx
    return ContentRect(
        left = left,
        top = 0f,
        width = (right - left).coerceAtLeast(0f),
        height = bottom.coerceAtLeast(0f),
    )
}

/** 是否落在底边翻页条内：那一条恒为「下一页」，与左右分区无关，也与阅读方向无关。 */
fun isBottomPagingStrip(y: Float, heightPx: Float, hotspotRatio: Float): Boolean {
    if (heightPx <= 0f) return false
    return y > heightPx * (1f - hotspotRatioClamped(hotspotRatio))
}

fun tapZoneOf(
    x: Float,
    widthPx: Float,
    hotspotRatio: Float,
    y: Float = -1f,
    heightPx: Float = 0f,
): TapZone {
    if (widthPx <= 0f) return TapZone.MIDDLE
    val ratio = hotspotRatioClamped(hotspotRatio)
    // 底边整条都是「下一页」：单手拇指够得着，与左右分区无关
    if (isBottomPagingStrip(y, heightPx, hotspotRatio)) return TapZone.NEXT
    return when {
        x < widthPx * ratio -> TapZone.PREVIOUS
        x > widthPx * (1f - ratio) -> TapZone.NEXT
        else -> TapZone.MIDDLE
    }
}

/**
 * 横滑方向判据：返回 true = 向左滑（内容向左拖），null = 不翻页。
 *
 * 只看位移时，「正常滑一下」常常落在触摸 slop 与阈值之间的死区里——手势在、却毫无反应。
 * 原先的阈值是屏宽的 15%（展开态约 110–130dp，远超拇指一次自然滑动），所以补上**速度**判据：
 * 位移过 [distanceThresholdPx]，或甩速过 [flingVelocityPxPerSec]，任一成立即翻页。
 * 位移够长时按位移定方向，否则按速度定方向（短促轻甩看的是脱手瞬间的朝向）。
 *
 * 两个阈值都以 dp 为单位（用户可在设置里调，见 `ReadingPreferences.swipeDistanceDp` /
 * `swipeFlingVelocityDpPerSec`），调用方按密度换算成 px。
 */
fun horizontalSwipeDirection(
    draggedPx: Float,
    velocityXPxPerSec: Float,
    distanceThresholdPx: Float,
    flingVelocityPxPerSec: Float,
): Boolean? = when {
    abs(draggedPx) >= distanceThresholdPx -> draggedPx < 0f
    abs(velocityXPxPerSec) >= flingVelocityPxPerSec -> velocityXPxPerSec < 0f
    else -> null
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

/**
 * PDF 用哪个阅读器。
 *
 * 用户手动定过就听用户的；没定过则看文档本身：抽得出正文就当电子书读
 * （可重排、可调字号、能全文搜索），扫描件只能按页渲染。
 * 这只是**默认值**——两种模式随时可切，不是给这本书判了刑。
 */
fun resolvePdfReadingMode(preference: PdfReadingMode?, hasExtractedText: Boolean): PdfReadingMode =
    preference ?: if (hasExtractedText) PdfReadingMode.TEXT else PdfReadingMode.PAGED

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

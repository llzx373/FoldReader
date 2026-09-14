package com.llzx373.foldreader.feature.reader

import androidx.compose.ui.geometry.Rect
import com.llzx373.foldreader.core.data.settings.DualPageMode
import com.llzx373.foldreader.core.foldable.FoldingPosture
import com.llzx373.foldreader.core.foldable.HingeOrientation
import com.llzx373.foldreader.core.foldable.Posture
import com.llzx373.foldreader.core.foldable.WidthCategory
import com.llzx373.foldreader.core.format.Chapter

enum class TapZone { PREVIOUS, MENU, NEXT }

enum class PageLayoutMode { SINGLE, DUAL }

data class ContentRect(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
)

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
): PageLayoutMode = when (pref) {
    DualPageMode.FORCE_SINGLE -> PageLayoutMode.SINGLE
    DualPageMode.FORCE_DUAL -> PageLayoutMode.DUAL
    DualPageMode.AUTO -> {
        val bookOpen = posture.posture == Posture.FLAT &&
            posture.hingeOrientation == HingeOrientation.VERTICAL &&
            (posture.hingeBounds?.width ?: 0f) > 0f
        if (bookOpen || widthCategory == WidthCategory.EXPANDED) {
            PageLayoutMode.DUAL
        } else {
            PageLayoutMode.SINGLE
        }
    }
}

fun tapZoneOf(x: Float, widthPx: Float, hotspotRatio: Float): TapZone {
    if (widthPx <= 0f) return TapZone.MENU
    val ratio = hotspotRatio.coerceIn(0.05f, 0.45f)
    return when {
        x < widthPx * ratio -> TapZone.PREVIOUS
        x > widthPx * (1f - ratio) -> TapZone.NEXT
        else -> TapZone.MENU
    }
}

fun chapterIndexAt(chapters: List<Chapter>, offset: Long): Int =
    chapters.indexOfLast { offset >= it.charStart }.coerceAtLeast(0)

fun progressPercentOf(offset: Long, totalChars: Long): Float =
    if (totalChars <= 0L) 0f else (offset.toFloat() / totalChars).coerceIn(0f, 1f)

fun formatPercent(fraction: Float): String =
    "%.1f%%".format(fraction.coerceIn(0f, 1f) * 100f)

fun marginDpFor(level: Int): Pair<Float, Float> = when (level.coerceIn(0, 2)) {
    0 -> 8f to 12f
    2 -> 24f to 36f
    else -> 16f to 24f
}

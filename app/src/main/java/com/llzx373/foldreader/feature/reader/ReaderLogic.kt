package com.llzx373.foldreader.feature.reader

import com.llzx373.foldreader.core.format.Chapter

enum class TapZone { PREVIOUS, MENU, NEXT }

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

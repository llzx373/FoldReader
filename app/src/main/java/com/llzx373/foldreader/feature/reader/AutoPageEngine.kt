package com.llzx373.foldreader.feature.reader

import com.llzx373.foldreader.core.data.settings.AutoPageMode

val AUTO_PAGE_INTERVAL_STEPS = listOf(3, 5, 8, 10, 15, 20, 30)
val AUTO_PAGE_SPEED_STEPS = listOf(30f, 45f, 60f, 90f, 120f, 150f)

fun nextAutoPageIntervalSec(current: Int): Int {
    val exact = AUTO_PAGE_INTERVAL_STEPS.indexOf(current)
    if (exact >= 0) {
        return if (exact == AUTO_PAGE_INTERVAL_STEPS.lastIndex) {
            AUTO_PAGE_INTERVAL_STEPS.first()
        } else {
            AUTO_PAGE_INTERVAL_STEPS[exact + 1]
        }
    }
    return AUTO_PAGE_INTERVAL_STEPS.firstOrNull { it >= current } ?: AUTO_PAGE_INTERVAL_STEPS.first()
}

fun nextAutoPageSpeedPx(current: Float): Float {
    val exact = AUTO_PAGE_SPEED_STEPS.indexOf(current)
    if (exact >= 0) {
        return if (exact == AUTO_PAGE_SPEED_STEPS.lastIndex) {
            AUTO_PAGE_SPEED_STEPS.first()
        } else {
            AUTO_PAGE_SPEED_STEPS[exact + 1]
        }
    }
    return AUTO_PAGE_SPEED_STEPS.firstOrNull { it >= current } ?: AUTO_PAGE_SPEED_STEPS.first()
}

fun nextAutoPageMode(mode: AutoPageMode): AutoPageMode = when (mode) {
    AutoPageMode.INTERVAL -> AutoPageMode.SCROLL
    AutoPageMode.SCROLL -> AutoPageMode.INTERVAL
}

fun autoPageModeLabel(mode: AutoPageMode): String = when (mode) {
    AutoPageMode.INTERVAL -> "间隔"
    AutoPageMode.SCROLL -> "滚动"
}

fun autoPageSpeedLabel(prefs: com.llzx373.foldreader.core.data.settings.ReadingPreferences): String =
    when (prefs.autoPageMode) {
        AutoPageMode.INTERVAL -> "${prefs.autoPageIntervalSec} 秒/页"
        AutoPageMode.SCROLL -> "${prefs.autoPageSpeedPx.toInt()} px/s"
    }

class AutoPageClock(
    private val resumeAfterManualMs: Long = 10_000L,
) {
    private var manualPauseUntilMs = 0L

    fun noteManualInteraction(nowMs: Long) {
        manualPauseUntilMs = nowMs + resumeAfterManualMs
    }

    fun isManualPaused(nowMs: Long): Boolean = nowMs < manualPauseUntilMs

    fun shouldRun(enabled: Boolean, uiPaused: Boolean, nowMs: Long): Boolean =
        enabled && !uiPaused && !isManualPaused(nowMs)
}

data class AutoPageStatus(
    val enabled: Boolean = false,
    val paused: Boolean = false,
)

fun maxAutoScrollPx(
    lineCount: Int,
    paragraphBreaks: Int,
    lineHeightPx: Float,
    paragraphSpacingPx: Float,
    marginTopPx: Float,
    marginBottomPx: Float,
    viewportHeightPx: Int,
): Float {
    val content = marginTopPx + lineCount * lineHeightPx +
        paragraphBreaks * paragraphSpacingPx + marginBottomPx
    return (content - viewportHeightPx).coerceAtLeast(0f)
}

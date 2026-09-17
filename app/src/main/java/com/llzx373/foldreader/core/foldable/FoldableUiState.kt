package com.llzx373.foldreader.core.foldable

import androidx.window.core.layout.WindowSizeClass

enum class WidthCategory { COMPACT, MEDIUM, EXPANDED }

data class FoldableUiState(
    val posture: FoldingPosture,
    val windowSizeClass: WindowSizeClass,
    /** 窗口竖向（高 > 宽）。双页书式要求窗口横向，见 [isPortraitWindow]。 */
    val windowPortrait: Boolean,
) {
    val widthCategory: WidthCategory
        get() = when {
            windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND) ->
                WidthCategory.EXPANDED
            windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND) ->
                WidthCategory.MEDIUM
            else -> WidthCategory.COMPACT
        }
}

/**
 * 窗口是否竖向（高 > 宽）。
 *
 * 注意**不能**拿 [WindowSizeClass] 的 `minWidthDp` / `minHeightDp` 推断方向：那两个值是"不超过
 * 真实尺寸的最大断点"（宽 0/600/840/1200/1600、高 0/480/900），真实尺寸已被丢弃——
 * 阔折叠竖持 608×860dp 会落成 600×480，反倒判成横向。方向只能取自真实窗口尺寸。
 */
fun isPortraitWindow(widthDp: Int, heightDp: Int): Boolean = heightDp > widthDp

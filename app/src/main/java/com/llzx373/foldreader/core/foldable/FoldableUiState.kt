package com.llzx373.foldreader.core.foldable

import androidx.window.core.layout.WindowSizeClass

enum class WidthCategory { COMPACT, MEDIUM, EXPANDED }

data class FoldableUiState(
    val posture: FoldingPosture,
    val windowSizeClass: WindowSizeClass,
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

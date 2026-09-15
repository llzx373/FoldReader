package com.llzx373.foldreader.ui.theme

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Typography

// Material Design 3 Expressive：标题/展示类采用 emphasized 字阶，正文保持标准字阶
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
val Typography = with(Typography()) {
    Typography(
        displayLarge = displayLargeEmphasized,
        displayMedium = displayMediumEmphasized,
        displaySmall = displaySmallEmphasized,
        headlineLarge = headlineLargeEmphasized,
        headlineMedium = headlineMediumEmphasized,
        headlineSmall = headlineSmallEmphasized,
        titleLarge = titleLargeEmphasized,
        titleMedium = titleMediumEmphasized,
        titleSmall = titleSmallEmphasized,
    )
}

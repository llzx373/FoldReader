package com.llzx373.foldreader.core.reader

import android.graphics.Typeface

enum class PageTextAlignment { JUSTIFY, LEFT }

data class LayoutConfig(
    val fontSizeSp: Float = 18f,
    val lineSpacingMultiplier: Float = 1.5f,
    val letterSpacingEm: Float = 0f,
    val paragraphSpacingEm: Float = 0.4f,
    val marginLeftDp: Float = 16f,
    val marginTopDp: Float = 24f,
    val marginRightDp: Float = 16f,
    val marginBottomDp: Float = 24f,
    val firstLineIndentChars: Int = 2,
    val maxLineChars: Int = 40,
    val alignment: PageTextAlignment = PageTextAlignment.JUSTIFY,
    val fontKey: String? = null,
    val typeface: Typeface? = null,
)

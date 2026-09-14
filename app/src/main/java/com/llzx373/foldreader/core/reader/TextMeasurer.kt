package com.llzx373.foldreader.core.reader

import android.graphics.Typeface

interface TextMeasurer {
    fun measureLineBreaks(
        text: CharSequence,
        widthPx: Int,
        indentPx: Int,
        fontSizePx: Float,
        letterSpacingEm: Float,
        typeface: Typeface?,
    ): IntArray
}

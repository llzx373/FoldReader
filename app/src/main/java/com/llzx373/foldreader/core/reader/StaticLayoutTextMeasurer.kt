package com.llzx373.foldreader.core.reader

import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.LeadingMarginSpan

class StaticLayoutTextMeasurer : TextMeasurer {

    override fun measureLineBreaks(
        text: CharSequence,
        widthPx: Int,
        indentPx: Int,
        fontSizePx: Float,
        letterSpacingEm: Float,
        typeface: Typeface?,
    ): IntArray {
        if (text.isEmpty()) return IntArray(0)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = fontSizePx
            letterSpacing = letterSpacingEm
            this.typeface = typeface ?: Typeface.DEFAULT
        }
        val measured: CharSequence = if (indentPx > 0) {
            SpannableString(text).apply {
                setSpan(
                    LeadingMarginSpan.Standard(indentPx, 0),
                    0,
                    length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        } else {
            text
        }
        val layout = StaticLayout.Builder.obtain(measured, 0, measured.length, paint, widthPx)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .build()
        return IntArray(layout.lineCount) { layout.getLineEnd(it) }
    }
}

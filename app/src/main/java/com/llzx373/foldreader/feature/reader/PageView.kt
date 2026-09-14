package com.llzx373.foldreader.feature.reader

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.llzx373.foldreader.core.reader.LayoutConfig
import com.llzx373.foldreader.core.reader.Page
import com.llzx373.foldreader.core.reader.PageTextAlignment

@Composable
fun PageView(
    page: Page,
    config: LayoutConfig,
    colors: ReaderColors,
    modifier: Modifier = Modifier,
    innerPaddingPx: Float = 0f,
    innerOnRight: Boolean = true,
) {
    val density = LocalDensity.current.density
    val scaledDensity = density * LocalDensity.current.fontScale
    Canvas(modifier = modifier) {
        val fontSizePx = config.fontSizeSp * scaledDensity
        val lineHeightPx = fontSizePx * config.lineSpacingMultiplier
        val paragraphSpacingPx = fontSizePx * config.paragraphSpacingEm
        val indentPx = config.firstLineIndentChars * fontSizePx
        val topPx = config.marginTopDp * density
        val leftPad = page.paddingLeft + if (innerOnRight) 0f else innerPaddingPx
        val rightPad = page.paddingRight + if (innerOnRight) innerPaddingPx else 0f
        val textWidthPx = size.width - leftPad - rightPad

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = fontSizePx
            color = colors.text.toArgb()
            config.typeface?.let { typeface = it }
        }
        val fm = paint.fontMetrics
        val canvas = drawContext.canvas.nativeCanvas

        var yTop = topPx
        page.lines.forEachIndexed { index, line ->
            if (line.isParagraphStart && index > 0) yTop += paragraphSpacingPx
            val baseline = yTop + (lineHeightPx - fm.descent - fm.ascent) / 2f
            val x0 = leftPad + if (line.isParagraphStart) indentPx else 0f
            if (line.text.isNotEmpty()) {
                val justify = config.alignment == PageTextAlignment.JUSTIFY &&
                    !line.isParagraphEnd && line.text.length > 1
                if (justify) {
                    val natural = paint.measureText(line.text)
                    val gap = ((textWidthPx - (x0 - leftPad) - natural) /
                        (line.text.length - 1)).coerceAtLeast(0f)
                    var x = x0
                    for (ch in line.text) {
                        canvas.drawText(ch.toString(), x, baseline, paint)
                        x += paint.measureText(ch.toString()) + gap
                    }
                } else {
                    canvas.drawText(line.text, x0, baseline, paint)
                }
            }
            yTop += lineHeightPx
        }
    }
}

@Composable
fun ReaderHeader(
    chapterTitle: String,
    colors: ReaderColors,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.Start,
    ) {
        Text(
            text = chapterTitle,
            style = MaterialTheme.typography.labelSmall,
            color = colors.text.copy(alpha = 0.55f),
            maxLines = 1,
        )
    }
}

@Composable
fun ReaderFooter(
    progressText: String?,
    batteryText: String?,
    timeText: String?,
    colors: ReaderColors,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = progressText.orEmpty(),
            style = MaterialTheme.typography.labelSmall,
            color = colors.text.copy(alpha = 0.55f),
        )
        Text(
            text = listOfNotNull(batteryText, timeText).joinToString("  "),
            style = MaterialTheme.typography.labelSmall,
            color = colors.text.copy(alpha = 0.55f),
        )
    }
}

@Composable
fun SpineOverlay(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val mid = w / 2f
        val shadowWidth = minOf(16.dp.toPx(), mid)
        drawRect(
            brush = androidx.compose.ui.graphics.Brush.horizontalGradient(
                listOf(Color.Transparent, Color.Black.copy(alpha = 0.10f)),
                startX = mid - shadowWidth,
                endX = mid,
            ),
            topLeft = androidx.compose.ui.geometry.Offset(mid - shadowWidth, 0f),
            size = androidx.compose.ui.geometry.Size(shadowWidth, h),
        )
        drawRect(
            brush = androidx.compose.ui.graphics.Brush.horizontalGradient(
                listOf(Color.Black.copy(alpha = 0.10f), Color.Transparent),
                startX = mid,
                endX = mid + shadowWidth,
            ),
            topLeft = androidx.compose.ui.geometry.Offset(mid, 0f),
            size = androidx.compose.ui.geometry.Size(shadowWidth, h),
        )
        drawLine(
            color = Color.Black.copy(alpha = 0.18f),
            start = androidx.compose.ui.geometry.Offset(mid, 0f),
            end = androidx.compose.ui.geometry.Offset(mid, h),
            strokeWidth = 1.dp.toPx(),
        )
    }
}

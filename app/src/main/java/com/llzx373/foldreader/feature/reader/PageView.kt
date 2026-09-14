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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.llzx373.foldreader.core.reader.LayoutConfig
import com.llzx373.foldreader.core.reader.LineBox
import com.llzx373.foldreader.core.reader.Page
import com.llzx373.foldreader.core.reader.PageTextAlignment
import com.llzx373.foldreader.core.reader.buildLineBoxes
import com.llzx373.foldreader.core.reader.segmentsForRange

/** 页内高亮片段：字符区间 [start, endExclusive) + 颜色（标注底色 / 选区高亮共用）。 */
data class TextRangeSpan(
    val start: Long,
    val endExclusive: Long,
    val color: Color,
)

@Composable
fun PageView(
    page: Page,
    config: LayoutConfig,
    colors: ReaderColors,
    modifier: Modifier = Modifier,
    innerPaddingPx: Float = 0f,
    innerOnRight: Boolean = true,
    highlights: List<TextRangeSpan> = emptyList(),
    selection: TextRangeSpan? = null,
    onGeometry: (List<LineBox>) -> Unit = {},
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

        val boxes = buildLineBoxes(
            page = page,
            lineHeightPx = lineHeightPx,
            paragraphSpacingPx = paragraphSpacingPx,
            indentPx = indentPx,
            topPadPx = topPx,
            leftPadPx = leftPad,
            textWidthPx = textWidthPx,
            justify = config.alignment == PageTextAlignment.JUSTIFY,
            measure = { paint.measureText(it) },
        )
        onGeometry(boxes)

        // 标注底色（半透明，文字下方）
        highlights.forEach { span ->
            segmentsForRange(boxes, span.start, span.endExclusive).forEach { seg ->
                drawRoundRect(
                    color = span.color.copy(alpha = 0.30f),
                    topLeft = Offset(seg.xStart, seg.yTop + lineHeightPx * 0.08f),
                    size = Size(seg.xEnd - seg.xStart, seg.lineHeightPx * 0.84f),
                    cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
                )
            }
        }
        // 选区高亮
        if (selection != null) {
            segmentsForRange(boxes, selection.start, selection.endExclusive).forEach { seg ->
                drawRect(
                    color = selection.color,
                    topLeft = Offset(seg.xStart, seg.yTop),
                    size = Size(seg.xEnd - seg.xStart, seg.lineHeightPx),
                )
            }
        }

        boxes.forEach { box ->
            val line = box.line
            if (line.text.isEmpty()) return@forEach
            val baseline = box.yTop + (lineHeightPx - fm.descent - fm.ascent) / 2f
            if (box.gapPx > 0f) {
                for (i in line.text.indices) {
                    canvas.drawText(line.text[i].toString(), box.boundaryX(i), baseline, paint)
                }
            } else {
                canvas.drawText(line.text, box.x0, baseline, paint)
            }
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

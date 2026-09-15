package com.llzx373.foldreader.feature.reader

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
import com.llzx373.foldreader.core.reader.LineBox
import com.llzx373.foldreader.core.reader.Page
import com.llzx373.foldreader.core.reader.drawPageInto

/** 页内高亮片段：字符区间 [start, endExclusive) + 颜色（标注底色 / 下划线 / 选区高亮共用）。 */
data class TextRangeSpan(
    val start: Long,
    val endExclusive: Long,
    val color: Color,
    val underline: Boolean = false,
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
        val boxes = drawPageInto(
            canvas = drawContext.canvas.nativeCanvas,
            page = page,
            config = config,
            textColorArgb = colors.text.toArgb(),
            density = density,
            scaledDensity = scaledDensity,
            widthPx = size.width,
            innerPaddingPx = innerPaddingPx,
            innerOnRight = innerOnRight,
            highlights = highlights,
            selection = selection,
        )
        onGeometry(boxes)
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
    leftText: String?,
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
            text = leftText.orEmpty(),
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

package com.llzx373.foldreader.feature.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.llzx373.foldreader.core.reader.LineBox
import kotlin.math.roundToInt

/**
 * 选择状态：锚点（按下点）+ 当前光标（拖动点），绝对字符偏移，可跨页。
 * start/end 为排序后的起止；端点落在哪一页由渲染方按页区间求交决定。
 */
data class SelectionUi(
    val anchor: Long,
    val caret: Long,
    val dragging: Boolean = true,
) {
    val start: Long get() = minOf(anchor, caret)
    val end: Long get() = maxOf(anchor, caret)
}

/** 行框中 [offset] 字符左边缘的手柄位（行底部）；越界时钳到首行首/末行尾。 */
fun handlePosition(boxes: List<LineBox>, offset: Long): Offset? {
    if (boxes.isEmpty()) return null
    val box = boxes.firstOrNull {
        offset >= it.line.charStart && offset <= it.line.charStart + it.textLength
    } ?: if (offset < boxes.first().line.charStart) boxes.first() else boxes.last()
    val index = (offset - box.line.charStart).toInt().coerceIn(0, box.textLength)
    return Offset(box.boundaryX(index), box.yBottom)
}

/**
 * 单个选择手柄：圆点，可拖动调整选区端点。
 * 坐标换算：手柄中心画在行底下方，命中时把 y 抬回行内半行高处。
 */
@Composable
fun SelectionHandle(
    boxes: List<LineBox>,
    offset: Long,
    originXPx: Float,
    originYPx: Float,
    scrollYPx: Float,
    accent: Color,
    onDrag: (pageLocal: Offset) -> Unit,
) {
    val density = LocalDensity.current
    val touchPx = with(density) { 28.dp.toPx() }
    val halfLine = boxes.firstOrNull()?.lineHeightPx?.div(2f) ?: 0f
    val pos = handlePosition(boxes, offset) ?: return
    // 手柄中心跟随选区重算；拖动期间以本地累计位置为准
    var dragCenter by remember(pos) { mutableStateOf(Offset.Unspecified) }
    val actual = dragCenter.takeIf { it != Offset.Unspecified } ?: pos
    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    (originXPx + actual.x - touchPx / 2f).roundToInt(),
                    (originYPx + actual.y - scrollYPx + 4.dp.toPx() - touchPx / 2f).roundToInt(),
                )
            }
            .size(28.dp)
            .pointerInput(pos) {
                detectDragGestures(
                    onDragStart = { dragCenter = pos },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        dragCenter =
                            (dragCenter.takeIf { it != Offset.Unspecified } ?: pos) + dragAmount
                        onDrag(Offset(dragCenter.x, dragCenter.y - halfLine))
                    },
                    onDragEnd = { dragCenter = Offset.Unspecified },
                    onDragCancel = { dragCenter = Offset.Unspecified },
                )
            },
    ) {
        Canvas(modifier = Modifier.size(28.dp)) {
            val r = 6.dp.toPx()
            drawCircle(color = accent, radius = r)
            drawCircle(
                color = Color.White.copy(alpha = 0.9f),
                radius = r,
                style = Stroke(width = 1.5.dp.toPx()),
            )
        }
    }
}

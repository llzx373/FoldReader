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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
 *
 * pos 会随拖动实时重算（选区端点跟着手指走），所以它绝不能做 remember /
 * pointerInput 的 key：手指每跨过一个字符 key 就变，手势协程重启后干等下一次
 * 按下，本次拖动当场死掉（表现为"拖一两个字就卡住、不跟手"）。协程常驻，
 * 最新值一律经 rememberUpdatedState 读取。
 */
@Composable
fun SelectionHandle(
    boxes: List<LineBox>,
    offset: Long,
    originXPx: Float,
    originYPx: Float,
    scrollYPx: Float,
    accent: Color,
    /** M35：TalkBack 语义标签（「选区起点」/「选区终点」）——手柄是纯绘制圆点，无语义不可达。 */
    label: String,
    onDrag: (pageLocal: Offset) -> Unit,
    /** 松手/取消回调：滚动模式靠它停掉手柄拖动触发的边缘自动滚动。 */
    onDragEnd: () -> Unit = {},
) {
    val density = LocalDensity.current
    val touchPx = with(density) { 44.dp.toPx() }
    val pos = handlePosition(boxes, offset) ?: return
    val currentPos by rememberUpdatedState(pos)
    val halfLine by rememberUpdatedState(boxes.firstOrNull()?.lineHeightPx?.div(2f) ?: 0f)
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)
    // 拖动期间以本地累计位置为准（跟随手指），松手后回贴选区端点
    var dragCenter by remember { mutableStateOf(Offset.Unspecified) }
    Box(
        modifier = Modifier
            .offset {
                // 在 placement 里读状态：拖动逐帧只重摆位，不触发整次重组
                val actual = dragCenter.takeIf { it != Offset.Unspecified } ?: currentPos
                IntOffset(
                    (originXPx + actual.x - touchPx / 2f).roundToInt(),
                    (originYPx + actual.y - scrollYPx + 4.dp.toPx() - touchPx / 2f).roundToInt(),
                )
            }
            .size(44.dp)
            .semantics { contentDescription = label }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { dragCenter = currentPos },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        dragCenter =
                            (dragCenter.takeIf { it != Offset.Unspecified } ?: currentPos) + dragAmount
                        currentOnDrag(Offset(dragCenter.x, dragCenter.y - halfLine))
                    },
                    onDragEnd = {
                        dragCenter = Offset.Unspecified
                        currentOnDragEnd()
                    },
                    onDragCancel = {
                        dragCenter = Offset.Unspecified
                        currentOnDragEnd()
                    },
                )
            },
    ) {
        Canvas(modifier = Modifier.size(44.dp)) {
            val r = 7.dp.toPx()
            drawCircle(color = accent, radius = r)
            drawCircle(
                color = Color.White.copy(alpha = 0.9f),
                radius = r,
                style = Stroke(width = 1.5.dp.toPx()),
            )
        }
    }
}

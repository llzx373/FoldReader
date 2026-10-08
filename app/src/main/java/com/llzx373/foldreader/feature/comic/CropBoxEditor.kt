package com.llzx373.foldreader.feature.comic

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** 框选至少保留页面的这个比例（宽/高），再小就是误触。 */
private const val MIN_CROP_FRACTION = 0.05f

/**
 * 手动框选裁边（M32）：在未裁剪的页面上拖出一个矩形作为裁框。
 *
 * 坐标全程是**原页归一化**（与存储口径一致，见 MarginCrop.encodeNormalized）：
 * 底图是未裁剪位图，拖动两点围成矩形，松手即定型（可反复重拖）。
 * 「整页」= 关闭裁边（onApply(null)）；「应用」记忆裁框并启用。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CropBoxEditor(
    bitmap: Bitmap?,
    loading: Boolean,
    /** 已记忆的裁框（原页归一化 l,t,r,b）；null = 从未裁过，初始整页。 */
    initialBox: FloatArray?,
    /** null = 整页（关闭裁边）。 */
    onApply: (FloatArray?) -> Unit,
    onDismiss: () -> Unit,
) {
    var box by remember {
        mutableStateOf(
            initialBox?.takeIf { it.size == 4 }?.copyOf() ?: floatArrayOf(0f, 0f, 1f, 1f),
        )
    }
    Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                text = "拖动框选要保留的区域",
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    bitmap != null -> CropCanvas(bitmap, box) { box = it }
                    loading -> LoadingIndicator(modifier = Modifier.align(Alignment.Center))
                    else -> Text(
                        text = "无法渲染此页",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }
            Row(
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                TextButton(onClick = { onApply(null) }) { Text("整页（不裁剪）") }
                TextButton(onClick = onDismiss) { Text("取消") }
                TextButton(
                    onClick = { onApply(box.copyOf()) },
                    enabled = box.let { it[2] - it[0] >= MIN_CROP_FRACTION && it[3] - it[1] >= MIN_CROP_FRACTION },
                ) { Text("应用裁剪") }
            }
        }
    }
}

@Composable
private fun CropCanvas(bitmap: Bitmap, box: FloatArray, onBoxChange: (FloatArray) -> Unit) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    /** 位图在画布里的 contain 落位矩形（像素）。 */
    fun imageRect(): Rect {
        if (canvasSize.width <= 0 || canvasSize.height <= 0) return Rect.Zero
        val scale = minOf(
            canvasSize.width.toFloat() / bitmap.width,
            canvasSize.height.toFloat() / bitmap.height,
        )
        val w = bitmap.width * scale
        val h = bitmap.height * scale
        return Rect(
            left = (canvasSize.width - w) / 2f,
            top = (canvasSize.height - h) / 2f,
            right = (canvasSize.width + w) / 2f,
            bottom = (canvasSize.height + h) / 2f,
        )
    }

    /** 画布点 → 原页归一化；落在图外返回 null。 */
    fun normalizedAt(offset: Offset): Pair<Float, Float>? {
        val r = imageRect()
        if (r.width <= 0f || r.height <= 0f) return null
        val nx = (offset.x - r.left) / r.width
        val ny = (offset.y - r.top) / r.height
        if (nx < 0f || nx > 1f || ny < 0f || ny > 1f) return null
        return nx to ny
    }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { canvasSize = it }
            .pointerInput(bitmap) {
                // 拖动起点就是框的锚点角，松手定型；反复重拖直接重画
                var dragAnchor: Pair<Float, Float>? = null
                detectDragGestures(
                    onDragStart = { start ->
                        dragAnchor = normalizedAt(start)
                        dragAnchor?.let { onBoxChange(floatArrayOf(it.first, it.second, it.first, it.second)) }
                    },
                    onDrag = { change, _ ->
                        val anchor = dragAnchor ?: return@detectDragGestures
                        change.consume()
                        val current = normalizedAt(change.position) ?: return@detectDragGestures
                        onBoxChange(
                            floatArrayOf(
                                minOf(anchor.first, current.first),
                                minOf(anchor.second, current.second),
                                maxOf(anchor.first, current.first),
                                maxOf(anchor.second, current.second),
                            ),
                        )
                    },
                    onDragEnd = { dragAnchor = null },
                    onDragCancel = { dragAnchor = null },
                )
            },
    ) {
        val r = run {
            val scale = minOf(size.width / bitmap.width, size.height / bitmap.height)
            val w = bitmap.width * scale
            val h = bitmap.height * scale
            Rect((size.width - w) / 2f, (size.height - h) / 2f, (size.width + w) / 2f, (size.height + h) / 2f)
        }
        if (r.width <= 0f || r.height <= 0f) return@Canvas
        drawImage(
            image = image,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(bitmap.width, bitmap.height),
            dstOffset = IntOffset(r.left.roundToInt(), r.top.roundToInt()),
            dstSize = IntSize(r.width.roundToInt().coerceAtLeast(1), r.height.roundToInt().coerceAtLeast(1)),
        )
        val sel = Rect(
            left = r.left + box[0] * r.width,
            top = r.top + box[1] * r.height,
            right = r.left + box[2] * r.width,
            bottom = r.top + box[3] * r.height,
        )
        // 框外压暗，框内原样
        val dim = Color.Black.copy(alpha = 0.55f)
        drawRect(dim, topLeft = Offset(r.left, r.top), size = Size(r.width, sel.top - r.top))
        drawRect(dim, topLeft = Offset(r.left, sel.bottom), size = Size(r.width, r.bottom - sel.bottom))
        drawRect(dim, topLeft = Offset(r.left, sel.top), size = Size(sel.left - r.left, sel.height))
        drawRect(dim, topLeft = Offset(sel.right, sel.top), size = Size(r.right - sel.right, sel.height))
        // 框边 + 四角手柄
        drawRect(Color.White, topLeft = sel.topLeft, size = sel.size, style = Stroke(width = 3f))
        val handle = 20f
        for ((cx, cy) in listOf(
            sel.topLeft, Offset(sel.right, sel.top), Offset(sel.left, sel.bottom), Offset(sel.right, sel.bottom),
        )) {
            drawRect(
                Color.White,
                topLeft = Offset(cx - handle / 2, cy - handle / 2),
                size = Size(handle, handle),
            )
        }
    }
}

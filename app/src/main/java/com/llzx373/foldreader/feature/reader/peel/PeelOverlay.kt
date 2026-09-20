package com.llzx373.foldreader.feature.reader.peel

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt

/**
 * 盖在被掀那一叶上。后半段画布向对页伸出，纸背可以盖住另一叶。
 * 位图与叶同尺寸；[frame] 为叶内局部坐标。
 */
@Composable
fun PeelOverlay(
    frame: PeelFrame,
    leaf: PeelLeaf,
    current: Bitmap,
    next: Bitmap,
    background: Color,
    modifier: Modifier = Modifier,
    back: Bitmap? = null,
    extendLeft: Float = 0f,
    extendRight: Float = 0f,
) {
    val density = LocalDensity.current
    val totalW = (extendLeft + leaf.width + extendRight).coerceAtLeast(1f)
    val wDp = with(density) { totalW.toDp() }
    val hDp = with(density) { leaf.height.toDp() }
    val d = density.density
    Canvas(
        modifier = modifier
            .offset {
                IntOffset(
                    (leaf.originX - extendLeft).roundToInt(),
                    leaf.originY.roundToInt(),
                )
            }
            .size(wDp, hDp),
    ) {
        drawIntoCanvas { composeCanvas ->
            val native = composeCanvas.nativeCanvas
            native.save()
            native.translate(extendLeft, 0f)
            PeelRenderer.draw(
                canvas = native,
                frame = frame,
                leafWidth = leaf.width,
                leafHeight = leaf.height,
                current = current,
                next = next,
                backgroundArgb = background.toArgb(),
                density = d,
                back = back,
                extendLeft = extendLeft,
                extendRight = extendRight,
            )
            native.restore()
        }
    }
}

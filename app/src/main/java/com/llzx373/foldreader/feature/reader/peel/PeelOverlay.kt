package com.llzx373.foldreader.feature.reader.peel

import android.graphics.Bitmap
import android.graphics.Path
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

@Composable
fun PeelAnimationLayer(
    controller: PeelController,
    leftLeaf: PeelLeaf,
    rightLeaf: PeelLeaf?,
    current: Bitmap,
    next: Bitmap?,
    background: Color,
    back: Bitmap? = null,
    currentFit: PeelBitmapFit? = null,
    nextFit: PeelBitmapFit? = null,
    backFit: PeelBitmapFit? = null,
    spineOverlay: (@Composable (PeelFrame?, Path?) -> Unit)? = null,
) {
    val frame = controller.frame()
    val leaf = controller.leaf
    val geometry = if (frame != null && !current.isRecycled && next?.isRecycled != true) {
        val (extendLeft, extendRight) = peelFlapExtend(controller.corner, leaf, leftLeaf, rightLeaf)
        val geometry = PeelRenderer.renderGeometry(
            frame = frame,
            width = leaf.width,
            height = leaf.height,
            extendLeft = extendLeft,
            extendRight = extendRight,
        )
        PeelOverlay(
            frame = frame,
            leaf = leaf,
            current = current,
            next = next,
            background = background,
            back = back?.takeUnless { it.isRecycled },
            extendLeft = extendLeft,
            extendRight = extendRight,
            currentFit = currentFit,
            nextFit = nextFit,
            backFit = backFit,
            geometry = geometry,
        )
        geometry
    } else {
        null
    }
    if (spineOverlay != null) {
        val flap = geometry?.flap?.let { Path(it).apply { offset(leaf.originX, leaf.originY) } }
        spineOverlay(frame, flap)
    }
}

/**
 * 盖在被掀那一叶上。后半段画布向对页伸出，纸背可以盖住另一叶。
 * 电子书位图与叶同尺寸；漫画静图可以是解码缓存的引用，用 [currentFit] 等落位。
 * [next] 为空时下层只留纸色（落单缺栏）。[frame] 为叶内局部坐标。
 */
@Composable
fun PeelOverlay(
    frame: PeelFrame,
    leaf: PeelLeaf,
    current: Bitmap,
    next: Bitmap?,
    background: Color,
    modifier: Modifier = Modifier,
    back: Bitmap? = null,
    extendLeft: Float = 0f,
    extendRight: Float = 0f,
    currentFit: PeelBitmapFit? = null,
    nextFit: PeelBitmapFit? = null,
    backFit: PeelBitmapFit? = null,
    geometry: PeelRenderGeometry? = null,
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
                currentFit = currentFit,
                nextFit = nextFit,
                backFit = backFit,
                geometry = geometry,
            )
            native.restore()
        }
    }
}

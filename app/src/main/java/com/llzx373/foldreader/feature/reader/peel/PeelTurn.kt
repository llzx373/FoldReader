package com.llzx373.foldreader.feature.reader.peel

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

enum class PeelPhase { Drag, Complete, Cancel, AutoPlay }

/**
 * 仿真翻页状态机：跟手拖动、抬手完成/取消、点击自动播放。
 *
 * 触点一律是叶内局部坐标。自动播放用 [progress] 驱动 P0→P1→P2；
 * 后半段只钉装订边，页角翻到对页而不是从书上撕下来。
 * 拖动写 [dragTouch]（同步，好跟手）；完成/取消再动画 [touchX]/[touchY]。
 */
@Stable
class PeelController {
    var phase: PeelPhase? by mutableStateOf(null)
        private set
    var forward: Boolean by mutableStateOf(true)
        private set
    var corner: PeelCorner by mutableStateOf(PeelCorner.BOTTOM_RIGHT)
        private set
    var leaf: PeelLeaf by mutableStateOf(PeelLeaf(1f, 1f))
        private set
    var dragTouch: Offset? by mutableStateOf(null)
        private set
    /** 对页宽度：后半段装订圆放大，终帧才能盖住另一叶。单页为 0。 */
    var oppositeWidth: Float by mutableStateOf(0f)
        private set

    val progress = Animatable(0f)
    val touchX = Animatable(0f)
    val touchY = Animatable(0f)

    val busy: Boolean get() = phase != null

    fun frame(): PeelFrame? {
        val p = phase ?: return null
        val (touch, bindingOnly) = when (p) {
            PeelPhase.AutoPlay -> {
                val t = progress.value
                autoPlayTouch(t, corner, leaf.width, leaf.height, oppositeWidth) to autoPlayBindingOnly(t)
            }
            PeelPhase.Drag -> {
                val local = dragTouch ?: return null
                val crossed = (corner.isRight && local.x < 0f) || (!corner.isRight && local.x > leaf.width)
                local to crossed
            }
            PeelPhase.Complete -> Offset(touchX.value, touchY.value) to true
            PeelPhase.Cancel -> Offset(touchX.value, touchY.value) to false
        }
        return peelFrame(
            touchLocal = touch,
            corner = corner,
            width = leaf.width,
            height = leaf.height,
            bindingOnly = bindingOnly,
            oppositeWidth = oppositeWidth,
        )
    }

    fun reset() {
        phase = null
        dragTouch = null
        oppositeWidth = 0f
    }

    fun beginDrag(
        forward: Boolean,
        corner: PeelCorner,
        leaf: PeelLeaf,
        local: Offset,
        oppositeWidth: Float = 0f,
    ) {
        this.forward = forward
        this.corner = corner
        this.leaf = leaf
        this.oppositeWidth = oppositeWidth.coerceAtLeast(0f)
        this.phase = PeelPhase.Drag
        this.dragTouch = local
    }

    fun updateDrag(local: Offset) {
        if (phase != PeelPhase.Drag) return
        dragTouch = local
    }

    /**
     * 抬手：过线或甩得够快则飞向 P2 并返回 true（调用方随后 showSpread）；
     * 否则退回 P0 并返回 false。
     */
    suspend fun endDrag(
        velocityX: Float,
        velocityY: Float,
        completeDistancePx: Float = 0f,
    ): Boolean {
        if (phase != PeelPhase.Drag) {
            reset()
            return false
        }
        val current = dragTouch ?: run {
            reset()
            return false
        }
        val frame = peelFrame(
            touchLocal = current,
            corner = corner,
            width = leaf.width,
            height = leaf.height,
            bindingOnly = false,
            oppositeWidth = oppositeWidth,
        )
        val complete = frame != null && shouldCompletePeel(
            frame = frame,
            width = leaf.width,
            height = leaf.height,
            velocityX = velocityX,
            velocityY = velocityY,
            completeDistancePx = completeDistancePx,
        )
        val (p0, _, p2) = autoPlayPathPoints(corner, leaf.width, leaf.height, oppositeWidth)
        touchX.snapTo(current.x)
        touchY.snapTo(current.y)
        dragTouch = null
        return if (complete) {
            phase = PeelPhase.Complete
            animateTouch(p2, PEEL_AUTO_MS)
            true
        } else {
            phase = PeelPhase.Cancel
            animateTouch(p0, PEEL_CANCEL_MS)
            reset()
            false
        }
    }

    suspend fun autoPlay(
        forward: Boolean,
        corner: PeelCorner,
        leaf: PeelLeaf,
        oppositeWidth: Float = 0f,
    ) {
        progress.stop()
        this.forward = forward
        this.corner = corner
        this.leaf = leaf
        this.oppositeWidth = oppositeWidth.coerceAtLeast(0f)
        this.phase = PeelPhase.AutoPlay
        progress.snapTo(0f)
        progress.animateTo(1f, tween(PEEL_AUTO_MS, easing = FastOutSlowInEasing))
        // 停在 t=1，等调用方 showSpread 后再 reset，避免中间闪回旧页
    }

    private suspend fun animateTouch(target: Offset, durationMs: Int) {
        val spec = tween<Float>(durationMs, easing = FastOutSlowInEasing)
        coroutineScope {
            launch { touchX.animateTo(target.x, spec) }
            launch { touchY.animateTo(target.y, spec) }
        }
    }
}

/** 纯函数：跟手抬手是否应该翻页。供单测，不依赖 [PeelController]。 */
fun peelEndShouldComplete(
    touchLocal: Offset,
    corner: PeelCorner,
    width: Float,
    height: Float,
    velocityX: Float,
    velocityY: Float,
    completeDistancePx: Float = 0f,
): Boolean {
    val frame = peelFrame(touchLocal, corner, width, height, bindingOnly = false) ?: return false
    return shouldCompletePeel(frame, width, height, velocityX, velocityY, completeDistancePx)
}

package com.llzx373.foldreader.feature.reader.peel

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.TwoWayConverter
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset

private val peelAutoEasing = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)

private val offsetConverter = TwoWayConverter<Offset, AnimationVector2D>(
    convertToVector = { AnimationVector2D(it.x, it.y) },
    convertFromVector = { Offset(it.v1, it.v2) },
)

enum class PeelPhase { Drag, Complete, Cancel, AutoPlay }

/**
 * 甩出后的收敛：临界阻尼弹簧（不回弹），初始速度由抬手速度提供。
 * 二维触点共用一条动画，避免 X/Y 分别收敛造成尾段轨迹不一致。
 */
private val peelSettleSpring = spring<Offset>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMediumLow,
)

/** 取消回缩：固定 [PEEL_CANCEL_MS] 快速复位，不继承速度（系统纠正用户的动作应当干脆）。 */
private val peelCancelSpec: FiniteAnimationSpec<Offset> =
    tween(PEEL_CANCEL_MS, easing = FastOutSlowInEasing)

/**
 * 仿真翻页状态机：跟手拖动、抬手完成/取消、点击自动播放。
 *
 * 触点一律是叶内局部坐标。自动播放用 [progress] 驱动 P0→P1→P2；
 * 后半段只钉装订边，页角翻到对页而不是从书上撕下来。
 * 拖动写 [dragTouch]（同步，好跟手）；完成/取消再动画二维 [touch]。
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
    val touch = Animatable(Offset.Zero, offsetConverter)
    private var completeOrigin: Offset? = null

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
                local to peelCrossedBinding(local, corner, leaf.width)
            }
            PeelPhase.Complete -> {
                val origin = completeOrigin ?: return null
                val (_, _, target) = autoPlayPathPoints(corner, leaf.width, leaf.height, oppositeWidth)
                val local = peelClampCompleteTouch(touch.value, origin, target)
                local to peelCrossedBinding(local, corner, leaf.width)
            }
            PeelPhase.Cancel -> touch.value to peelCrossedBinding(touch.value, corner, leaf.width)
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
        completeOrigin = null
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
        val crossed = peelCrossedBinding(current, corner, leaf.width)
        val frame = peelFrame(
            touchLocal = current,
            corner = corner,
            width = leaf.width,
            height = leaf.height,
            bindingOnly = crossed,
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
        touch.snapTo(current)
        dragTouch = null
        return if (complete) {
            completeOrigin = current
            phase = PeelPhase.Complete
            animateTouch(p2, peelSettleSpring, Offset(velocityX, velocityY))
            true
        } else {
            phase = PeelPhase.Cancel
            animateTouch(p0, peelCancelSpec, Offset.Zero)
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
        progress.animateTo(1f, tween(PEEL_AUTO_MS, easing = peelAutoEasing))
        // 停在 t=1，等调用方 showSpread 后再 reset，避免中间闪回旧页
    }

    private suspend fun animateTouch(
        target: Offset,
        spec: FiniteAnimationSpec<Offset>,
        velocity: Offset = Offset.Zero,
    ) {
        touch.animateTo(target, spec, velocity)
    }
}

fun peelCrossedBinding(touch: Offset, corner: PeelCorner, width: Float): Boolean =
    (corner.isRight && touch.x < 0f) || (!corner.isRight && touch.x > width)

/** 弹簧可以过冲，但呈现给纸张的触点停在起点到终点的包围盒内。 */
fun peelClampCompleteTouch(value: Offset, origin: Offset, target: Offset): Offset = Offset(
    value.x.coerceIn(minOf(origin.x, target.x), maxOf(origin.x, target.x)),
    value.y.coerceIn(minOf(origin.y, target.y), maxOf(origin.y, target.y)),
)

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

package com.llzx373.foldreader.feature.reader.peel

import androidx.compose.ui.geometry.Offset
import kotlin.math.abs
import kotlin.math.hypot

/** 被抓住的页角。向前翻用右侧两角，向后翻用左侧两角。 */
enum class PeelCorner {
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT,
    ;

    val isRight: Boolean get() = this == TOP_RIGHT || this == BOTTOM_RIGHT
    val isBottom: Boolean get() = this == BOTTOM_LEFT || this == BOTTOM_RIGHT
}

/**
 * 一叶纸在内容区里的矩形（内容区局部坐标）。
 *
 * 单页时 origin 为 (0, 0)、宽高即内容区；双页时各叶是安全区内居中的那一栏。
 * 前半段折痕停在被掀那一叶；后半段纸背绕装订边盖到对页。
 */
data class PeelLeaf(
    val width: Float,
    val height: Float,
    val originX: Float = 0f,
    val originY: Float = 0f,
) {
    val right: Float get() = originX + width
    val bottom: Float get() = originY + height
}

/**
 * 一次卷角的全部几何（叶内局部坐标，原点在叶的左上）。
 *
 * 命名与微信读书/任阅同源：A 触点、F 页角、G 中点、C 控制点、S 边起点、E 终点、V 二次贝塞尔顶点。
 */
data class PeelFrame(
    val corner: PeelCorner,
    val touch: Offset,
    val cornerPoint: Offset,
    val mid: Offset,
    val bezierStart1: Offset,
    val bezierControl1: Offset,
    val bezierEnd1: Offset,
    val bezierVertex1: Offset,
    val bezierStart2: Offset,
    val bezierControl2: Offset,
    val bezierEnd2: Offset,
    val bezierVertex2: Offset,
    val touchToCorner: Float,
    val bindingOnly: Boolean,
)

/** 自动翻页轨迹在 [0, 0.7] 走双圆附着；之后只钉装订边，页角翻到对页。 */
const val PEEL_ATTACHED_UNTIL = 0.7f

/**
 * |AF| / 叶短边 超过此值视为翻过去。
 *
 * 按短边而不是对角线：横屏双页对角线很长，0.22 对角线要拖四百多像素才过线，
 * 和已经改掉的「屏宽 15%」覆盖翻页是同一类手感问题。短边的 0.15 大约就是一次自然滑动；
 * 阅读器还会再用横滑判定距离（默认 40dp）封顶，两者取较短。
 */
const val PEEL_COMPLETE_RATIO = 0.15f

/** 离开页角方向的甩速（px·s⁻¹）过线也翻。 */
const val PEEL_FLING_PX_PER_SEC = 800f

const val PEEL_AUTO_MS = 400
const val PEEL_CANCEL_MS = 250

/** 小于此距离视为还没卷起来，避免除零。 */
const val PEEL_MIN_DRAG = 6f

/** |dy| 大于此倍数的 |dx| 时不接管，把竖滑留给亮度。 */
const val PEEL_VERTICAL_DOMINANCE = 1.6f

private const val SPEC_W = 1080f
private const val SPEC_H = 2340f

/** 说明书 1080×2340 右下角轨迹的归一化控制点。 */
internal val PEEL_PATH_P0 = Offset(1044f / SPEC_W, 2292f / SPEC_H)
internal val PEEL_PATH_P1 = Offset(420f / SPEC_W, 1680f / SPEC_H)
/** 装订圆上 135°：左下角钉住，触点落到对页。 */
internal val PEEL_PATH_P2 = Offset(
    (-SPEC_W * 0.70710677f) / SPEC_W,
    (SPEC_H - SPEC_W * 0.70710677f) / SPEC_H,
)

fun peelCornerPoint(corner: PeelCorner, width: Float, height: Float): Offset = when (corner) {
    PeelCorner.TOP_LEFT -> Offset(0f, 0f)
    PeelCorner.TOP_RIGHT -> Offset(width, 0f)
    PeelCorner.BOTTOM_LEFT -> Offset(0f, height)
    PeelCorner.BOTTOM_RIGHT -> Offset(width, height)
}

/**
 * 按触点落在叶的上/下半与翻页方向选页角。
 * 向前掀右上或右下，向后掀左上或左下（微信读书「靠近哪个角掀哪个角」）。
 */
fun peelCornerFor(local: Offset, width: Float, height: Float, forward: Boolean): PeelCorner {
    val bottom = local.y >= height * 0.5f
    return when {
        forward && bottom -> PeelCorner.BOTTOM_RIGHT
        forward -> PeelCorner.TOP_RIGHT
        bottom -> PeelCorner.BOTTOM_LEFT
        else -> PeelCorner.TOP_LEFT
    }
}

fun smoothstep(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    return x * x * (3f - 2f * x)
}

fun lerpOffset(a: Offset, b: Offset, t: Float): Offset =
    Offset(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)

/**
 * 把右下角归一化点镜像到 [corner] 所在象限。
 * 右下：原样；左下：x → W−x；右上：y → H−y；左上：两者都镜像。
 */
fun mirrorFromBottomRight(p: Offset, corner: PeelCorner, width: Float, height: Float): Offset {
    val x = if (corner.isRight) p.x * width else width - p.x * width
    val y = if (corner.isBottom) p.y * height else height - p.y * height
    return Offset(x, y)
}

fun autoPlayPathPoints(
    corner: PeelCorner,
    width: Float,
    height: Float,
    oppositeWidth: Float = 0f,
): Triple<Offset, Offset, Offset> = Triple(
    mirrorFromBottomRight(PEEL_PATH_P0, corner, width, height),
    mirrorFromBottomRight(PEEL_PATH_P1, corner, width, height),
    if (oppositeWidth > 1f) {
        dualCoverPoint(corner, width, height, oppositeWidth)
    } else {
        mirrorFromBottomRight(PEEL_PATH_P2, corner, width, height)
    },
)

/**
 * 双页终帧：被抓住的页角横着落到对页同一侧的角，纸背才能整叶盖住。
 * 右下 → 对页左下，右上 → 对页左上；不是走对角线去对页另一角。
 */
fun dualCoverPoint(
    corner: PeelCorner,
    width: Float,
    height: Float,
    oppositeWidth: Float,
): Offset {
    val far = oppositeWidth.coerceAtLeast(1f)
    return when (corner) {
        PeelCorner.TOP_LEFT -> Offset(width + far, 0f)
        PeelCorner.TOP_RIGHT -> Offset(-far, 0f)
        PeelCorner.BOTTOM_LEFT -> Offset(width + far, height)
        PeelCorner.BOTTOM_RIGHT -> Offset(-far, height)
    }
}

/** 后半段绕书脊盖住对页时，装订圆半径要够到对页同侧远角。 */
fun peelBindingRadius(
    width: Float,
    height: Float,
    oppositeWidth: Float,
    bindingOnly: Boolean,
): Float {
    if (!bindingOnly || oppositeWidth <= 1f) return width
    return maxOf(width, oppositeWidth)
}

/** 自动播放触点。t∈[0,1]；与说明书第 3 节同一条 smoothstep 折线。 */
fun autoPlayTouch(
    progress: Float,
    corner: PeelCorner,
    width: Float,
    height: Float,
    oppositeWidth: Float = 0f,
): Offset {
    val t = progress.coerceIn(0f, 1f)
    val (p0, p1, p2) = autoPlayPathPoints(corner, width, height, oppositeWidth)
    return if (t <= PEEL_ATTACHED_UNTIL) {
        val u = smoothstep((t / PEEL_ATTACHED_UNTIL).coerceIn(0f, 1f))
        lerpOffset(p0, p1, u)
    } else {
        val u = smoothstep(((t - PEEL_ATTACHED_UNTIL) / (1f - PEEL_ATTACHED_UNTIL)).coerceIn(0f, 1f))
        lerpOffset(p1, p2, u)
    }
}

fun autoPlayBindingOnly(progress: Float): Boolean = progress > PEEL_ATTACHED_UNTIL

/**
 * 纸张不撕裂：把 A 拉回约束圆内。
 *
 * 装订圆（始终）：右下角 `|A−(0,H)| ≤ W`，左下角对称。这是书脊/左缘钉住。
 * 对边圆（前半段）：`|A−(W,0)| ≤ H`，避免顶角被提前扯开。
 * [bindingOnly] 为 true 时只钉装订边，好让整页绕书脊翻到对页。
 */
fun clampPeelTouch(
    touch: Offset,
    corner: PeelCorner,
    width: Float,
    height: Float,
    bindingOnly: Boolean = false,
    oppositeWidth: Float = 0f,
): Offset {
    val f = peelCornerPoint(corner, width, height)
    val bindingCenter = Offset(if (corner.isRight) 0f else width, f.y)
    val pinned = clampToCircle(
        touch,
        bindingCenter,
        peelBindingRadius(width, height, oppositeWidth, bindingOnly),
    )
    if (bindingOnly) return pinned
    val vertCenter = Offset(f.x, if (corner.isBottom) 0f else height)
    return clampToCircle(pinned, vertCenter, height)
}

private fun clampToCircle(point: Offset, center: Offset, radius: Float): Offset {
    val dx = point.x - center.x
    val dy = point.y - center.y
    val len = hypot(dx, dy)
    if (len <= radius || len < 1e-6f || radius <= 0f) return point
    val s = radius / len
    return Offset(center.x + dx * s, center.y + dy * s)
}

fun lineIntersection(p1: Offset, p2: Offset, p3: Offset, p4: Offset): Offset? {
    val d1x = p2.x - p1.x
    val d1y = p2.y - p1.y
    val d2x = p4.x - p3.x
    val d2y = p4.y - p3.y
    val den = d1x * d2y - d1y * d2x
    if (abs(den) < 1e-4f) return null
    val t = ((p3.x - p1.x) * d2y - (p3.y - p1.y) * d2x) / den
    return Offset(p1.x + t * d1x, p1.y + t * d1y)
}

private fun bezierVertex(start: Offset, control: Offset, end: Offset): Offset = Offset(
    (start.x + 2f * control.x + end.x) / 4f,
    (start.y + 2f * control.y + end.y) / 4f,
)

/**
 * 由触点构造一帧。公式见说明书第 2 节（任阅/微信读书垂直平分线 + 二次贝塞尔）。
 *
 * [bindingOnly] 为 false 时双圆都夹；为 true 时只钉装订边（后半段绕书脊翻到对页）。
 * 触点从不放开装订圆，避免页角从书上撕下来。
 */
fun peelFrame(
    touchLocal: Offset,
    corner: PeelCorner,
    width: Float,
    height: Float,
    bindingOnly: Boolean = false,
    oppositeWidth: Float = 0f,
): PeelFrame? {
    if (width < 8f || height < 8f) return null
    val f = peelCornerPoint(corner, width, height)
    val a0 = clampPeelTouch(
        touchLocal,
        corner,
        width,
        height,
        bindingOnly = bindingOnly,
        oppositeWidth = oppositeWidth,
    )
    val dist = hypot(a0.x - f.x, a0.y - f.y)
    if (dist < PEEL_MIN_DRAG) return null
    val g = Offset((a0.x + f.x) / 2f, (a0.y + f.y) / 2f)
    var dx = f.x - g.x
    var dy = f.y - g.y
    if (abs(dx) < 1e-4f) dx = if (dx < 0f) -1e-4f else 1e-4f
    if (abs(dy) < 1e-4f) dy = if (dy < 0f) -1e-4f else 1e-4f
    val c1 = Offset(g.x - dy * dy / dx, f.y)
    val c2 = Offset(f.x, g.y - dx * dx / dy)
    val s1 = Offset(c1.x - (f.x - c1.x) / 2f, f.y)
    val s2 = Offset(f.x, c2.y - (f.y - c2.y) / 2f)
    val e1 = lineIntersection(a0, c1, s1, s2) ?: return null
    val e2 = lineIntersection(a0, c2, s1, s2) ?: return null
    if (!e1.x.isFinite() || !e1.y.isFinite() || !e2.x.isFinite() || !e2.y.isFinite()) return null
    return PeelFrame(
        corner = corner,
        touch = a0,
        cornerPoint = f,
        mid = g,
        bezierStart1 = s1,
        bezierControl1 = c1,
        bezierEnd1 = e1,
        bezierVertex1 = bezierVertex(s1, c1, e1),
        bezierStart2 = s2,
        bezierControl2 = c2,
        bezierEnd2 = e2,
        bezierVertex2 = bezierVertex(s2, c2, e2),
        touchToCorner = dist,
        bindingOnly = bindingOnly,
    )
}

/** 绕折痕（AF 的垂直平分线）的 Householder 反射：等距，F 与 A 互为像。 */
fun peelReflected(point: Offset, frame: PeelFrame): Offset {
    val nx0 = frame.touch.x - frame.cornerPoint.x
    val ny0 = frame.touch.y - frame.cornerPoint.y
    val len = hypot(nx0, ny0)
    if (len < 1e-3f) return point
    val nx = nx0 / len
    val ny = ny0 / len
    val g = frame.mid
    val d = (point.x - g.x) * nx + (point.y - g.y) * ny
    return Offset(point.x - 2f * d * nx, point.y - 2f * d * ny)
}

/**
 * 把叶内点通过折痕反射到纸背：`X' = H X + 2 (G·n) n`，`H = I − 2nnᵀ`。
 * 9 个数按 android.graphics.Matrix.setValues 行主序。
 */
fun peelReflectionMatrixValues(frame: PeelFrame): FloatArray {
    val nx0 = frame.touch.x - frame.cornerPoint.x
    val ny0 = frame.touch.y - frame.cornerPoint.y
    val len = hypot(nx0, ny0).coerceAtLeast(1e-3f)
    val nx = nx0 / len
    val ny = ny0 / len
    val g = frame.mid
    val gn = g.x * nx + g.y * ny
    val a = 1f - 2f * nx * nx
    val b = -2f * nx * ny
    val d = 1f - 2f * ny * ny
    val tx = 2f * gn * nx
    val ty = 2f * gn * ny
    return floatArrayOf(
        a, b, tx,
        b, d, ty,
        0f, 0f, 1f,
    )
}

fun peelProgressRatio(touchToCorner: Float, width: Float, height: Float): Float {
    val diag = hypot(width, height).coerceAtLeast(1f)
    return (touchToCorner / diag).coerceAtLeast(0f)
}

/**
 * 抬手是否翻过去：|AF| 过叶短边的 [PEEL_COMPLETE_RATIO]（可被 [completeDistancePx] 再缩短），
 * 或沿离开页角方向甩得够快。[velocityX]/[velocityY] 为叶内坐标的速度。
 */
fun shouldCompletePeel(
    frame: PeelFrame,
    width: Float,
    height: Float,
    velocityX: Float,
    velocityY: Float,
    completeDistancePx: Float = 0f,
): Boolean {
    val span = minOf(width, height).coerceAtLeast(1f)
    var limit = span * PEEL_COMPLETE_RATIO
    if (completeDistancePx > 1f) limit = minOf(limit, completeDistancePx)
    if (frame.touchToCorner > limit) return true
    val nx0 = frame.touch.x - frame.cornerPoint.x
    val ny0 = frame.touch.y - frame.cornerPoint.y
    val len = hypot(nx0, ny0)
    if (len < 1e-3f) return false
    val away = (velocityX * nx0 + velocityY * ny0) / len
    return away > PEEL_FLING_PX_PER_SEC
}

fun screenToLeafLocal(screenInContent: Offset, leaf: PeelLeaf): Offset =
    Offset(screenInContent.x - leaf.originX, screenInContent.y - leaf.originY)

/**
 * 双页两叶：左叶 / 右叶。单页时右叶为 null，左叶即整块内容区。
 *
 * [splitLeft] / [splitRight] 与 [contentWidth] 同一套内容区坐标（双页时内容区通常就是整窗）。
 */
fun peelLeaves(
    dual: Boolean,
    contentWidth: Float,
    contentHeight: Float,
    pageWidth: Float,
    splitLeft: Float,
    splitRight: Float,
): Pair<PeelLeaf, PeelLeaf?> {
    if (!dual || pageWidth <= 0f) {
        return PeelLeaf(contentWidth, contentHeight) to null
    }
    val leftInset = (splitLeft - pageWidth).coerceAtLeast(0f) / 2f
    val rightInset = (contentWidth - splitRight - pageWidth).coerceAtLeast(0f) / 2f
    val left = PeelLeaf(width = pageWidth, height = contentHeight, originX = leftInset, originY = 0f)
    val right = PeelLeaf(
        width = pageWidth,
        height = contentHeight,
        originX = splitRight + rightInset,
        originY = 0f,
    )
    return left to right
}

fun activePeelLeaf(forward: Boolean, left: PeelLeaf, right: PeelLeaf?): PeelLeaf =
    if (right == null || forward) (right ?: left) else left

/** 纸背可画到对页：右翻向左延伸到内容区左缘，左翻向右延伸到右叶右缘。 */
fun peelFlapExtend(
    corner: PeelCorner,
    leaf: PeelLeaf,
    left: PeelLeaf,
    right: PeelLeaf?,
): Pair<Float, Float> {
    if (right == null) return 0f to 0f
    val extendLeft = if (corner.isRight) (leaf.originX - left.originX).coerceAtLeast(0f) else 0f
    val extendRight = if (!corner.isRight) (right.right - leaf.right).coerceAtLeast(0f) else 0f
    return extendLeft to extendRight
}

/** 对页在本叶局部坐标里要跨越的宽度；单页为 0。 */
fun peelOppositeWidth(
    corner: PeelCorner,
    leaf: PeelLeaf,
    left: PeelLeaf,
    right: PeelLeaf?,
): Float {
    val (extL, extR) = peelFlapExtend(corner, leaf, left, right)
    return if (corner.isRight) extL else extR
}

internal fun triangleArea(a: Offset, b: Offset, c: Offset): Float =
    abs(a.x * (b.y - c.y) + b.x * (c.y - a.y) + c.x * (a.y - b.y)) / 2f

fun peelBackArea(frame: PeelFrame): Float =
    triangleArea(frame.touch, frame.bezierVertex1, frame.bezierVertex2)

/** 点到直线 AB 的距离。用来确认反射后的页边落在 A–C 上。 */
fun peelDistanceToLine(point: Offset, a: Offset, b: Offset): Float {
    val vx = b.x - a.x
    val vy = b.y - a.y
    val len = hypot(vx, vy).coerceAtLeast(1e-6f)
    return abs((point.x - a.x) * vy - (point.y - a.y) * vx) / len
}

fun peelUnderAreaHint(frame: PeelFrame): Float =
    triangleArea(frame.cornerPoint, frame.bezierStart1, frame.bezierStart2)

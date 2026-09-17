package com.llzx373.foldreader.feature.reader

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 卷曲翻页几何：纯函数、无 Android 依赖，可在纯 JVM 单测里跑。
 *
 * ## 模型：折痕处的小圆角 + 翻起部分是一块斜着的平面
 *
 * 纸张沿一条**直线折痕**抬起，抬起角为 θ。纸在折痕处有一小段圆柱圆角（半径 [bendRadius]），
 * 出了圆角之后**保持平整**，只是整体倾斜 θ：
 *
 * ```
 * 平铺弧长 s 处的屏幕偏移：
 *   s ≤ 弯折弧长  →  t = R·sin(s/R)                      （圆角段）
 *   否则          →  t = R·sin(θ) + (s − R·θ)·cos(θ)     （斜面段）
 * ```
 *
 * 这一点是整个模型的关键：**绝大多数翻起的纸是可见的斜面**，只有折痕附近一小段是曲面。
 * 如果反过来把整段翻起的纸都裹在圆柱上（对齐卷角的做法），卷筒会很粗、而且中间一段弧长
 * 会被外层挡住**彻底看不见**——读者看到的是"文字被吸进一条窄带然后消失＋一条空白纸背柱
 * 横扫屏幕"，完全不像翻书。
 *
 * ## 沿 dir 的四段（t = 到折痕的有符号距离）
 *
 * | 区间 | 屏幕上是 | 取样 |
 * |---|---|---|
 * | t < 0 且未被背面盖住 | 尚未离地的平铺页 | 当前页，**逐像素不动** |
 * | 0 ≤ t < frontTMax | 纸张正面（θ < 90° 时含斜面） | 正面纹理，沿 dir 压缩 |
 * | frontTMax ≤ t ≤ backTMax | 纸张背面（θ > 90° 才出现） | 背面纹理 |
 * | 其余 | 卷起之外 | 下层页 |
 *
 * θ < 90° 时整块翻起的纸都朝上，**没有背面可见**（也就没有早期那条空白柱）；
 * θ > 90° 时斜面转到折痕左侧、露出纸背——这正是"纸翻过去压在左边"的样子。
 *
 * ## 进度与收敛
 *
 * θ = π·progress；折痕同时从自由边扫到装订边之外（可卷长度夹在 `[0, 叶片跨度]` 内）。
 * `p = 0` 时 θ=0、长度为 0 → 全屏当前页；`p = 1` 时整块纸已转到折痕左侧且折痕本身
 * 也移出叶片 → 全屏下层页。两端严格收敛，落账不需要交叉淡入。
 */

/** 某个屏幕位置落在哪一种表面上。 */
enum class CurlSurface { FLAT, FRONT, BACK, UNDER }

/**
 * 叶片坐标系：叶片矩形在屏幕上的定位。装订边在 [originX]，[side] = +1 叶片向 +x 展开，
 * −1 向 −x 展开（双页后退翻 = 抓左页，叶片向左展开）。
 */
data class CurlFrame(
    val originX: Float,
    val side: Float,
    val width: Float,
    val height: Float,
) {
    val screenRect: Rect = if (side >= 0f) {
        Rect(originX, 0f, originX + width, height)
    } else {
        Rect(originX - width, 0f, originX, height)
    }

    val center: Offset get() = Offset(screenRect.center.x, screenRect.center.y)

    /** 叶片局部坐标（0..width 从装订边到自由边）→ 屏幕坐标。 */
    fun toScreen(local: Offset): Offset = Offset(originX + side * local.x, local.y)

    /** 屏幕坐标 → 叶片局部坐标。 */
    fun toLocal(screen: Offset): Offset = Offset((screen.x - originX) * side, screen.y)

    val corners: List<Offset>
        get() {
            val r = screenRect
            return listOf(
                Offset(r.left, r.top), Offset(r.right, r.top),
                Offset(r.left, r.bottom), Offset(r.right, r.bottom),
            )
        }
}

/**
 * 一次卷曲的完整状态（屏幕坐标）。
 *
 * [dir] 单位向量、指向被卷起的一侧；[crease] 折痕上一点；
 * [bendRadius] 折痕处的圆角半径；[angle] 抬起角 θ ∈ [0, π]；
 * [length] 折痕到自由边的平铺长度；[progress] 驱动进度 0..1。
 *
 * 折痕方向取自拖动方向，所以"从右上角往左下抓"就是一条向右上倾的折痕。
 */
data class CurlRoll(
    val crease: Offset,
    val dir: Offset,
    val bendRadius: Float,
    val angle: Float,
    val length: Float,
    val progress: Float = 0f,
    /** 透视投影中心（屏幕坐标）。 */
    val viewPoint: Offset = Offset.Zero,
    /** 相机距离（px）；取 [ORTHO_VIEW_DISTANCE] 即退化为正交投影。 */
    val viewDistance: Float = ORTHO_VIEW_DISTANCE,
) {
    /** 圆角段的弧长（不超过整段可卷长度）。 */
    val bendLength: Float get() = min(bendRadius * angle, length)

    /** 圆角段末端的切线角——斜面就用这个倾角，两者在接缝处严格相切。 */
    val tangent: Float get() = if (bendRadius > 0f) bendLength / bendRadius else 0f

    /** 斜面起点（圆角段末端）沿 dir 的屏幕偏移。 */
    val planeStart: Float get() = bendRadius * sin(tangent)

    /** 斜面末端（纸的自由边）沿 dir 的屏幕偏移；θ > 90° 时会落到折痕左侧。 */
    val planeEnd: Float get() = planeStart + (length - bendLength) * cos(tangent)

    /**
     * 正面可见范围的上界（= 自由边落点）。
     * θ ≤ 90° 时整块翻起的纸都朝上，正面一直可见到自由边；
     * θ > 90° 时自由边落到折痕左侧，超出它的部分被纸背盖住。
     */
    val frontMax: Float get() = planeEnd

    /** 背面可见范围：θ > 90° 才出现，从斜面末端一直到圆角段外缘。 */
    val backMin: Float get() = planeEnd
    val backMax: Float get() = bendRadius

    val hasBack: Boolean get() = tangent > PI.toFloat() / 2f && backMax > backMin

    /** 平铺页与"已翻起部分"的分界（恒 ≤ 0；纸翻过来压住左侧时就是纸背的下缘）。 */
    val flatEdge: Float get() = min(0f, backMin)

    /** 整块翻起部分的外缘——再往外就是露出的下层页。 */
    val rollEdge: Float get() = max(frontMax, backMax)

    val degenerate: Boolean get() = length <= 0f || bendRadius <= 0f
}

/** 折痕圆角半径占叶片跨度的比例（掀到后段的**上限**）。 */
const val CURL_BEND_RATIO: Float = 0.055f

/**
 * 起手时折痕圆角的比例——远小于 [CURL_BEND_RATIO]。
 *
 * 圆角随掀起程度从"几乎贴合的细缝"长到 [CURL_BEND_RATIO]：固定比例会让折痕从头到尾一样厚，
 * 折痕纸边、阴影宽度也就全程不变，观感上就成了"一条被打上去的线"而不是"逐渐掀起来的纸"。
 */
const val CURL_BEND_MIN_RATIO: Float = 0.012f

/**
 * "从叶片外一直到某处"的开放下界。
 *
 * 正面的裁剪必须用它而不是 [CurlRoll.flatEdge]：**尚未翻起的平铺页在 flatEdge 之外**
 * （`t < flatEdge`），漏掉它就会让下层页从那一片透出来——表现为"翻页时左边已经变成了
 * 后一页的内容"。
 */
const val CURL_OPEN_LOWER_BOUND: Float = -1e6f

/** 正交投影的相机距离（足够远即无透视）。 */
const val ORTHO_VIEW_DISTANCE: Float = 1e9f

/**
 * 相机距离相对叶片跨度的倍数。越小透视越强：翻起那片会"近大远小"、文字行收束、
 * 整体有立体旋转感。取 2.6 是照着微信读书那类仿真的观感定的。
 */
const val CURL_VIEW_DISTANCE_RATIO: Float = 2.6f

/** 起手点到当前点的最小距离（px）：小于它视为还没开始卷。 */
const val MIN_CURL_DRAG: Float = 6f

/** 网格单边格数上限，防超长屏把顶点数打爆。 */
const val MAX_CURL_MESH_CELLS: Int = 160

/** 网格顶点总数上限（顶点数 = (w+1)*(h+1)）。 */
const val MAX_CURL_MESH_VERTICES: Int = 24_000

/**
 * 网格采样间隔（px）：越小卷曲带越精细、开销越大。性能不达标时先调这个。
 */
const val CURL_MESH_INTERVAL_PX: Float = 24f

/** 起手后多久才决定是否进入翻页（毫秒）：避免轻扫误触发。 */
const val CURL_START_TIMEOUT_MS: Long = 70L

/** 起手后在多长比例的行程内把锚定量衰减到 0（折痕追上手指）。 */
private const val CURL_CATCH_UP_RATIO: Float = 0.35f

/** 末尾多扫一点，保证 `p = 1` 时整块纸**连同它投出的阴影带**都滑出叶片。 */
private const val CURL_END_MARGIN: Float = 4f

/**
 * 投到下层页的阴影带宽度相对于圆角半径的倍数，与 [curlShadowWidths] 的 `cast` 保持一致。
 * 末端余量要靠它：不然末态会在叶片左缘残留一条合不上的投影。
 */
private const val CURL_CAST_WIDTH_RATIO: Float = 2.4f

internal fun dot(a: Offset, b: Offset): Float = a.x * b.x + a.y * b.y

/** 起手方向：由"起手点 → 当前点"得到被卷起一侧的单位向量；距离过近返回 null。 */
fun curlDirectionFor(grab: Offset, current: Offset): Offset? {
    val dx = grab.x - current.x
    val dy = grab.y - current.y
    val len = sqrt(dx * dx + dy * dy)
    if (len < MIN_CURL_DRAG) return null
    return Offset(dx / len, dy / len)
}

/** 叶片沿 [dir] 的投影跨度：返回 (最远点 uMax, 跨度 extent)，都以叶片中心为原点。 */
private fun curlAxisExtent(frame: CurlFrame, dir: Offset): Pair<Float, Float> {
    val c = frame.center
    val us = frame.corners.map { dot(Offset(it.x - c.x, it.y - c.y), dir) }
    val uMax = us.max()
    return uMax to (uMax - us.min()).coerceAtLeast(1f)
}

/** 折痕圆角半径：随掀起程度从 [CURL_BEND_MIN_RATIO] 长到 [CURL_BEND_RATIO]。 */
private fun curlBendRadius(frame: CurlFrame, dir: Offset, progress: Float): Float {
    val extent = curlAxisExtent(frame, dir).second
    val t = progress.coerceIn(0f, 1f)
    val ratio = CURL_BEND_MIN_RATIO + (CURL_BEND_RATIO - CURL_BEND_MIN_RATIO) * t
    return extent * ratio
}

/** 折痕从自由边扫到"整块纸连同投影都滑出叶片之外"所需的总距离。 */
private fun curlSweepLength(frame: CurlFrame, dir: Offset): Float {
    val (_, extent) = curlAxisExtent(frame, dir)
    val bend = extent * CURL_BEND_RATIO
    // θ 转到 π 时自由边落到折痕左侧 extent − R·π 处；再让圆角外缘与投影带一起出去
    val tail = bend * (PI.toFloat() + CURL_CAST_WIDTH_RATIO) + CURL_END_MARGIN
    return extent + tail
}

/**
 * 由进度构造卷筒。θ = π·progress；折痕同步从自由边扫到装订边之外，
 * 可卷长度夹在 `[0, 叶片跨度]` 内（真实纸张不会越卷越长）。
 */
fun curlRollForProgress(
    frame: CurlFrame,
    dir: Offset,
    progress: Float,
): CurlRoll {
    val p = progress.coerceIn(0f, 1f)
    val c = frame.center
    val (uMax, extent) = curlAxisExtent(frame, dir)
    val uCrease = uMax - curlSweepLength(frame, dir) * p
    return CurlRoll(
        crease = Offset(c.x + dir.x * uCrease, c.y + dir.y * uCrease),
        dir = dir,
        bendRadius = curlBendRadius(frame, dir, p),
        angle = PI.toFloat() * p,
        length = (uMax - uCrease).coerceIn(0f, extent),
        progress = p,
        viewPoint = c,
        viewDistance = extent * CURL_VIEW_DISTANCE_RATIO,
    )
}

/**
 * 由手指位置反解进度：折痕精确跟在触点下（与 [curlRollForProgress] 互逆）。
 */
fun curlProgressFor(frame: CurlFrame, dir: Offset, finger: Offset): Float {
    val c = frame.center
    val (uMax, _) = curlAxisExtent(frame, dir)
    val uFinger = dot(Offset(finger.x - c.x, finger.y - c.y), dir)
    return ((uMax - uFinger) / curlSweepLength(frame, dir)).coerceIn(0f, 1f)
}

/**
 * 起手锚定量：把起手点沿 dir 平移到"折痕起点"所在的法线上。
 * 抓在页面中间时若不做锚定，一按下折痕就会跳到起点之外，画面立刻跳变。
 */
fun curlStartAnchor(frame: CurlFrame, dir: Offset, grab: Offset): Offset {
    val c = frame.center
    val (uMax, _) = curlAxisExtent(frame, dir)
    val uGrab = dot(Offset(grab.x - c.x, grab.y - c.y), dir)
    val k = uMax - uGrab
    return Offset(dir.x * k, dir.y * k)
}

/**
 * 拖拽跟手进度：起手锚定 + 前 [CURL_CATCH_UP_RATIO] 行程内把锚定量衰减到 0——
 * 折痕先平滑起步，随后精确贴在触点下。
 */
fun curlDragProgress(frame: CurlFrame, dir: Offset, grab: Offset, finger: Offset): Float {
    val (_, extent) = curlAxisExtent(frame, dir)
    val anchor = curlStartAnchor(frame, dir, grab)
    val traveled = -dot(Offset(finger.x - grab.x, finger.y - grab.y), dir)
    val decay = (1f - traveled.coerceAtLeast(0f) / (extent * CURL_CATCH_UP_RATIO))
        .coerceIn(0f, 1f)
    return curlProgressFor(
        frame = frame,
        dir = dir,
        finger = Offset(finger.x + anchor.x * decay, finger.y + anchor.y * decay),
    )
}

/**
 * 接管正在飞的翻页：以 [baseProgress] 为基准，按手指相对接管点的位移继续推进。
 * 接管那一帧进度恰为 [baseProgress]，不会跳。
 *
 * 注意不能用 [curlDragProgress]——它的起手锚定量会把进度强制拉回 0。
 */
fun curlResumeProgress(
    frame: CurlFrame,
    dir: Offset,
    finger: Offset,
    takeOverAt: Offset,
    baseProgress: Float,
): Float {
    val delta = curlProgressFor(frame, dir, finger) - curlProgressFor(frame, dir, takeOverAt)
    return (baseProgress + delta).coerceIn(0f, 1f)
}

/** 屏幕位置 [p] 沿 dir 距折痕的有符号距离（正 = 被翻起的一侧）。 */
fun curlOffset(roll: CurlRoll, p: Offset): Float =
    dot(Offset(p.x - roll.crease.x, p.y - roll.crease.y), roll.dir)

/** 平铺弧长 [s] 处的屏幕偏移。 */
fun curlProject(roll: CurlRoll, s: Float): Float = if (s <= roll.bendLength) {
    roll.bendRadius * sin(s / roll.bendRadius)
} else {
    roll.planeStart + (s - roll.bendLength) * cos(roll.tangent)
}

/** [p] 落在哪一种表面上（见文件头表格）。 */
fun curlSurfaceAt(roll: CurlRoll, p: Offset): CurlSurface {
    if (roll.degenerate) return CurlSurface.FLAT
    val t = curlOffset(roll, p)
    return when {
        // 纸翻过折痕压在左侧时，纸背优先于下面的平铺页
        roll.hasBack && t in roll.backMin..roll.backMax -> CurlSurface.BACK
        t < 0f -> CurlSurface.FLAT
        // 含端点：自由边本身仍属于纸（越过它才是下层页）
        t <= roll.frontMax -> CurlSurface.FRONT
        else -> CurlSurface.UNDER
    }
}

/**
 * 屏幕上距折痕 [t] 处对应的平铺弧长；该处没有纸时返回 null。
 * 出图与命中测试用（生产路径走网格，不需要反解）。
 */
fun curlArcAt(roll: CurlRoll, t: Float): Float? {
    if (roll.degenerate) return null
    val surface = when {
        roll.hasBack && t in roll.backMin..roll.backMax -> CurlSurface.BACK
        t < 0f -> CurlSurface.FLAT
        t <= roll.frontMax -> CurlSurface.FRONT
        else -> return null
    }
    if (surface == CurlSurface.FLAT) return null
    // 斜面段：正背共用同一公式，cos 的符号已经决定了 t 与 s 的单调方向
    val cosT = cos(roll.tangent)
    if (cosT != 0f) {
        val planeS = roll.bendLength + (t - roll.planeStart) / cosT
        val planeFace =
            if (cosT > 0f) CurlSurface.FRONT else CurlSurface.BACK
        if (planeS in roll.bendLength..roll.length && planeFace == surface) return planeS
    }
    // 圆角段
    if (roll.bendRadius <= 0f) return null
    val ratio = (t / roll.bendRadius).coerceIn(-1f, 1f)
    val bendS = if (surface == CurlSurface.FRONT) {
        roll.bendRadius * asin(ratio)
    } else {
        roll.bendRadius * (PI.toFloat() - asin(ratio))
    }
    return if (bendS in 0f..roll.bendLength) bendS else null
}

/** 平铺弧长 [s] 处的纸面高度（朝向观察者）。 */
fun curlHeight(roll: CurlRoll, s: Float): Float = if (s <= roll.bendLength) {
    roll.bendRadius * (1f - cos(s / roll.bendRadius))
} else {
    roll.bendRadius * (1f - cos(roll.tangent)) + (s - roll.bendLength) * sin(roll.tangent)
}

/**
 * 弧长 [s] 处的透视放大率 `m = D / (D − z)`。
 * 注意 z 只依赖弧长（沿折痕方向高度不变），所以**每条等弧长线投影后仍是直线、且仍平行于
 * 折痕**——正/背分界与各条阴影带的"两平行线夹一带"结构因此得以保留。
 */
fun curlScale(roll: CurlRoll, s: Float): Float {
    if (roll.viewDistance >= ORTHO_VIEW_DISTANCE) return 1f
    val depth = roll.viewDistance - curlHeight(roll, s)
    return if (depth <= 1f) roll.viewDistance else roll.viewDistance / depth
}

/** 把抬起后的屏幕点按透视投影缩放（[s] 为该点所在弧长）。 */
private fun curlApplyPerspective(roll: CurlRoll, lifted: Offset, s: Float): Offset {
    val m = curlScale(roll, s)
    if (m == 1f) return lifted
    return Offset(
        roll.viewPoint.x + (lifted.x - roll.viewPoint.x) * m,
        roll.viewPoint.y + (lifted.y - roll.viewPoint.y) * m,
    )
}

/**
 * 平铺点 [p] 翻起后的屏幕位置：沿 dir 的分量由 s 变成 [curlProject]，再按透视缩放。
 * `s ≤ 0`（折痕未被翻起的一侧）原样返回——这是"正在读的正文不动"的几何来源。
 */
fun curlWarpPoint(roll: CurlRoll, p: Offset): Offset {
    val s = curlOffset(roll, p)
    if (s <= 0f || roll.degenerate) return p
    val shift = curlProject(roll, s) - s
    val lifted = Offset(p.x + roll.dir.x * shift, p.y + roll.dir.y * shift)
    return curlApplyPerspective(roll, lifted, s)
}

/**
 * `drawBitmapMesh` 的网格顶点：按源位图的行优先顺序，输出每个顶点变形后的**屏幕坐标**。
 *
 * 源位图是叶片（frame.width × frame.height）。平板部分（s ≤ 0）顶点保持原位，
 * 只有被翻起的一侧被压缩——所以文字变形只发生在翻起的那一块里。
 */
fun curlMeshVertices(roll: CurlRoll, frame: CurlFrame, meshW: Int, meshH: Int): FloatArray {
    val w = max(1, meshW)
    val h = max(1, meshH)
    val out = FloatArray((w + 1) * (h + 1) * 2)
    var i = 0
    for (row in 0..h) {
        val localY = frame.height * row / h
        for (col in 0..w) {
            val localX = frame.width * col / w
            val p = curlWarpPoint(roll, frame.toScreen(Offset(localX, localY)))
            out[i++] = p.x
            out[i++] = p.y
        }
    }
    return out
}

/** 网格格数：按采样间隔铺满叶片，并夹到顶点数上限之内。 */
fun curlMeshSize(frame: CurlFrame, intervalPx: Float): Pair<Int, Int> {
    val interval = intervalPx.coerceAtLeast(1f)
    var w = (frame.width / interval).roundToInt().coerceIn(2, MAX_CURL_MESH_CELLS)
    var h = (frame.height / interval).roundToInt().coerceIn(2, MAX_CURL_MESH_CELLS)
    while ((w + 1) * (h + 1) > MAX_CURL_MESH_VERTICES) {
        w = (w * 4 / 5).coerceAtLeast(2)
        h = (h * 4 / 5).coerceAtLeast(2)
    }
    return w to h
}

/** 用一条 `signed(p) <= 0` 的半平面裁剪凸多边形（Sutherland–Hodgman）。 */
private fun clipBelowZero(poly: List<Offset>, signed: (Offset) -> Float): List<Offset> {
    if (poly.isEmpty()) return poly
    val out = ArrayList<Offset>(poly.size + 2)
    for (i in poly.indices) {
        val a = poly[i]
        val b = poly[(i + 1) % poly.size]
        val sa = signed(a)
        val sb = signed(b)
        if (sa <= 0f) out.add(a)
        if ((sa <= 0f) != (sb <= 0f)) {
            val t = sa / (sa - sb)
            out.add(Offset(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t))
        }
    }
    return out
}

/**
 * 把裁剪得到的屏幕顶点按透视缩放。顶点要么落在等弧长边界上（反解弧长后精确投影），
 * 要么落在叶片矩形边上（用该处的弧长近似——矩形边投影后本是曲线，误差只在页面上下缘几像素）。
 */
private fun curlProjectVertex(roll: CurlRoll, q: Offset): Offset {
    if (roll.viewDistance >= ORTHO_VIEW_DISTANCE) return q
    val t = curlOffset(roll, q)
    if (t <= 0f) return q
    val s = curlArcAt(roll, t) ?: return q
    return curlApplyPerspective(roll, q, s)
}

/**
 * 叶片矩形被 `[fromT, toT]` 这条"距折痕的带"裁出来的凸多边形（屏幕坐标）。
 *
 * 正面区取 `[0, frontMax]`、背面区取 `[backMin, backMax]`、阴影带取更窄的区间。
 * 默认裁剪到叶片矩形，因此**双页时折痕不会糊到中缝另一侧**。
 * 裁剪在抬起前的空间做、最后逐顶点过透视——等弧长线投影后仍平行于折痕，带形状是准的。
 */
fun curlBandPolygon(
    roll: CurlRoll,
    frame: CurlFrame,
    fromT: Float,
    toT: Float,
    clipRect: Rect = frame.screenRect,
): List<Offset> {
    var poly = listOf(
        Offset(clipRect.left, clipRect.top), Offset(clipRect.right, clipRect.top),
        Offset(clipRect.right, clipRect.bottom), Offset(clipRect.left, clipRect.bottom),
    )
    poly = clipBelowZero(poly) { curlOffset(roll, it) - toT }
    poly = clipBelowZero(poly) { fromT - curlOffset(roll, it) }
    if (poly.size < 3) return emptyList()
    return poly.map { curlProjectVertex(roll, it) }.takeIf { it.size >= 3 } ?: emptyList()
}

/** 多边形面积（用于覆盖率单调性断言）。 */
fun polygonArea(poly: List<Offset>): Float {
    if (poly.size < 3) return 0f
    var acc = 0f
    for (i in poly.indices) {
        val a = poly[i]
        val b = poly[(i + 1) % poly.size]
        acc += a.x * b.y - b.x * a.y
    }
    return kotlin.math.abs(acc) / 2f
}

/** 阴影宽度尺度：圆角越紧，折痕阴影越窄。 */
fun curlShadowScale(roll: CurlRoll): Float = max(1f, roll.bendRadius)

/** 供测试与调试：整块翻起部分覆盖的屏幕包围盒。 */
fun curlRollBounds(roll: CurlRoll, frame: CurlFrame): Rect {
    val p = curlBandPolygon(roll, frame, roll.flatEdge, roll.rollEdge)
    if (p.isEmpty()) return Rect(roll.crease, 0f)
    return Rect(
        p.minOf { it.x }, p.minOf { it.y },
        p.maxOf { it.x }, p.maxOf { it.y },
    )
}

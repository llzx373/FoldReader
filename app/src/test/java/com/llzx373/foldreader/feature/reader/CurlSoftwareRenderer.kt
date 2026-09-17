package com.llzx373.foldreader.feature.reader

import androidx.compose.ui.geometry.Offset

/**
 * [CurlOverlay] 的离线出图镜像：逐像素按同一套几何与同一套阴影剖面渲染。
 *
 * 与生产路径的唯一差别：生产用 `Canvas.drawBitmapMesh`（网格顶点做分段线性近似），
 * 这里用**精确逆变换**（[curlArcAt] 反解弧长）。因此出图比真机略干净——网格离散化带来的
 * 锯齿不在离线图里。几何与阴影参数完全共用（[curlShadowWidths] 等），不会漂移。
 */
class CurlSoftwareRenderer(
    val width: Int,
    val height: Int,
    /** 叶片位图（ARGB），尺寸 width×height。 */
    val leaf: IntArray,
    /** 下层页位图（ARGB），尺寸 width×height。 */
    val under: IntArray,
    /** 纸背纹理；为空则用 [pageBackArgb] 纯色。 */
    val back: IntArray? = null,
    val pageBackArgb: Int = 0xFFDCE8D8.toInt(),
    val palette: CurlShadowPalette = CurlShadowPalette(),
) {

    private fun sample(tex: IntArray, x: Float, y: Float): Int {
        val ix = x.toInt().coerceIn(0, width - 1)
        val iy = y.toInt().coerceIn(0, height - 1)
        return tex[iy * width + ix]
    }

    private fun darken(color: Int, alpha: Float): Int {
        if (alpha <= 0f) return color
        val k = 1f - alpha.coerceIn(0f, 1f)
        fun ch(shift: Int) =
            ((((color shr shift) and 0xFF) / 255f) * k * 255f + 0.5f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun blend(base: Int, over: Int, alpha: Float): Int {
        val a = alpha.coerceIn(0f, 1f)
        if (a <= 0f) return base
        fun ch(shift: Int): Int {
            val b = ((base shr shift) and 0xFF) / 255f
            val o = ((over shr shift) and 0xFF) / 255f
            return ((b + (o - b) * a) * 255f + 0.5f).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    fun render(roll: CurlRoll, frame: CurlFrame): IntArray {
        val out = IntArray(width * height)
        if (roll.degenerate) {
            leaf.copyInto(out)
            return out
        }
        val w = curlShadowWidths(roll)
        val aCrease = palette.crease.alpha
        val aCast = palette.cast.alpha
        val aEdge = palette.edge.alpha
        val aRollShade = palette.rollShade.alpha
        val backSpan = (roll.backMax - roll.backMin).coerceAtLeast(1f)
        val sheetEnd = minOf(roll.flatEdge + w.sheet, roll.frontMax)
        val edgeStart = roll.frontMax - w.edge
        val foldEdgeW = curlFoldEdgeWidth(roll)
        val d = roll.dir

        for (y in 0 until height) {
            val fy = y + 0.5f
            for (x in 0 until width) {
                val i = y * width + x
                val p = Offset(x + 0.5f, fy)

                // 透视逆解：屏幕点是"抬起后按 m 放大"的结果，而 m 依赖弧长，迭代几次即收敛。
                // 收敛后 q 是抬起前的屏幕位置，分类与取样都在它上面做（与 curlBandPolygon 一致）。
                var q = p
                val vp = roll.viewPoint
                for (iter in 0 until 5) {
                    val tq = curlOffset(roll, q)
                    if (tq <= 0f) break
                    val sq = curlArcAt(roll, tq) ?: break
                    val m = curlScale(roll, sq)
                    if (m == 1f) break
                    q = Offset(vp.x + (p.x - vp.x) / m, vp.y + (p.y - vp.y) / m)
                }
                val t = curlOffset(roll, q)

                val color = when (curlSurfaceAt(roll, q)) {
                    CurlSurface.FLAT -> leaf[i]
                    CurlSurface.UNDER -> under[i]
                    CurlSurface.FRONT -> {
                        val s = curlArcAt(roll, t) ?: 0f
                        sample(leaf, q.x + d.x * (s - t), q.y + d.y * (s - t))
                    }
                    CurlSurface.BACK -> {
                        val s = curlArcAt(roll, t) ?: 0f
                        val src = Offset(q.x + d.x * (s - t), q.y + d.y * (s - t))
                        back?.let { sample(it, src.x, src.y) } ?: pageBackArgb
                    }
                }

                // 阴影剖面与 CurlShadows 的各条带一一对应
                val alpha = when {
                    t > roll.rollEdge ->
                        aCast * (1f - (t - roll.rollEdge) / w.cast).coerceIn(0f, 1f)

                    t < roll.flatEdge ->
                        aCrease * (1f - (roll.flatEdge - t) / w.crease).coerceIn(0f, 1f)

                    roll.hasBack && t >= roll.backMin && t <= roll.backMax ->
                        aRollShade * ((t - roll.backMin) / backSpan).coerceIn(0f, 1f)

                    t <= sheetEnd ->
                        aCrease * (1f - (t - roll.flatEdge) / w.sheet).coerceIn(0f, 1f)

                    t >= edgeStart && t <= roll.frontMax ->
                        aEdge * ((t - edgeStart) / w.edge).coerceIn(0f, 1f)

                    else -> 0f
                }
                out[i] = when {
                    // 折痕纸边白带画在最上层（与 CurlOverlay 的图层顺序一致）
                    t in 0f..foldEdgeW ->
                        blend(darken(color, alpha), pageBackArgb, 1f - t / foldEdgeW)
                    else -> darken(color, alpha)
                }
            }
        }
        return out
    }
}

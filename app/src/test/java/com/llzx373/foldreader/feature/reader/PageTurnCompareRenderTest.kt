package com.llzx373.foldreader.feature.reader

import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * 仿真翻页「差距对比」出图：同一份页面内容，三种模型并排渲染到
 * build/curlFrames/compare/，用于目检当前生产模型与业内主流做法差在哪。
 *
 * - A 当前生产模型：单页铰链（正交投影，纸张是平面绕竖轴旋转），
 *   几何与明暗逐分支对齐 HINGE_AGSL（复用 HingeSoftwareRenderer）；
 * - B 同一模型 + 透视投影：只把相机从"无穷远"换成有限视距，
 *   几何常数、光照、时序全部不动——用来隔离"透视"这一项贡献多少观感；
 * - C 圆柱卷曲参考：纸张绕圆柱卷起（iBooks / turn.js / pagecurl 一族），
 *   含基于表面法线的光照、卷筒高光与卷筒投射到下一页的阴影。
 *
 * 三列取"大致等进度"的状态（已揭示面积接近），不是严格同参数。
 * C 为从公开圆柱模型自行推导的实现，只作观感基准，非可移植代码。
 */
class PageTurnCompareRenderTest {

    private val tileW = 640
    private val tileH = 576
    private val bg = 0xFFC7EDCC.toInt()
    private val ink = 0xFF2B3A2E.toInt()
    private val outDir = File("build/curlFrames/compare")

    // ---------- 页面内容 ----------

    private fun makePage(label: String): IntArray {
        val img = BufferedImage(tileW, tileH, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.color = Color(bg, true)
        g.fillRect(0, 0, tileW, tileH)
        g.color = Color(ink, true)
        g.font = g.font.deriveFont(tileW * 0.035f)
        g.drawString(label, (tileW * 0.04f).toInt(), (tileH * 0.05f).toInt())
        val lineH = (tileH * 0.035f).toInt()
        var y = (tileH * 0.10f).toInt()
        var row = 0
        while (y < tileH * 0.90f) {
            val blocks = 3 + (row % 3)
            var x = (tileW * 0.06f).toInt() + if (row % 4 == 0) (tileW * 0.10f).toInt() else 0
            for (b in 0 until blocks) {
                val bw = (tileW * (0.12f + 0.05f * ((row + b) % 3))).toInt()
                if (x + bw > tileW * 0.94f) break
                g.fillRoundRect(x, y, bw, lineH / 2, 4, 4)
                x += bw + (tileW * 0.03f).toInt()
            }
            y += (lineH * 1.8f).toInt()
            row++
        }
        g.drawString(label, (tileW * 0.04f).toInt(), (tileH * 0.97f).toInt())
        g.dispose()
        return img.getRGB(0, 0, tileW, tileH, null, 0, tileW)
    }

    // ---------- 像素工具 ----------

    private fun pack(r: Float, g: Float, b: Float): Int {
        fun c(v: Float) = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        return (0xFF shl 24) or (c(r) shl 16) or (c(g) shl 8) or c(b)
    }

    private fun mul(c: Int, k: Float): Int = pack(
        (((c shr 16) and 0xFF) / 255f) * k,
        (((c shr 8) and 0xFF) / 255f) * k,
        ((c and 0xFF) / 255f) * k,
    )

    private fun blend(a: Int, b: Int, t: Float): Int {
        fun ch(c: Int, s: Int) = ((c shr s) and 0xFF) / 255f
        return pack(
            ch(a, 16) + (ch(b, 16) - ch(a, 16)) * t,
            ch(a, 8) + (ch(b, 8) - ch(a, 8)) * t,
            ch(a, 0) + (ch(b, 0) - ch(a, 0)) * t,
        )
    }

    private fun sample(tex: IntArray, x: Float, y: Float): Int {
        val ix = x.toInt().coerceIn(0, tileW - 1)
        val iy = y.toInt().coerceIn(0, tileH - 1)
        return tex[iy * tileW + ix]
    }

    // ---------- A 当前生产模型（正交铰链，逐分支对齐 HINGE_AGSL） ----------

    private fun renderHingeOrtho(front: IntArray, under: IntArray, p: Float): IntArray {
        val r = HingeSoftwareRenderer(tileW, tileH, front, under)
        val sheet = singleHingeSheet(tileW.toFloat())
        r.setProgress(p, sheet, tileH / 2f, 0f, hingeSliverFade(p))
        r.bend = creaseBend(Size(sheet.width, tileH.toFloat())) * hingeBendFade(p)
        return r.render()
    }

    // ---------- B 同模型 + 透视投影 ----------

    /**
     * 与 A 完全同一模型，只把投影从正交换成有限视距 D：
     * 纸面点（平铺距离 d、转角 φ）实际位置 x_p = spineX + side·d·cosφ、深度 z = d·sinφ，
     * 屏幕投影 X = vpX + (x_p - vpX)·D/(D - z)，反解 d 为闭式：
     * d = D·(X - spineX) / (sinφ·(X - vpX) + D·side·cosφ)
     * 纵向同样按 m = D/(D - d·sinφ) 缩放（近端被放大 → 梯形透视）。
     */
    private fun renderHingePerspective(
        front: IntArray,
        under: IntArray,
        p: Float,
        camDist: Float,
    ): IntArray {
        val out = IntArray(tileW * tileH)
        val sheet = singleHingeSheet(tileW.toFloat())
        val phi = hingeAngle(p)
        val cosPhi = max(cos(phi), 0.05f)
        val sinPhi = sin(phi)
        val lift = max(sinPhi, 0f)
        val reach = sheet.reach
        val side = sheet.side
        val spineX = sheet.spineX
        val vpX = tileW / 2f
        val vpY = tileH / 2f
        val bend = creaseBend(Size(sheet.width, tileH.toFloat())) * hingeBendFade(p)
        val fade = hingeSliverFade(p)
        for (y in 0 until tileH) {
            val Y = y + 0.5f
            for (x in 0 until tileW) {
                val X = x + 0.5f
                val i = y * tileW + x
                val denom = sinPhi * (X - vpX) + camDist * side * cosPhi
                val d = camDist * (X - spineX) / denom
                if (d <= 0f) {
                    out[i] = front[i]
                    continue
                }
                val m = camDist / (camDist - d * sinPhi)
                val etaFlat = (Y - vpY) / m
                val dEdge = reach - bend * etaFlat * etaFlat / tileH
                if (d < dEdge) {
                    val srcX = spineX + side * d
                    val srcY = vpY + etaFlat
                    val tex = sample(front, srcX, srcY)
                    val shade = 1f - 0.30f * lift.pow(1.5f)
                    val ao = 1f - 0.10f * exp(-(d - sheet.inner) / (sheet.width * 0.12f)) * lift
                    val edgeShade = 1f -
                        0.20f * exp(-((reach - d) / (sheet.width * 0.06f)).pow(2f)) * lift
                    out[i] = blend(
                        sample(under, X, Y),
                        mul(tex, shade * ao * edgeShade),
                        fade,
                    )
                } else if (d < reach) {
                    val past = d - dEdge
                    val sh = min(
                        0.55f * lift * fade *
                            (1f - exp(-past / (sheet.width * 0.05f))) *
                            exp(-past / (sheet.width * 0.20f)),
                        0.5f,
                    )
                    out[i] = mul(sample(under, X, Y), 1f - sh)
                } else {
                    out[i] = sample(front, X, Y)
                }
            }
        }
        return out
    }

    // ---------- C 圆柱卷曲参考 ----------

    /**
     * 纸张（弧长 L = 未卷长度）绕半径 R 的圆柱卷起，圆柱轴过折痕 x = creaseX（竖直）。
     * 弧长 s 处的点：θ = s/R，屏幕 x = creaseX + R·sinθ，高度 z = R·(1 - cosθ)。
     * 法线：正面 (-sinθ, cosθ)、背面 (sinθ, -cosθ)，按方向光做 Lambert + 高光。
     * 片元与轴的偏移 t 同时对应正面（θ = asin(t/R)）与背面（θ = π - asin(t/R)）两点；
     * 背面 z 更高故优先，且只有弧长仍在纸内（backArc ≤ L）才存在。
     * 折痕左侧是尚未离地的平铺纸面（当前页）；卷筒右侧露出下一页并承接卷筒投影。
     * 沿 y 不变，故按列计算。
     */
    private fun renderCurl(
        front: IntArray,
        under: IntArray,
        creaseX: Float,
        radius: Float,
    ): IntArray {
        val out = IntArray(tileW * tileH)
        val leafLen = tileW - creaseX
        // 方向光：自左上偏前；V = (0,1) 时的半程向量
        val lx = -0.42f
        val lz = 0.907f
        val hnx = -0.215f
        val hnz = 0.9765f
        val paperR = ((bg shr 16) and 0xFF) / 255f * 0.99f
        val paperG = ((bg shr 8) and 0xFF) / 255f * 0.95f
        val paperB = ((bg and 0xFF) / 255f) * 0.88f
        val right = creaseX + radius
        val left = creaseX - radius
        for (x in 0 until tileW) {
            val X = x + 0.5f
            val t = X - creaseX
            val a = asin((t / radius).coerceIn(-1f, 1f))
            val backArc = radius * (PI.toFloat() - a)
            val frontArc = radius * a
            when {
                backArc in 0f..leafLen -> {
                    val th = backArc / radius
                    val nx = sin(th)
                    val nz = -cos(th)
                    val lam = max(nx * lx + nz * lz, 0f)
                    val spec = max(nx * hnx + nz * hnz, 0f).pow(26f)
                    // 卷筒靠近触碰点处压暗（自遮蔽）
                    val ao = 1f - 0.10f * exp(-((X - right) / (radius * 0.5f)).pow(2f))
                    val k = (0.62f + 0.38f * lam + 0.20f * spec) * ao
                    val c = pack(paperR * k, paperG * k, paperB * k)
                    for (y in 0 until tileH) out[y * tileW + x] = c
                }

                t >= 0f && frontArc in 0f..leafLen -> {
                    val th = frontArc / radius
                    val nx = -sin(th)
                    val nz = cos(th)
                    val lam = max(nx * lx + nz * lz, 0f)
                    val spec = max(nx * hnx + nz * hnz, 0f).pow(26f)
                    val k = 0.66f + 0.34f * lam + 0.18f * spec
                    val sx = (creaseX + frontArc).toInt().coerceIn(0, tileW - 1)
                    for (y in 0 until tileH) out[y * tileW + x] = mul(front[y * tileW + sx], k)
                }

                t < 0f -> {
                    // 尚未离地的平铺页：折痕附近轻微 AO
                    val ao =
                        1f - 0.22f * exp(-((X - left) / (radius * 0.45f)).pow(2f))
                    for (y in 0 until tileH) out[y * tileW + x] = mul(front[y * tileW + x], ao)
                }

                else -> {
                    // 已揭示的下一页 + 卷筒向右投下的软阴影
                    val sh = (0.50f * exp(-(X - right) / (0.16f * tileW))).coerceIn(0f, 0.5f)
                    for (y in 0 until tileH) out[y * tileW + x] = mul(under[y * tileW + x], 1f - sh)
                }
            }
        }
        return out
    }

    // ---------- 出图 ----------

    private fun save(pixels: IntArray, name: String) {
        outDir.mkdirs()
        val img = BufferedImage(tileW, tileH, BufferedImage.TYPE_INT_ARGB)
        img.setRGB(0, 0, tileW, tileH, pixels, 0, tileW)
        ImageIO.write(img, "png", File(outDir, "$name.png"))
    }

    private fun saveSheet(rows: List<List<Pair<String, IntArray>>>, name: String) {
        outDir.mkdirs()
        val cols = rows.first().size
        val caption = 28
        val img = BufferedImage(
            tileW * cols,
            (tileH + caption) * rows.size,
            BufferedImage.TYPE_INT_ARGB,
        )
        val g = img.createGraphics()
        g.color = Color(24, 26, 24)
        g.fillRect(0, 0, img.width, img.height)
        for ((ri, row) in rows.withIndex()) {
            for ((ci, cell) in row.withIndex()) {
                val (cap, px) = cell
                val ox = ci * tileW
                val oy = ri * (tileH + caption)
                val tile = BufferedImage(tileW, tileH, BufferedImage.TYPE_INT_ARGB)
                tile.setRGB(0, 0, tileW, tileH, px, 0, tileW)
                g.drawImage(tile, ox, oy, null)
                g.color = Color(232, 232, 232)
                g.font = g.font.deriveFont(15f)
                g.drawString(cap, ox + 10, oy + tileH + 19)
            }
        }
        g.dispose()
        ImageIO.write(img, "png", File(outDir, "$name.png"))
    }

    @Test
    fun `render page turn model comparison sheet`() {
        val cur = makePage("P2")
        val next = makePage("P3")
        val cam = tileW * 2.2f
        // 三行取"已揭示面积接近"的等进度状态
        val hingeProgress = listOf(0.20f, 0.30f, 0.40f)
        val curlStates = listOf(
            0.78f * tileW to 0.10f * tileW,
            0.52f * tileW to 0.16f * tileW,
            0.20f * tileW to 0.22f * tileW,
        )
        val rows = hingeProgress.mapIndexed { i, p ->
            val (cx, r) = curlStates[i]
            val a = renderHingeOrtho(cur, next, p)
            val b = renderHingePerspective(cur, next, p, cam)
            val c = renderCurl(cur, next, cx, r)
            save(a, "A_hinge_p%02d".format((p * 100).toInt()))
            save(b, "B_hinge_persp_p%02d".format((p * 100).toInt()))
            save(c, "C_curl_%02d".format(i))
            listOf(
                "row${i + 1}  A 当前·铰链(正交)  p=%.2f".format(p) to a,
                "row${i + 1}  B 同模型+透视  p=%.2f".format(p) to b,
                "row${i + 1}  C 圆柱卷曲(参考)  crease=%.2fW R=%.2fW".format(
                    cx / tileW,
                    r / tileW,
                ) to c,
            )
        }
        saveSheet(rows, "compare_sheet")

        // 模型自证：A 在 p=0 时等于当前页；B 的透视参数下 d 的映射在视场内单调
        val zero = renderHingeOrtho(cur, next, 0f)
        var diff = 0
        for (i in zero.indices) if (zero[i] != cur[i]) diff++
        assertTrue("A 起手帧应等于当前页", diff.toDouble() / zero.size < 0.005)
        assertTrue("透视相机距离须大于页宽（避免卷到相机之后）", cam > tileW)
    }

    /**
     * 差距的硬指标：卷曲模型里折痕左侧的当前页**逐像素不变形**（读者正在读的文字
     * 不会因为起手而重排/挤压）；铰链模型是整页绕轴旋转，从起手那一刻起全页横向
     * 压缩（p=0.20 即 cos36° = 0.81，整页压到 81%）。
     */
    @Test
    fun `only the curl model keeps the current page undistorted`() {
        val cur = makePage("P2")
        val next = makePage("P3")
        val probeX = (tileW * 0.2f).toInt() // 两种模型下都在"当前页"里，且远离折痕

        fun columnDiff(frame: IntArray): Double {
            var diff = 0
            for (y in 0 until tileH) {
                val a = frame[y * tileW + probeX]
                val b = cur[y * tileW + probeX]
                if (a != b) diff++
            }
            return diff.toDouble() / tileH
        }

        val curl = renderCurl(cur, next, 0.78f * tileW, 0.10f * tileW)
        assertTrue("卷曲模型：折痕左侧当前页应与原图逐像素一致", columnDiff(curl) == 0.0)

        for (p in listOf(0.20f, 0.30f, 0.40f)) {
            val hinge = renderHingeOrtho(cur, next, p)
            assertTrue(
                "铰链模型 p=%.2f：整页已被压缩，探针列应已变形".format(p),
                columnDiff(hinge) > 0.5,
            )
        }
    }
}

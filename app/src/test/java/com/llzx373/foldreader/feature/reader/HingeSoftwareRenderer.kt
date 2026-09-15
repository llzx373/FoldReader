package com.llzx373.foldreader.feature.reader

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * HINGE_AGSL（PageCurlShader.kt）的逐分支 JVM 镜像：同一套铰轴投影/片元分类/
 * 明暗/阴影公式，软渲染成像素数组，用于离线检查双页翻页（页背落对侧页）长相。
 *
 * ⚠️ 与 PageCurlShader.kt 中的 HINGE_AGSL 必须保持分支级同步；
 * 几何（hingeSheetFor / singleHingeSheet / hingeAngle / hingeAxis）直接复用主代码。
 * 覆盖双页整程（页背落对侧页）与单页前半程（垂直位落账，sheetFade 收尾淡出）。
 */
class HingeSoftwareRenderer(
    val width: Int,
    val height: Int,
    /** ARGB 纹理，尺寸 width×height；CLAMP 边缘采样。 */
    val front: IntArray,
    val under: IntArray,
) {
    var spineX = width / 2f
    var spineY = height / 2f
    var axisX = 0f
    var axisY = 1f
    var side = 1f
    var cosPhi = 1f
    var sinPhi = 0f
    var sheetG = 0f
    var sheetW = width / 2f
    var shadowStrength = 0.55f
    var bend = 0f
    var sheetFade = 1f

    /** 按主代码 HingeOverlay 的方式设置进度（含垂直位钳制；tilt/sheetFade 与调用侧一致）。 */
    fun setProgress(p: Float, sheet: HingeSheet, startY: Float, tilt: Float, fade: Float) {
        val phi = hingeAngle(p)
        val rawCos = cos(phi.toDouble()).toFloat()
        // 与主代码一致：按进度选侧钳制（cos(π/2) 浮点误差为负，不能按符号判断）
        cosPhi = if (p <= 0.5f) {
            rawCos.coerceAtLeast(0.05f)
        } else {
            rawCos.coerceAtMost(-0.05f)
        }
        sinPhi = sin(phi.toDouble()).toFloat()
        spineX = sheet.spineX
        spineY = startY.coerceIn(0f, height.toFloat())
        val axis = hingeAxis(tilt)
        axisX = axis.x
        axisY = axis.y
        side = sheet.side
        sheetG = sheet.inner
        sheetW = sheet.width
        sheetFade = fade.coerceIn(0f, 1f)
    }

    private fun sample(tex: IntArray, x: Float, y: Float): Int {
        val ix = x.toInt().coerceIn(0, width - 1)
        val iy = y.toInt().coerceIn(0, height - 1)
        return tex[iy * width + ix]
    }

    private fun rgb(c: Int): FloatArray = floatArrayOf(
        ((c shr 16) and 0xFF) / 255f,
        ((c shr 8) and 0xFF) / 255f,
        (c and 0xFF) / 255f,
    )

    private fun pack(r: Float, g: Float, b: Float): Int {
        fun c(v: Float) = (v.coerceIn(0f, 1f) * 255f).toInt()
        return (0xFF shl 24) or (c(r) shl 16) or (c(g) shl 8) or c(b)
    }

    private fun shadowProfile(past: Float, lift: Float, fade: Float = 1f): Float = min(
        shadowStrength * lift * fade * (1f - exp(-past / (sheetW * 0.05f))) *
            exp(-past / (sheetW * 0.20f)),
        0.5f,
    )

    fun render(): IntArray {
        val out = IntArray(width * height)
        val nX = axisY * side
        val nY = -axisX * side
        val lift = sinPhi.coerceAtLeast(0f)
        val reach = sheetG + sheetW
        for (fy in 0 until height) {
            for (fx in 0 until width) {
                val relX = fx - spineX
                val relY = fy - spineY
                val eta = relX * axisX + relY * axisY
                val delta = relX * nX + relY * nY
                val edge = reach * cosPhi - bend * eta * eta / height * abs(cosPhi)
                val inner = sheetG * cosPhi

                val result: FloatArray
                if (cosPhi > 0f) {
                    if (delta >= inner && delta < edge) {
                        // 纸张正面（透视压缩）
                        val d = delta / cosPhi
                        val srcX = spineX + nX * d + axisX * eta
                        val srcY = spineY + nY * d + axisY * eta
                        val tex = rgb(sample(front, srcX, srcY))
                        val shade = 1f - 0.30f * lift.toDouble().pow(1.5).toFloat()
                        val ao = 1f - 0.10f * exp(-(d - sheetG) / (sheetW * 0.12f)) * lift
                        val ridge = exp(
                            -((reach - d) / (sheetW * 0.10f)).pow(2),
                        ) * 0.20f * lift
                        val base = rgb(sample(under, fx.toFloat(), fy.toFloat()))
                        result = floatArrayOf(
                            base[0] + (min(tex[0] * shade * ao + ridge, 1f) - base[0]) * sheetFade,
                            base[1] + (min(tex[1] * shade * ao + ridge, 1f) - base[1]) * sheetFade,
                            base[2] + (min(tex[2] * shade * ao + ridge, 1f) - base[2]) * sheetFade,
                        )
                    } else if (delta >= edge && delta < reach) {
                        val sh = shadowProfile(delta - edge, lift, sheetFade)
                        val t = rgb(sample(under, fx.toFloat(), fy.toFloat()))
                        result = floatArrayOf(t[0] * (1f - sh), t[1] * (1f - sh), t[2] * (1f - sh))
                    } else {
                        result = rgb(sample(front, fx.toFloat(), fy.toFloat()))
                    }
                } else if (delta >= edge && delta < inner) {
                    // 纸张背面（落地区）：绕轴镜像采样目标跨页另半页
                    val d = delta / cosPhi
                    val bsX = spineX - nX * d + axisX * eta
                    val bsY = spineY - nY * d + axisY * eta
                    val tex = rgb(sample(under, bsX, bsY))
                    val shade = 1f - 0.25f * lift.toDouble().pow(1.4).toFloat()
                    val ao = 1f - 0.10f * exp(-(d - sheetG) / (sheetW * 0.12f)) * lift
                    val grad = 1f - 0.12f * ((d - sheetG) / sheetW) * lift
                    result = floatArrayOf(
                        tex[0] * shade * ao * grad,
                        tex[1] * shade * ao * grad,
                        tex[2] * shade * ao * grad,
                    )
                } else if (delta >= inner && delta < reach) {
                    val sh = shadowStrength * 0.5f * lift * exp(-delta / (sheetW * 0.15f))
                    val t = rgb(sample(under, fx.toFloat(), fy.toFloat()))
                    result = floatArrayOf(t[0] * (1f - sh), t[1] * (1f - sh), t[2] * (1f - sh))
                } else {
                    val past = edge - delta
                    val sh = if (past > 0f) shadowProfile(past, lift) else 0f
                    val c = rgb(sample(front, fx.toFloat(), fy.toFloat()))
                    result = floatArrayOf(c[0] * (1f - sh), c[1] * (1f - sh), c[2] * (1f - sh))
                }
                out[fy * width + fx] = pack(result[0], result[1], result[2])
            }
        }
        return out
    }
}

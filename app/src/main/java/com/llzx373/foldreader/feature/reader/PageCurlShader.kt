package com.llzx373.foldreader.feature.reader

import android.graphics.Bitmap
import android.graphics.RuntimeShader
import android.graphics.Shader
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 圆柱面卷曲（参考 harism/android_page_curl 的 2D 模型，Apache 2.0）。
 *
 * 卷曲由三参数描述：卷曲线上一点 P（pointer）、卷曲展开方向 D（direction，
 * 单位向量，指向当前页仍平铺的一侧）、半径 R（radius，约 0.06×页宽）。
 * 片元先投影到局部坐标 lx = dot(frag - P, D)：
 * - lx >= 0：平区，正常采样正面纹理，靠近卷曲线轻微压暗（环境光遮蔽）；
 * - -πR < lx < 0：弯曲区，按弧长展开镜像采样（src = P + perp·ly - D·lx），
 *   卷曲角 θ = -lx/R，θ<π/2 为正面圆柱外侧（cos 明暗 + 脊线高光），
 *   θ>=π/2 为背面（镜像采样与 backColor 混合压暗）；
 * - lx <= -πR：完全翻过去的背面区，同样镜像采样 + backColor 压暗；
 * - 镜像采样坐标越出页边界：纸张已翻离，输出透明（露出下层目标页），
 *   紧邻纸张边缘的条带输出半透明黑作为投向目标页的软阴影。
 */
private const val CURL_AGSL = """
uniform shader page;
uniform float2 resolution;
uniform float2 pointer;
uniform float2 direction;
uniform float radius;
layout(color) uniform float4 backColor;
uniform float shadowStrength;

const float PI = 3.14159265;
const float HALF_PI = 1.5707963;

half4 main(float2 fragCoord) {
    float2 perp = float2(-direction.y, direction.x);
    float2 rel = fragCoord - pointer;
    float lx = dot(rel, direction);
    float ly = dot(rel, perp);

    if (lx >= 0.0) {
        half4 c = page.eval(fragCoord);
        float ao = 1.0 - 0.10 * exp(-lx / (radius * 1.5));
        return half4(c.rgb * ao, 1.0);
    }

    float theta = clamp(-lx / radius, 0.0, PI);
    float2 src = pointer + perp * ly - direction * lx;

    bool outX = src.x < 0.0 || src.x >= resolution.x;
    bool outY = src.y < 0.0 || src.y >= resolution.y;
    if (outX || outY) {
        if (outY) { return half4(0.0, 0.0, 0.0, 0.0); }
        float past = max(-src.x, src.x - resolution.x);
        float a = shadowStrength * exp(-past / (radius * 3.0));
        return half4(0.0, 0.0, 0.0, a);
    }

    half4 tex = page.eval(src);
    if (theta < HALF_PI) {
        float shade = mix(1.0, 0.55, theta / HALF_PI);
        float ridge = exp(-pow((theta - 0.9) * 3.0, 2.0));
        return half4(min(tex.rgb * shade + ridge * 0.30, half3(1.0)), 1.0);
    }
    float bt = clamp((theta - HALF_PI) / HALF_PI, 0.0, 1.0);
    float dark = mix(0.80, 0.55, bt);
    float3 rgb = mix(float3(tex.rgb), backColor.rgb, 0.55) * dark;
    return half4(rgb, 1.0);
}
"""

data class CurlParams(
    val pointer: Offset,
    val direction: Offset,
    val radiusPx: Float,
)

fun curlRadiusPx(pageSize: Size): Float = pageSize.width * 0.06f

/** 拖拽映射：P = 手指实时坐标，D = 拖拽方向（页面向该方向展开，可斜向撕页）。 */
fun curlParamsFromPointer(
    start: Offset,
    current: Offset,
    pageSize: Size,
    forward: Boolean,
): CurlParams {
    val delta = current - start
    val fallback = if (forward) Offset(-1f, 0f) else Offset(1f, 0f)
    val direction = if (delta.getDistance() < 1f) fallback else delta / delta.getDistance()
    return CurlParams(pointer = current, direction = direction, radiusPx = curlRadiusPx(pageSize))
}

/**
 * 点击/弹簧收尾的虚拟指针路径：向前翻从右缘（x=W）走到左缘外（x<0），
 * 向后翻镜像；纵向带轻微弧度。
 */
fun pointerForProgress(progress: Float, forward: Boolean, pageSize: Size): Offset {
    val p = progress.coerceIn(0f, 1f)
    val x = if (forward) {
        pageSize.width * (1f - 1.02f * p)
    } else {
        pageSize.width * (1.04f * p - 0.02f)
    }
    val arc = sin(p * Math.PI).toFloat() * pageSize.height * 0.08f
    val y = pageSize.height * 0.5f + if (forward) -arc else arc
    return Offset(x, y)
}

/** 弹簧收尾阶段：从松手时的真实指针插值到虚拟路径，避免跳变。 */
fun settlingPointer(
    from: Offset,
    progress: Float,
    settleStartProgress: Float,
    completing: Boolean,
    forward: Boolean,
    pageSize: Size,
): Offset {
    val t = if (completing) {
        (progress - settleStartProgress) / (1f - settleStartProgress).coerceAtLeast(0.001f)
    } else {
        (settleStartProgress - progress) / settleStartProgress.coerceAtLeast(0.001f)
    }.coerceIn(0f, 1f)
    val target = pointerForProgress(progress, forward, pageSize)
    return from + (target - from) * t
}

/**
 * 卷曲覆盖层：下层平铺目标对页位图，上层 RuntimeShader 按圆柱模型卷曲当前对页位图。
 * 位图尺寸须与布局尺寸一致（1:1 像素映射）。
 */
@Composable
fun CurlOverlay(
    front: Bitmap,
    target: Bitmap,
    pointer: Offset,
    direction: Offset,
    radiusPx: Float,
    backColor: Color,
    modifier: Modifier = Modifier,
) {
    val shader = remember { RuntimeShader(CURL_AGSL) }
    val pageShader = remember(front) {
        android.graphics.BitmapShader(front, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    }
    val targetImage = remember(target) { target.asImageBitmap() }
    Canvas(modifier = modifier) {
        drawImage(targetImage, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()))
        shader.setFloatUniform("resolution", size.width, size.height)
        shader.setFloatUniform("pointer", pointer.x, pointer.y)
        shader.setFloatUniform("direction", direction.x, direction.y)
        shader.setFloatUniform("radius", radiusPx)
        shader.setColorUniform("backColor", backColor.toArgb())
        shader.setFloatUniform("shadowStrength", 0.35f)
        shader.setInputBuffer("page", pageShader)
        drawRect(brush = ShaderBrush(shader), size = size)
    }
}

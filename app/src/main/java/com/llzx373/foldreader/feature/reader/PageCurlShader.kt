package com.llzx373.foldreader.feature.reader

import android.graphics.Bitmap
import android.graphics.RuntimeShader
import android.graphics.Shader
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asImageBitmap
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin

/**
 * 铰链翻页：纸张绕书脊轴旋转（φ = π·progress），单页/双页共用同一模型。
 *
 * 双页（展开态）走整程 p∈[0,1]：前半程正面透视压缩、后半程页背落到对侧页
 * 上——页背内容恰好是目标跨页的另半页（绕轴镜像采样 under），落定时画面
 * 自然等于目标跨页，无需交叉淡入补丁。
 *
 * 单页（折叠态/强制单栏）只走前半程 p∈[0,0.5]：纸张绕左缘轴转到垂直位即
 * 完成（垂直位投影宽度为 0，屏上恰好全是目标页，落账无缝）。自由边全程横跨
 * 整个页宽，拖拽始终跟手；不需要后半程，也就不需要页背内容。后退翻 = 同一
 * 几何的逆放（p 从 0.5 落回 0），由调用方交换 page/under 纹理使纸张正面
 * 始终显示翻入的那一页。
 *
 * 坐标：铰轴上一点 spine、轴方向 axis（单位向量，单页/双页均固定竖直——
 * 双页对准书脊中缝，单页对齐页左缘）、side=±1（纸张起手一侧）。
 * n = perp(axis)·side 指向纸张；片元投影 delta = dot(frag - spine, n)，
 * eta = dot(frag - spine, axis)。纸张占据平铺距离 d ∈ [sheetG, sheetG+sheetW]
 * （sheetG = 轴到纸张内缘的距离，即铰链缝半宽；单页为 0），投影后
 * delta = d·cosPhi。自由边 edge = (sheetG+sheetW)·cosPhi 再减抛物线弯曲项
 * （页角领先，随 |cosPhi| 缩放、垂直位归零、首尾随 bend 淡入出）。
 *
 * 片元分类：
 * - 前半程（cosPhi>0）：delta ∈ [sheetG·cos, edge) 为纸张正面（d=delta/cos 取样
 *   page，明暗∝sinφ + 书脊 AO + 自由边高光脊）；delta ∈ [edge, sheetG+sheetW)
 *   为已揭示区（under + 自由边投影，渐入再衰减剖面，强度∝sinφ）；其余为静止区
 *   （page 原位：对侧页 + 铰链缝 + 外边距）；
 * - 后半程（cosPhi<0，仅双页到达）：delta ∈ [edge, sheetG·cos) 为纸张背面
 *   （d=delta/cos，绕轴镜像采样 under 的另半页 = 真实的"页背"内容）；
 *   delta ∈ [sheetG·cos, sheetG+sheetW) 为原侧已腾空区（under + 轴侧余影）；
 *   delta < edge 为落地侧未覆盖区（page 原位 + 落地边前方投影）。
 *
 * sheetFade（仅单页使用，双页恒 1）：接近垂直位时把纸张窄条与自由边投影
 * 向下层淡出——单页在 p=0.5 落账，垂直位的压缩窄条/最大强度投影若不衰减，
 * 切换到静态页时会跳变。
 *
 * 双页 p=0：纸张正面 1:1 覆盖原侧 → 全屏=当前跨页；p=1：背面 1:1 覆盖对侧
 * 页、原侧揭示 → 全屏=目标跨页。cosPhi 由 Kotlin 侧钳制到 |cos|≥0.05，
 * 垂直位无除零。
 */
private const val HINGE_AGSL = """
uniform shader page;
uniform shader under;
uniform float2 resolution;
uniform float2 spine;
uniform float2 axis;
uniform float side;
uniform float cosPhi;
uniform float sinPhi;
uniform float sheetG;
uniform float sheetW;
uniform float shadowStrength;
uniform float bend;
uniform float sheetFade;

half4 main(float2 fragCoord) {
    float2 n = float2(axis.y, -axis.x) * side;
    float2 rel = fragCoord - spine;
    float eta = dot(rel, axis);
    float delta = dot(rel, n);
    float lift = max(sinPhi, 0.0);
    float reach = sheetG + sheetW;
    float edge = reach * cosPhi - bend * eta * eta / resolution.y * abs(cosPhi);
    float inner = sheetG * cosPhi;

    if (cosPhi > 0.0) {
        if (delta >= inner && delta < edge) {
            // 纸张正面（透视压缩）
            float d = delta / cosPhi;
            float2 src = spine + n * d + axis * eta;
            half4 tex = page.eval(src);
            float shade = 1.0 - 0.30 * pow(lift, 1.5);
            float ao = 1.0 - 0.10 * exp(-(d - sheetG) / (sheetW * 0.12)) * lift;
            // 自由边高光：乘法项（随纸色缩放，浅色主题不再烧成白边），范围收窄、强度减弱
            float ridge = exp(-pow((reach - d) / (sheetW * 0.06), 2.0)) * 0.14 * lift;
            half3 frontRgb = min(tex.rgb * (shade * ao + ridge), half3(1.0));
            // 单页收尾：垂直位窄条向下层淡出（双页 sheetFade 恒 1，无影响）
            half3 base = under.eval(fragCoord).rgb;
            return half4(base + (frontRgb - base) * half(sheetFade), 1.0);
        }
        if (delta >= edge && delta < reach) {
            // 已揭示：目标页 + 自由边投影（渐入再衰减，单页收尾随 sheetFade 淡出）
            float past = delta - edge;
            float sh = shadowStrength * lift * sheetFade
                * (1.0 - exp(-past / (sheetW * 0.05)))
                * exp(-past / (sheetW * 0.20));
            sh = min(sh, 0.5);
            return half4(under.eval(fragCoord).rgb * (1.0 - sh), 1.0);
        }
        // 静止区：对侧页 / 铰链缝 / 外边距原位
        return half4(page.eval(fragCoord).rgb, 1.0);
    }

    if (delta >= edge && delta < inner) {
        // 纸张背面（落地区）：绕轴镜像采样目标跨页另半页
        float d = delta / cosPhi;
        float2 bsrc = spine - n * d + axis * eta;
        float shade = 1.0 - 0.25 * pow(lift, 1.4);
        float ao = 1.0 - 0.10 * exp(-(d - sheetG) / (sheetW * 0.12)) * lift;
        float grad = 1.0 - 0.12 * ((d - sheetG) / sheetW) * lift;
        return half4(under.eval(bsrc).rgb * shade * ao * grad, 1.0);
    }
    if (delta >= inner && delta < reach) {
        // 原侧已腾空：目标跨页 + 轴侧余影
        float sh = shadowStrength * 0.5 * lift * exp(-delta / (sheetW * 0.15));
        return half4(under.eval(fragCoord).rgb * (1.0 - sh), 1.0);
    }
    // 落地侧未覆盖：当前跨页原位 + 落地边前方投影
    float past = edge - delta;
    float sh = 0.0;
    if (past > 0.0) {
        sh = shadowStrength * lift
            * (1.0 - exp(-past / (sheetW * 0.05)))
            * exp(-past / (sheetW * 0.20));
        sh = min(sh, 0.5);
    }
    return half4(page.eval(fragCoord).rgb * (1.0 - sh), 1.0);
}
"""

/**
 * 铰链纸张：铰轴位置 spineX（双页=两页内缘的中点，单页=页左缘）、轴到纸张
 * 内缘的距离 inner（铰链缝半宽，单页为 0）、纸张宽 width、起手侧 side
 * （双页前进=+1 翻右页、后退=-1 翻左页；单页恒 +1，后退靠交换纹理实现）。
 * 页背绕轴镜像即目标跨页另半页，落地无缝。
 */
data class HingeSheet(
    val spineX: Float,
    val inner: Float,
    val width: Float,
    val side: Float,
) {
    /** 轴到纸张外缘（自由边）的距离。 */
    val reach: Float get() = inner + width
}

/** 双页铰链纸张：轴心在两页内缘中点，保证 p=0 恰好覆盖本页、p=1 页背恰好覆盖对侧页。 */
fun hingeSheetFor(
    forward: Boolean,
    pageWidthPx: Float,
    leftInsetPx: Float,
    splitRightPx: Float,
    rightInsetPx: Float,
): HingeSheet {
    val leftInner = leftInsetPx + pageWidthPx
    val rightInner = splitRightPx + rightInsetPx
    return HingeSheet(
        spineX = (leftInner + rightInner) / 2f,
        inner = (rightInner - leftInner) / 2f,
        width = pageWidthPx,
        side = if (forward) 1f else -1f,
    )
}

/** 单页铰链纸张：绕页左缘轴旋转，只走前半程（p∈[0,0.5]，垂直位即完成）。 */
fun singleHingeSheet(pageWidthPx: Float): HingeSheet =
    HingeSheet(spineX = 0f, inner = 0f, width = pageWidthPx, side = 1f)

/** 铰链转角：φ = π·p。 */
fun hingeAngle(progress: Float): Float =
    Math.PI.toFloat() * progress.coerceIn(0f, 1f)

/** 自由边的 x 坐标（拖拽锚定/接管飞行中页面用）。 */
fun hingeFreeEdgeX(sheet: HingeSheet, progress: Float): Float =
    sheet.spineX + sheet.side * sheet.reach * cos(hingeAngle(progress).toDouble()).toFloat()

/**
 * 拖拽进度：自由边跟手，由手指 x 反解 φ。maxProgress 限制行程上限
 * （双页=1 整程；单页=0.5 只走前半程，手指拖过轴心后钳在垂直位）。
 */
fun hingeProgressFor(fingerX: Float, sheet: HingeSheet, maxProgress: Float = 1f): Float {
    val c = (sheet.side * (fingerX - sheet.spineX) / sheet.reach).coerceIn(-1f, 1f)
    return (acos(c.toDouble()) / Math.PI).toFloat().coerceIn(0f, maxProgress)
}

/** 起手锚定：令起手时刻自由边落在纸张外缘（p=0），随手指相对位移移动。 */
fun hingeAnchorOffsetX(sheet: HingeSheet, grabX: Float): Float =
    hingeFreeEdgeX(sheet, 0f) - grabX

/**
 * 拖拽跟手的锚定衰减：起手时自由边锚定在纸张外缘（锚定量 anchorX，避免
 * 起手瞬间画面跳变），随手指在翻向行程的前约 35% 内把锚定量平滑衰减到 0
 * ——自由边逐渐追上手指，之后精确贴在触点下（直接映射）。traveledInFlipDir
 * 是手指沿翻页方向已拖过的距离（反向不计）。
 */
fun hingeCatchUpAnchor(anchorX: Float, traveledInFlipDir: Float, reach: Float): Float {
    val t = (1f - traveledInFlipDir.coerceAtLeast(0f) / (reach * 0.35f)).coerceIn(0f, 1f)
    return anchorX * t
}

/**
 * 铰轴方向（单位向量）：竖直轴按 tilt 偏转；单页/双页均传 0——转轴固定
 * 竖直（双页对准书脊中缝分页线，单页对齐页左缘）。
 */
fun hingeAxis(tilt: Float): Offset {
    val d = Offset(tilt, 1f)
    return d / d.getDistance()
}

/** 折线弯曲系数：页缘相对页中线滞后约 6% 页宽，页角先起。 */
fun creaseBend(pageSize: Size): Float = 0.24f * pageSize.width / pageSize.height

private fun smoothStep(e0: Float, e1: Float, x: Float): Float {
    val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/**
 * 自由边弯曲的首尾淡入淡出：弯曲项使 p=0 时页角也有翻起区域（抓取即见
 * 翘起）、双页 p=1 时页角无法精确贴合（落账残留），两端各淡出到 0，中段全量。
 */
fun hingeBendFade(progress: Float): Float =
    smoothStep(0f, 0.08f, progress) * (1f - smoothStep(0.90f, 1f, progress))

/**
 * 单页收尾淡出：只在接近垂直位（p→0.5）时把纸张窄条与自由边投影向下层
 * 淡出，使 p=0.5 的画面恰好等于目标页，落账无跳变。双页恒传 1（p=0.5 是
 * 飞行中段，不需要淡出）。
 */
fun hingeSliverFade(progress: Float): Float =
    1f - smoothStep(0.46f, 0.50f, progress)

/**
 * 铰链翻页覆盖层：front=当前页/跨页位图（纸张正面 + 静止区），target=目标
 * 位图（揭示区 + 纸张背面纹理）。单页后退翻由调用方交换 front/target，
 * 使纸张正面始终显示翻入的那一页。位图尺寸须与布局尺寸一致（1:1 像素映射）。
 * shader 编译失败（AGSL 运行时编译）时降级为交叉淡入并回调 onShaderFailed。
 */
@Composable
fun HingeOverlay(
    front: Bitmap,
    target: Bitmap,
    progress: Float,
    sheet: HingeSheet,
    startY: Float,
    tilt: Float,
    sheetFade: Float,
    bendPx: Float,
    onShaderFailed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shader = remember { runCatching { RuntimeShader(HINGE_AGSL) }.getOrNull() }
    val pageShader = remember(front) {
        android.graphics.BitmapShader(front, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    }
    val underShader = remember(target) {
        android.graphics.BitmapShader(target, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    }
    if (shader == null) {
        LaunchedEffect(Unit) { onShaderFailed() }
        Canvas(modifier = modifier) {
            drawImage(front.asImageBitmap())
            drawImage(target.asImageBitmap(), alpha = progress.coerceIn(0f, 1f))
        }
        return
    }
    Canvas(modifier = modifier) {
        val phi = hingeAngle(progress)
        val rawCos = cos(phi.toDouble()).toFloat()
        // 垂直位钳制：纸张缩成书脊处一条窄缝，避免除零与极端压缩的采样走样。
        // 按进度而不是 rawCos 符号选侧：cos(π/2) 的浮点误差是负的，若按符号
        // 钳制会把单页落账帧（p=0.5）误判进后半程分支
        val cosPhi = if (progress <= 0.5f) {
            rawCos.coerceAtLeast(0.05f)
        } else {
            rawCos.coerceAtMost(-0.05f)
        }
        val pivotY = startY.coerceIn(0f, size.height)
        val axis = hingeAxis(tilt)
        shader.setFloatUniform("resolution", size.width, size.height)
        shader.setFloatUniform("spine", sheet.spineX, pivotY)
        shader.setFloatUniform("axis", axis.x, axis.y)
        shader.setFloatUniform("side", sheet.side)
        shader.setFloatUniform("cosPhi", cosPhi)
        shader.setFloatUniform("sinPhi", sin(phi.toDouble()).toFloat())
        shader.setFloatUniform("sheetG", sheet.inner)
        shader.setFloatUniform("sheetW", sheet.width)
        shader.setFloatUniform("shadowStrength", 0.55f)
        shader.setFloatUniform("bend", bendPx)
        shader.setFloatUniform("sheetFade", sheetFade.coerceIn(0f, 1f))
        shader.setInputBuffer("page", pageShader)
        shader.setInputBuffer("under", underShader)
        drawRect(brush = ShaderBrush(shader), size = size)
    }
}

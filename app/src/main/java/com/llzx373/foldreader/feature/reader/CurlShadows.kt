package com.llzx373.foldreader.feature.reader

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color

/**
 * 卷曲阴影分层（纯函数，可在纯 JVM 单测里跑）。
 *
 * 全部是"距折痕的带"（见 [curlBandPolygon]），因此形状随折痕方向自动倾斜、且恒被叶片
 * 矩形裁过（双页时不会糊到中缝另一侧）。各带的边界直接取自 [CurlRoll] 的几何分界：
 * - [CurlRoll.flatEdge]：平铺页与"已翻起部分"的分界；
 * - [CurlRoll.rollEdge]：整块翻起部分的外缘，往外就是露出的下层页；
 * - [CurlRoll.backMin]～[CurlRoll.backMax]：纸背可见区间（θ > 90° 才出现）。
 *
 * 图层顺序：投到下层页的阴影必须画在**纸张之前**（它落在翻起部分之外，不被纸覆盖）；
 * 纸面自身的折痕阴影与纸背明暗画在**纸张之后**。
 */

enum class CurlShadowLayer { UNDER_PAGE, SHEET }

/** 一条阴影带：[polygon] 是裁剪后的凸多边形，[from]→[to] 是线性渐变的起止点。 */
data class CurlShadowBand(
    val polygon: List<Offset>,
    val from: Offset,
    val to: Offset,
    val startColor: Color,
    val endColor: Color,
    val layer: CurlShadowLayer,
)

/** 阴影用色。默认取国内阅读 App 仿真翻页的观感量级，由主题派生时按背景明度微调。 */
data class CurlShadowPalette(
    val crease: Color = Color.Black.copy(alpha = 0.22f),
    /** 下层页上的环境暗化：必须**又宽又淡**，否则会变成一条勾勒动画区域轮廓的线。 */
    val cast: Color = Color.Black.copy(alpha = 0.10f),
    /** 翻页书页自由边处的暗边——那是纸的边，不是高光。 */
    val edge: Color = Color.Black.copy(alpha = 0.16f),
    /** 纸背的圆柱明暗：让纸背读起来是卷起来的圆柱而不是一块平色。 */
    val rollShade: Color = Color.Black.copy(alpha = 0.12f),
)

/** 各阴影带的宽度（px）。抽成单一来源，绘制层与离线出图共用，避免两处漂移。 */
data class CurlShadowWidths(
    val crease: Float,
    val sheet: Float,
    val cast: Float,
    val edge: Float,
)

/** 折痕处可见的纸边宽度（纸的厚度）。占了圆角半径的一个固定比例。 */
fun curlFoldEdgeWidth(roll: CurlRoll): Float =
    (roll.bendRadius * FOLD_EDGE_RATIO).coerceAtLeast(1.5f)

/**
 * 折痕处的纸边白带：贴折痕最亮、向外渐隐，用纸背色画。
 *
 * 这是"真纸"最直接的观感来源——纸有厚度，折过来时折痕处能看到一条纸的断面。
 * 纯几何模型给不出它（θ < 90° 时纸背确实不可见），所以按观感补一条。
 * 调用方须把它画在**纸张与阴影之后**，否则会被折痕阴影压灰。
 */
fun curlFoldEdgeBand(
    roll: CurlRoll,
    frame: CurlFrame,
    pageBackColor: Color,
): CurlShadowBand? {
    if (roll.degenerate) return null
    val width = curlFoldEdgeWidth(roll)
    val poly = curlBandPolygon(roll, frame, 0f, width)
    if (poly.size < 3) return null
    val d = roll.dir
    fun at(t: Float) = Offset(roll.crease.x + d.x * t, roll.crease.y + d.y * t)
    return CurlShadowBand(
        polygon = poly,
        from = at(0f),
        to = at(width),
        startColor = pageBackColor,
        endColor = Color.Transparent,
        layer = CurlShadowLayer.SHEET,
    )
}

/** 折痕纸边宽度占圆角半径的比例。 */
const val FOLD_EDGE_RATIO: Float = 0.30f

/**
 * 按折痕圆角半径推各带宽度。
 *
 * 折痕与纸边取**很窄**的尺度（贴边的一层薄过渡）；下层页上的环境暗化反过来要**很宽**：
 * 一旦做成等宽等浓的窄带，视觉上就变成"两条平行线把动画区域围起来"，完全不像阴影。
 */
fun curlShadowWidths(roll: CurlRoll): CurlShadowWidths {
    val s = curlShadowScale(roll)
    return CurlShadowWidths(
        crease = (s * 0.45f).coerceIn(6f, 20f),
        sheet = (s * 0.50f).coerceIn(4f, 22f),
        cast = (s * 3.20f).coerceIn(40f, 240f),
        edge = (s * 0.80f).coerceIn(6f, 36f),
    )
}

/**
 * 生成当前卷曲状态下的全部分层阴影。
 */
fun curlShadowBands(
    roll: CurlRoll,
    frame: CurlFrame,
    palette: CurlShadowPalette = CurlShadowPalette(),
): List<CurlShadowBand> {
    if (roll.degenerate) return emptyList()
    val w = curlShadowWidths(roll)
    val transparent = Color.Transparent
    val d = roll.dir
    fun at(t: Float) = Offset(roll.crease.x + d.x * t, roll.crease.y + d.y * t)

    val out = ArrayList<CurlShadowBand>(5)

    fun addBand(
        fromT: Float,
        toT: Float,
        startColor: Color,
        endColor: Color,
        layer: CurlShadowLayer,
    ) {
        if (toT <= fromT) return
        val poly = curlBandPolygon(roll, frame, fromT, toT)
        if (poly.size < 3) return
        out.add(
            CurlShadowBand(
                polygon = poly,
                from = at(fromT),
                to = at(toT),
                startColor = startColor,
                endColor = endColor,
                layer = layer,
            ),
        )
    }

    // 1. 翻起部分投到下层页上的主体阴影：从外缘向外渐隐（画在纸张之前）
    addBand(
        fromT = roll.rollEdge,
        toT = roll.rollEdge + w.cast,
        startColor = palette.cast,
        endColor = transparent,
        layer = CurlShadowLayer.UNDER_PAGE,
    )

    // 2. 折痕外侧（尚未翻起的平铺页）被压住的阴影：贴折痕最深，向外渐隐
    addBand(
        fromT = roll.flatEdge - w.crease,
        toT = roll.flatEdge,
        startColor = transparent,
        endColor = palette.crease,
        layer = CurlShadowLayer.SHEET,
    )

    // 3. 翻起部分贴折痕处的环境光遮蔽（不超过正面可见范围）
    addBand(
        fromT = roll.flatEdge,
        toT = minOf(roll.flatEdge + w.sheet, roll.frontMax),
        startColor = palette.crease,
        endColor = transparent,
        layer = CurlShadowLayer.SHEET,
    )

    // 4. 纸背的圆柱明暗：贴圆角外缘最深，到自由边渐亮（θ > 90° 才有纸背）
    if (roll.hasBack) {
        addBand(
            fromT = roll.backMin,
            toT = roll.backMax,
            startColor = transparent,
            endColor = palette.rollShade,
            layer = CurlShadowLayer.SHEET,
        )
    }

    // 5. 翻页书页的自由边：纸的边本身是暗的（不是高光）
    if (roll.frontMax > w.edge) {
        addBand(
            fromT = roll.frontMax - w.edge,
            toT = roll.frontMax,
            startColor = transparent,
            endColor = palette.edge,
            layer = CurlShadowLayer.SHEET,
        )
    }

    return out
}

/** 阴影总量（供出图测试断言"阴影随卷曲成形"）。 */
fun curlShadowArea(bands: List<CurlShadowBand>): Float =
    bands.sumOf { polygonArea(it.polygon).toDouble() }.toFloat()

/** 由阅读主题派生阴影色：夜间/纯黑主题下要收敛强度，否则糊成一团。 */
fun curlShadowPaletteFor(background: Color): CurlShadowPalette {
    val luma = 0.299f * background.red + 0.587f * background.green + 0.114f * background.blue
    val k = (0.55f + 0.45f * luma).coerceIn(0.55f, 1f)
    return CurlShadowPalette(
        crease = Color.Black.copy(alpha = 0.22f * k),
        cast = Color.Black.copy(alpha = 0.10f * k),
        edge = Color.Black.copy(alpha = 0.16f * k),
        rollShade = Color.Black.copy(alpha = 0.12f * k),
    )
}

package com.llzx373.foldreader.feature.reader

import android.graphics.Bitmap
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb

/**
 * 卷曲翻页的绘制层。
 *
 * 图层顺序（与几何定义严格对应，见 [CurlGeometry] 文件头）：
 * 1. 下层页铺满（叶片离开后露出的那一页）；
 * 2. 卷筒投在下层页上的阴影（在纸张之前——它落在卷筒伸距之外，不会被纸覆盖）；
 * 3. 纸张正面：`drawBitmapMesh` 用同一套变形网格，只裁出正面可见的那一段；
 * 4. 纸张背面：同一网格、换纹理（[back] 为空则填纸色）；
 * 5. 纸面自身的折痕阴影与环境光遮蔽。
 *
 * 坐标一律用**覆盖层局部坐标**（内容框左上角为原点），因此单页时
 * `frame.screenRect == (0,0,内容宽,内容高)`，不需要额外的偏移换算。
 *
 * 与旧的 AGSL 铰链模型相比，这里不再有"着色器运行时编译失败"这一失败模式：
 * `drawBitmapMesh` 是稳定的平台 API。
 */
@Composable
fun CurlOverlay(
    leaf: Bitmap,
    under: Bitmap,
    roll: CurlRoll,
    frame: CurlFrame,
    meshIntervalPx: Float,
    pageBackColor: Color,
    back: Bitmap? = null,
    palette: CurlShadowPalette = CurlShadowPalette(),
    modifier: Modifier = Modifier,
) {
    val (meshW, meshH) = remember(frame, meshIntervalPx) {
        curlMeshSize(frame, meshIntervalPx)
    }
    val vertices = remember(roll, frame, meshW, meshH) {
        curlMeshVertices(roll, frame, meshW, meshH)
    }
    val bands = remember(roll, frame, palette) {
        curlShadowBands(roll, frame, palette)
    }
    val foldEdge = remember(roll, frame, pageBackColor) {
        curlFoldEdgeBand(roll, frame, pageBackColor)
    }
    val backArgb = pageBackColor.toArgb()

    Canvas(modifier) {
        drawIntoCanvas { canvas ->
            val nc = canvas.nativeCanvas
            val paint = Paint().apply {
                isAntiAlias = true
                isFilterBitmap = true
            }
            val rect = frame.screenRect
            val dst = RectF(rect.left, rect.top, rect.right, rect.bottom)

            nc.drawBitmap(under, null, dst, paint)

            drawBandLayer(nc, paint, bands, CurlShadowLayer.UNDER_PAGE)

            if (roll.degenerate) {
                // 还没卷起来：原样显示当前页
                nc.drawBitmap(leaf, null, dst, paint)
            } else {
                // 纸张正面 + 尚未翻起的平铺页：下界必须是"叶片之外"，因为平铺页在
                // flatEdge 之外（t < flatEdge）；漏掉它下层页会从那一片透出来。
                // 平铺部分的网格顶点是恒等的，所以同一次 drawBitmapMesh 就能一起画。
                nc.save()
                clipBand(nc, roll, frame, CURL_OPEN_LOWER_BOUND, roll.frontMax)
                nc.drawBitmapMesh(leaf, meshW, meshH, vertices, 0, null, 0, paint)
                nc.restore()

                // 纸张背面：θ > 90° 才有（纸翻过去压在折痕左侧）
                if (roll.hasBack) {
                    nc.save()
                    clipBand(nc, roll, frame, roll.backMin, roll.backMax)
                    val backBitmap = back
                    if (backBitmap != null) {
                        nc.drawBitmapMesh(backBitmap, meshW, meshH, vertices, 0, null, 0, paint)
                    } else {
                        paint.shader = null
                        paint.color = backArgb
                        nc.drawPath(bandPath(roll, frame, roll.backMin, roll.backMax), paint)
                    }
                    nc.restore()
                }
            }

            drawBandLayer(nc, paint, bands, CurlShadowLayer.SHEET)

            // 折痕处的纸边白带：画在最上层，否则会被折痕阴影压灰
            if (foldEdge != null) {
                drawBandLayer(nc, paint, listOf(foldEdge), CurlShadowLayer.SHEET)
            }
        }
    }
}

/** 把一段"距折痕的带"作为裁剪区（空带则裁掉全部，等于不画）。 */
private fun clipBand(
    nc: android.graphics.Canvas,
    roll: CurlRoll,
    frame: CurlFrame,
    fromT: Float,
    toT: Float,
) {
    val poly = curlBandPolygon(roll, frame, fromT, toT)
    if (poly.size >= 3) {
        nc.clipPath(androidPath(poly))
    } else {
        nc.clipRect(0f, 0f, 0f, 0f)
    }
}

private fun bandPath(roll: CurlRoll, frame: CurlFrame, fromT: Float, toT: Float): Path =
    androidPath(curlBandPolygon(roll, frame, fromT, toT))

private fun androidPath(poly: List<Offset>): Path = Path().apply {
    if (poly.isEmpty()) return@apply
    moveTo(poly[0].x, poly[0].y)
    for (i in 1 until poly.size) lineTo(poly[i].x, poly[i].y)
    close()
}

private fun drawBandLayer(
    nc: android.graphics.Canvas,
    paint: Paint,
    bands: List<CurlShadowBand>,
    layer: CurlShadowLayer,
) {
    for (band in bands) {
        if (band.layer != layer || band.polygon.size < 3) continue
        paint.shader = LinearGradient(
            band.from.x, band.from.y, band.to.x, band.to.y,
            band.startColor.toArgb(),
            band.endColor.toArgb(),
            Shader.TileMode.CLAMP,
        )
        nc.drawPath(androidPath(band.polygon), paint)
    }
    paint.shader = null
}

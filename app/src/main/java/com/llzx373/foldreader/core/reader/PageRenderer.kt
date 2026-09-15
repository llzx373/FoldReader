package com.llzx373.foldreader.core.reader

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.ui.graphics.toArgb
import com.llzx373.foldreader.feature.reader.PageSpread
import com.llzx373.foldreader.feature.reader.ReaderColors
import com.llzx373.foldreader.feature.reader.TextRangeSpan

/** 对页几何：双页含左右页 + 铰链空隙 + 居中 inset；单页即整页。坐标相对内容区左上角。 */
data class SpreadGeom(
    val dual: Boolean,
    val splitLeftPx: Float,
    val splitRightPx: Float,
    val leftInsetPx: Float,
    val rightInsetPx: Float,
    val innerPadPx: Float,
    val pageWidthPx: Float,
    /** 双页右页顶端额外下移量（摄像头开孔规避），与分页器奇数序页减容同步；0 为关闭。 */
    val rightTopPadPx: Float = 0f,
)

/**
 * 页面绘制主体（PageView 与离屏位图共用）：行布局 + 标注/选区高亮 + 逐行文字。
 * 返回行几何供命中测试复用。
 */
fun drawPageInto(
    canvas: android.graphics.Canvas,
    page: Page,
    config: LayoutConfig,
    textColorArgb: Int,
    density: Float,
    scaledDensity: Float,
    widthPx: Float,
    innerPaddingPx: Float = 0f,
    innerOnRight: Boolean = true,
    extraTopPadPx: Float = 0f,
    highlights: List<TextRangeSpan> = emptyList(),
    selection: TextRangeSpan? = null,
): List<LineBox> {
    val fontSizePx = config.fontSizeSp * scaledDensity
    val lineHeightPx = fontSizePx * config.lineSpacingMultiplier
    val paragraphSpacingPx = fontSizePx * config.paragraphSpacingEm
    val indentPx = if (config.autoIndentEnabled) config.firstLineIndentChars * fontSizePx else 0f
    val topPx = config.marginTopDp * density + extraTopPadPx
    val leftPad = page.paddingLeft + if (innerOnRight) 0f else innerPaddingPx
    val rightPad = page.paddingRight + if (innerOnRight) innerPaddingPx else 0f
    val textWidthPx = widthPx - leftPad - rightPad

    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = fontSizePx
        letterSpacing = config.letterSpacingEm
        color = textColorArgb
        config.typeface?.let { typeface = it }
    }
    val fm = paint.fontMetrics

    val boxes = buildLineBoxes(
        page = page,
        lineHeightPx = lineHeightPx,
        paragraphSpacingPx = paragraphSpacingPx,
        indentPx = indentPx,
        topPadPx = topPx,
        leftPadPx = leftPad,
        textWidthPx = textWidthPx,
        justify = config.alignment == PageTextAlignment.JUSTIFY,
        measure = { paint.measureText(it) },
    )

    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    val cornerPx = 3f * density
    // 标注底色（半透明，文字下方）或下划线（文字基线下彩色线）
    highlights.forEach { span ->
        segmentsForRange(boxes, span.start, span.endExclusive).forEach { seg ->
            if (span.underline) {
                fillPaint.color = span.color.copy(alpha = 0.9f).toArgb()
                fillPaint.strokeWidth = 1.6f * density
                canvas.drawLine(
                    seg.xStart, seg.yTop + seg.lineHeightPx * 0.92f,
                    seg.xEnd, seg.yTop + seg.lineHeightPx * 0.92f,
                    fillPaint,
                )
            } else {
                fillPaint.color = span.color.copy(alpha = 0.30f).toArgb()
                canvas.drawRoundRect(
                    RectF(
                        seg.xStart, seg.yTop + lineHeightPx * 0.08f,
                        seg.xEnd, seg.yTop + lineHeightPx * 0.92f,
                    ),
                    cornerPx, cornerPx, fillPaint,
                )
            }
        }
    }
    // 选区高亮
    if (selection != null) {
        fillPaint.color = selection.color.toArgb()
        segmentsForRange(boxes, selection.start, selection.endExclusive).forEach { seg ->
            canvas.drawRect(seg.xStart, seg.yTop, seg.xEnd, seg.yTop + seg.lineHeightPx, fillPaint)
        }
    }

    boxes.forEach { box ->
        val line = box.line
        if (line.text.isEmpty()) return@forEach
        val baseline = box.yTop + (lineHeightPx - fm.descent - fm.ascent) / 2f
        if (box.gapPx > 0f) {
            for (i in line.text.indices) {
                canvas.drawText(line.text[i].toString(), box.boundaryX(i), baseline, paint)
            }
        } else {
            canvas.drawText(line.text, box.x0, baseline, paint)
        }
    }
    return boxes
}

/**
 * 把整个对页（双页含铰链空隙与居中 inset；单页即整页）渲染成位图，供翻页动画
 * 按一张完整的纸翻折。页眉页脚不烘进位图（固定悬浮层，由 Compose 叠加层绘制）。
 * 位图格式 RGB_565。
 */
fun renderSpreadToBitmap(
    spread: PageSpread,
    config: LayoutConfig,
    colors: ReaderColors,
    geom: SpreadGeom,
    leftHighlights: List<TextRangeSpan>,
    rightHighlights: List<TextRangeSpan>,
    density: Float,
    scaledDensity: Float,
    widthPx: Int,
    heightPx: Int,
): Bitmap {
    val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.RGB_565)
    val canvas = android.graphics.Canvas(bitmap)
    canvas.drawColor(colors.background.toArgb())

    if (!geom.dual) {
        drawPageInto(
            canvas = canvas,
            page = spread.left,
            config = config,
            textColorArgb = colors.text.toArgb(),
            density = density,
            scaledDensity = scaledDensity,
            widthPx = widthPx.toFloat(),
            highlights = leftHighlights,
        )
    } else {
        val state = canvas.save()
        canvas.translate(geom.leftInsetPx, 0f)
        drawPageInto(
            canvas = canvas,
            page = spread.left,
            config = config,
            textColorArgb = colors.text.toArgb(),
            density = density,
            scaledDensity = scaledDensity,
            widthPx = geom.pageWidthPx,
            innerPaddingPx = geom.innerPadPx,
            innerOnRight = true,
            highlights = leftHighlights,
        )
        canvas.restoreToCount(state)
        spread.right?.let { right ->
            val rightState = canvas.save()
            canvas.translate(geom.splitRightPx + geom.rightInsetPx, 0f)
            drawPageInto(
                canvas = canvas,
                page = right,
                config = config,
                textColorArgb = colors.text.toArgb(),
                density = density,
                scaledDensity = scaledDensity,
                widthPx = geom.pageWidthPx,
                innerPaddingPx = geom.innerPadPx,
                innerOnRight = false,
                extraTopPadPx = geom.rightTopPadPx,
                highlights = rightHighlights,
            )
            canvas.restoreToCount(rightState)
        }
    }

    return bitmap
}

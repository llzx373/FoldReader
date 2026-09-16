package com.llzx373.foldreader.core.reader

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.ui.graphics.toArgb
import com.llzx373.foldreader.core.format.TextSpanType
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
    /** 链接/注释引用着色（LINK/NOTEREF span）；默认与正文同色。 */
    accentColorArgb: Int = textColorArgb,
    /** 图片行位图查询（仅查缓存，同步路径不做 IO/解码）；null 或未命中画占位灰框。 */
    imageProvider: ((imagePath: String) -> Bitmap?)? = null,
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
        if (line.imagePath != null) {
            drawImageLine(canvas, box, line, leftPad, textWidthPx, density, imageProvider)
            return@forEach
        }
        if (line.text.isEmpty()) return@forEach
        val baseline = box.yTop + (lineHeightPx - fm.descent - fm.ascent) / 2f
        if (line.spans.isEmpty()) {
            // 无样式快路径（与 span 引入前逐像素一致）
            if (box.gapPx > 0f) {
                for (i in line.text.indices) {
                    canvas.drawText(line.text[i].toString(), box.boundaryX(i), baseline, paint)
                }
            } else {
                canvas.drawText(line.text, box.x0, baseline, paint)
            }
        } else {
            // 样式 span 仅渲染期生效：粗体/斜体用 Paint 效果，上下标平移基线（字号不变），
            // 链接用主题 accent 色 + 下划线；度量不受影响
            for (run in styleRuns(line)) {
                paint.isFakeBoldText = TextSpanType.BOLD in run.types
                paint.textSkewX = if (TextSpanType.ITALIC in run.types) -0.25f else 0f
                val isLink = TextSpanType.LINK in run.types || TextSpanType.NOTEREF in run.types
                paint.color = if (isLink) accentColorArgb else textColorArgb
                val shift = when {
                    TextSpanType.SUP in run.types -> -0.35f * fontSizePx
                    TextSpanType.SUB in run.types -> 0.35f * fontSizePx
                    else -> 0f
                }
                val x = box.boundaryX(run.start)
                canvas.drawText(line.text.substring(run.start, run.end), x, baseline + shift, paint)
                if (isLink) {
                    paint.strokeWidth = density
                    canvas.drawLine(
                        x, baseline + 2f * density,
                        box.boundaryX(run.end), baseline + 2f * density,
                        paint,
                    )
                }
            }
            paint.isFakeBoldText = false
            paint.textSkewX = 0f
            paint.color = textColorArgb
        }
    }
    return boxes
}

/** 图片行：行框内居中按等比缩放画位图；位图未就绪画占位灰框 + alt 文本。 */
private fun drawImageLine(
    canvas: android.graphics.Canvas,
    box: LineBox,
    line: PageLine,
    leftPadPx: Float,
    textWidthPx: Float,
    density: Float,
    imageProvider: ((String) -> Bitmap?)?,
) {
    val bitmap = imageProvider?.invoke(line.imagePath.orEmpty())
    val inset = 2f * density
    val boxRect = RectF(
        leftPadPx + inset,
        box.yTop + inset,
        leftPadPx + textWidthPx - inset,
        box.yTop + box.lineHeightPx - inset,
    )
    if (bitmap != null && !bitmap.isRecycled) {
        val scale = minOf(boxRect.width() / bitmap.width, boxRect.height() / bitmap.height)
        val w = bitmap.width * scale
        val h = bitmap.height * scale
        val dest = RectF(
            boxRect.centerX() - w / 2f,
            boxRect.centerY() - h / 2f,
            boxRect.centerX() + w / 2f,
            boxRect.centerY() + h / 2f,
        )
        canvas.drawBitmap(bitmap, null, dest, Paint(Paint.ANTI_ALIAS_FLAG))
    } else {
        val ph = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x22888888 }
        canvas.drawRoundRect(boxRect, 4f * density, 4f * density, ph)
        val alt = line.imageAlt
        if (!alt.isNullOrEmpty()) {
            val altPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF888888.toInt()
                textSize = box.lineHeightPx * 0.2f
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText(
                alt, boxRect.centerX(), boxRect.centerY() + altPaint.textSize / 3f, altPaint,
            )
        }
    }
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
    imageProvider: ((imagePath: String) -> Bitmap?)? = null,
): Bitmap {
    val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.RGB_565)
    val canvas = android.graphics.Canvas(bitmap)
    canvas.drawColor(colors.background.toArgb())
    val accentArgb = colors.accent.toArgb()

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
            accentColorArgb = accentArgb,
            imageProvider = imageProvider,
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
            accentColorArgb = accentArgb,
            imageProvider = imageProvider,
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
                accentColorArgb = accentArgb,
                imageProvider = imageProvider,
            )
            canvas.restoreToCount(rightState)
        }
    }

    return bitmap
}

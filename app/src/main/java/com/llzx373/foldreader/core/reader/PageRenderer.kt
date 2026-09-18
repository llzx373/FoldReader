package com.llzx373.foldreader.core.reader

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.ui.graphics.toArgb
import com.llzx373.foldreader.core.format.TextSpanType
import com.llzx373.foldreader.feature.reader.TextRangeSpan

/**
 * 绘制用 Paint 按线程复用：设属性远廉于每次绘制 new 一个 Paint。
 * 离屏位图渲染发生在 Default 线程，与主线程天然隔离，故用 ThreadLocal。
 * 复用的前提是每次绘制显式重设**全部**会变动的属性（含 typeface，否则会沿用上一次的字体）。
 */
private class PagePaints {
    val text = Paint(Paint.ANTI_ALIAS_FLAG)
    val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    val image = Paint(Paint.ANTI_ALIAS_FLAG)
    val placeholder = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x22888888 }
    val alt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF888888.toInt()
        textAlign = Paint.Align.CENTER
    }
}

private val threadPaints = ThreadLocal.withInitial { PagePaints() }

/** 行几何缓存：重绘时复用行宽，避免逐字符 measureText。 */
private val lineBoxCache = LineBoxCache()

/**
 * 页面绘制主体：行布局 + 标注/选区高亮 + 逐行文字。
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

    val paints = threadPaints.get()
    val paint = paints.text
    paint.textSize = fontSizePx
    paint.letterSpacing = config.letterSpacingEm
    paint.color = textColorArgb
    // 必须显式设 typeface：复用的 Paint 会沿用上一次绘制留下的字体
    paint.typeface = config.typeface ?: Typeface.DEFAULT
    paint.isFakeBoldText = false
    paint.textSkewX = 0f
    val fm = paint.fontMetrics

    val boxes = lineBoxCache.getOrBuild(
        page = page,
        widthPx = widthPx,
        innerPaddingPx = innerPaddingPx,
        innerOnRight = innerOnRight,
        extraTopPadPx = extraTopPadPx,
        density = density,
        scaledDensity = scaledDensity,
        config = config,
    ) {
        buildLineBoxes(
            page = page,
            lineHeightPx = lineHeightPx,
            paragraphSpacingPx = paragraphSpacingPx,
            indentPx = indentPx,
            topPadPx = topPx,
            leftPadPx = leftPad,
            textWidthPx = textWidthPx,
            justify = config.alignment == PageTextAlignment.JUSTIFY,
            measure = { paint.measureText(it) },
            fillCharWidths = { text, out -> paint.getTextWidths(text, 0, text.length, out) },
            collapseWhitespace = config.normalizeWhitespaceEnabled,
        )
    }

    val fillPaint = paints.fill
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
            when {
                box.gapPx > 0f -> {
                    for (i in line.text.indices) {
                        if (box.collapsedMask?.get(i) == false) continue
                        canvas.drawText(line.text[i].toString(), box.boundaryX(i), baseline, paint)
                    }
                }
                // 折叠行不能用整串绘制：字体按真实字宽推进，折叠会失效
                box.collapsedMask != null ->
                    drawVisibleRange(canvas, box, 0, line.text.length, baseline, paint)

                else -> canvas.drawText(line.text, box.x0, baseline, paint)
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
                drawVisibleRange(canvas, box, run.start, run.end, baseline + shift, paint)
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

/**
 * 绘制 [from, to) 区间：把连续的未折叠字符切成片段，每段用 [LineBox.boundaryX] 定位。
 * 折叠字符既不占宽也不绘制（它们仍留在行文本里，偏移不变）。
 */
private fun drawVisibleRange(
    canvas: android.graphics.Canvas,
    box: LineBox,
    from: Int,
    to: Int,
    baseline: Float,
    paint: Paint,
) {
    val mask = box.collapsedMask
    if (mask == null) {
        canvas.drawText(box.line.text.substring(from, to), box.boundaryX(from), baseline, paint)
        return
    }
    var i = from
    while (i < to) {
        if (!mask[i]) {
            i++
            continue
        }
        var j = i + 1
        while (j < to && mask[j]) j++
        canvas.drawText(box.line.text.substring(i, j), box.boundaryX(i), baseline, paint)
        i = j
    }
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
    val paints = threadPaints.get()
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
        canvas.drawBitmap(bitmap, null, dest, paints.image)
    } else {
        canvas.drawRoundRect(boxRect, 4f * density, 4f * density, paints.placeholder)
        val alt = line.imageAlt
        if (!alt.isNullOrEmpty()) {
            val altPaint = paints.alt
            altPaint.textSize = box.lineHeightPx * 0.2f
            canvas.drawText(
                alt, boxRect.centerX(), boxRect.centerY() + altPaint.textSize / 3f, altPaint,
            )
        }
    }
}


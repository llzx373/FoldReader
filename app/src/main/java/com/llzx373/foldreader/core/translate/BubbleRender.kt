package com.llzx373.foldreader.core.translate

import com.llzx373.foldreader.core.ocr.OcrRect
import kotlin.math.ceil

/**
 * 漫画翻译覆盖层（视角①）的纯逻辑部分：气泡底色、文字色、字号自适应与折行。
 *
 * 不碰 Android / Compose：像素采样由调用方注入（[samplePoints] 给点位、
 * 调用方在页图片上取色后交给 [medianColor]），字号与折行按字符宽度估算——
 * 覆盖层绘制时用同一套折行结果排版，保证「算出来的」就是「画出来的」。
 *
 * 宽度估算口径：全角字符（CJK、日文假名、全角标点）≈ 1em，其余 ≈ 0.55em。
 * 这是估算不是排版引擎：宁可偏小（字小一点塞得下），不要偏大（译文溢出气泡）。
 */
object BubbleRender {

    /** 折行时的内边距比例：气泡四边各留一点，译文不顶边。 */
    const val INNER_PADDING = 0.08f

    /** 行距倍数。 */
    const val LINE_SPACING = 1.15f

    /** 字号下限（px，页图片坐标系）：再小就直接放弃收缩，用下限画出。 */
    const val MIN_FONT_PX = 8f

    /**
     * 气泡底色采样点：气泡矩形外扩 [margin]（页宽比例）后外周上的均匀点位
     * （每边 [pointsPerEdge] 个，不含角点重复）。取气泡**外侧**的像素——气泡内是文字，
     * 外周一圈才是气泡底色（白）或画面底色（自由文字）。点位裁剪到 0..1。
     */
    fun samplePoints(rect: OcrRect, margin: Float = 0.01f, pointsPerEdge: Int = 6): List<Pair<Float, Float>> {
        val left = (rect.left - margin).coerceIn(0f, 1f)
        val right = (rect.right + margin).coerceIn(0f, 1f)
        val top = (rect.top - margin).coerceIn(0f, 1f)
        val bottom = (rect.bottom + margin).coerceIn(0f, 1f)
        val points = ArrayList<Pair<Float, Float>>(pointsPerEdge * 4)
        for (i in 0 until pointsPerEdge) {
            val fx = if (pointsPerEdge == 1) 0.5f else i / (pointsPerEdge - 1f)
            val fy = if (pointsPerEdge == 1) 0.5f else (i + 0.5f) / pointsPerEdge
            points += (left + (right - left) * fx) to top
            points += (left + (right - left) * fx) to bottom
            points += left to (top + (bottom - top) * fy)
            points += right to (top + (bottom - top) * fy)
        }
        return points
    }

    /**
     * 一组 ARGB 颜色的中位色（逐通道取中位数）。逐通道而非整体排序：
     * 气泡边缘常会采到一两个深色文字像素，逐通道中位数能把它们挤掉。
     * 空列表返回 null（调用方回落默认底色）。
     */
    fun medianColor(colors: List<Int>): Int? {
        if (colors.isEmpty()) return null
        fun channel(selector: (Int) -> Int): Int =
            colors.map(selector).sorted().let { it[it.size / 2] }
        val a = channel { (it ushr 24) and 0xFF }
        val r = channel { (it ushr 16) and 0xFF }
        val g = channel { (it ushr 8) and 0xFF }
        val b = channel { it and 0xFF }
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** 覆盖文字色：亮底压黑字、暗底压白字。阈值取 140（略偏黑字，气泡大多是白底）。 */
    fun textColorFor(background: Int): Int {
        val r = (background ushr 16) and 0xFF
        val g = (background ushr 8) and 0xFF
        val b = background and 0xFF
        val luminance = 0.299 * r + 0.587 * g + 0.114 * b
        return if (luminance > 140.0) 0xFF1A1A1A.toInt() else 0xFFF5F5F5.toInt()
    }

    /** 单个字符的宽度估算（单位 em）。 */
    fun charEm(c: Char): Float = when {
        // CJK 统一表意、假名、全角形式、常用全角标点
        c in '　'..'〿' || c in '぀'..'ヿ' || c in '一'..'鿿' ||
            c in '＀'..'￯' || c in '、'..'。' || c in '＄'..'￥' -> 1.0f
        c == '…' || c == '—' || c == '「' || c == '」' || c == '『' || c == '』' -> 1.0f
        c == ' ' -> 0.35f
        else -> 0.55f
    }

    /**
     * 贪心折行：按 [fontSize] 与可用宽度 [maxWidth] 把 [text] 折成若干行。
     * 显式换行符强制断行。单字符超宽也放行（由字号收缩兜底）。
     */
    fun wrapLines(text: String, fontSize: Float, maxWidth: Float): List<String> {
        val lines = ArrayList<String>()
        for (segment in text.split('\n')) {
            val current = StringBuilder()
            var width = 0f
            for (c in segment) {
                val w = charEm(c) * fontSize
                if (current.isNotEmpty() && width + w > maxWidth) {
                    lines += current.toString()
                    current.clear()
                    width = 0f
                }
                current.append(c)
                width += w
            }
            if (current.isNotEmpty()) lines += current.toString()
        }
        if (lines.isEmpty()) lines += ""
        return lines
    }

    /**
     * 字号自适应：在 [minFont]..[maxFont] 之间找最大的字号，使折行后的
     * 行数 × 行高 ≤ 气泡可用高度。找不到就用 [minFont]（超一点也比看不清强）。
     */
    fun fitFontSize(
        text: String,
        rectWidth: Float,
        rectHeight: Float,
        maxFont: Float,
        minFont: Float = MIN_FONT_PX,
    ): Float {
        val usableW = rectWidth * (1f - INNER_PADDING * 2)
        val usableH = rectHeight * (1f - INNER_PADDING * 2)
        if (usableW <= 0f || usableH <= 0f) return minFont
        val start = minOf(maxFont, usableH / LINE_SPACING)
        var size = start
        while (size > minFont) {
            val lines = wrapLines(text, size, usableW)
            if (lines.size * size * LINE_SPACING <= usableH) return size
            size -= 1f
        }
        return minFont
    }

    /**
     * 一次算好一个气泡的排版：字号 + 折行结果。返回的行数与字号供绘制端直接使用。
     */
    fun layout(
        text: String,
        rectWidth: Float,
        rectHeight: Float,
        maxFont: Float,
        minFont: Float = MIN_FONT_PX,
    ): BubbleLayout {
        val font = fitFontSize(text, rectWidth, rectHeight, maxFont, minFont)
        val usableW = rectWidth * (1f - INNER_PADDING * 2)
        return BubbleLayout(
            lines = wrapLines(text, font, usableW),
            fontSize = font,
            lineHeight = font * LINE_SPACING,
        )
    }

    data class BubbleLayout(
        val lines: List<String>,
        val fontSize: Float,
        val lineHeight: Float,
    ) {
        val textHeight: Float get() = ceil(lines.size * lineHeight)
    }
}

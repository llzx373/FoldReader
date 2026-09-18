package com.llzx373.foldreader.core.reader

import androidx.compose.runtime.Immutable

/**
 * 页内行几何：与 PageView 绘制共用同一套坐标计算，
 * 保证划线渲染与长按/手柄命中测试完全一致（两端对齐附加字距也包含在内）。
 *
 * [charWidths] 虽为数组，但只在构造时填一次、之后只读，故可声明为不可变。
 * [collapsedMask] 为空白归一化的折叠掩码（null = 未开启）；被折叠字符不占宽、也不累计字距，
 * 但**仍留在 [line] 里**，故字符偏移与选区/划线/书签锚点完全不受影响。
 */
@Immutable
class LineBox(
    val line: PageLine,
    val x0: Float,
    val yTop: Float,
    val lineHeightPx: Float,
    val charWidths: FloatArray,
    val gapPx: Float,
    val collapsedMask: BooleanArray? = null,
) {
    val textLength: Int get() = line.text.length

    /** 第 [index] 个字符左边缘的 x（index 可等于 textLength，表示行尾光标位）。 */
    fun boundaryX(index: Int): Float {
        var x = x0
        val n = index.coerceIn(0, charWidths.size)
        val mask = collapsedMask
        for (i in 0 until n) {
            if (mask != null && !mask[i]) continue
            x += charWidths[i] + gapPx
        }
        return x
    }

    val yBottom: Float get() = yTop + lineHeightPx
}

@Immutable
data class RangeSegment(
    val lineIndex: Int,
    val xStart: Float,
    val xEnd: Float,
    val yTop: Float,
    val lineHeightPx: Float,
)

/**
 * 逐行布局，复刻 PageView 的排版：段首缩进、段间距、两端对齐附加字距。
 * [measure] 为字符串宽度测量（px）。
 * [fillCharWidths] 为批量逐字宽度填充（一次调用填满整行，如 `Paint.getTextWidths`）；
 * 为 null 时退回逐字 [measure]（单测与无 Paint 场景）。
 * [collapseWhitespace] 开启空白归一化：段首空白折叠为零宽并改由 [indentPx] 对齐，
 * 行内/行尾多余空白与不可见字符同样折叠。关闭时逐像素维持原行为。
 */
fun buildLineBoxes(
    page: Page,
    lineHeightPx: Float,
    paragraphSpacingPx: Float,
    indentPx: Float,
    topPadPx: Float,
    leftPadPx: Float,
    textWidthPx: Float,
    justify: Boolean,
    measure: (String) -> Float,
    fillCharWidths: ((CharSequence, FloatArray) -> Unit)? = null,
    collapseWhitespace: Boolean = false,
): List<LineBox> {
    val boxes = ArrayList<LineBox>(page.lines.size)
    var yTop = topPadPx
    page.lines.forEachIndexed { index, line ->
        if (line.isParagraphStart && index > 0) yTop += paragraphSpacingPx
        // 图片行用缩放后实际行高；文本行恒为 lineHeightPx（纯文本路径逐像素不变）
        val effectiveLineHeightPx = line.heightPx ?: lineHeightPx
        val mask = if (collapseWhitespace) normalizedWidthMask(line.text) else null
        val x0 = leftPadPx + if (line.isParagraphStart &&
            (!hasLeadingIndent(line.text) || collapseWhitespace)
        ) {
            indentPx
        } else {
            0f
        }
        val widths = FloatArray(line.text.length)
        if (fillCharWidths != null) {
            fillCharWidths(line.text, widths)
        } else {
            for (i in line.text.indices) widths[i] = measure(line.text[i].toString())
        }
        val visibleCount = if (mask == null) line.text.length else mask.count { it }
        if (mask != null) {
            for (i in line.text.indices) {
                if (!mask[i]) widths[i] = 0f
            }
        }
        val gap = if (justify && !line.isParagraphEnd && visibleCount > 1) {
            // 折叠字符不参与占宽，自然宽与字距除数都只按可见字符算
            val natural = if (mask == null) {
                measure(line.text)
            } else {
                var sum = 0f
                for (i in line.text.indices) sum += widths[i]
                sum
            }
            // 上限防御：绘制宽度与分页宽度错配（版式切换窗口期）时字距不会爆炸
            ((textWidthPx - (x0 - leftPadPx) - natural) / (visibleCount - 1))
                .coerceIn(0f, lineHeightPx * 0.5f)
        } else {
            0f
        }
        boxes += LineBox(line, x0, yTop, effectiveLineHeightPx, widths, gap, mask)
        yTop += effectiveLineHeightPx
    }
    return boxes
}

/**
 * 命中测试：返回光标位（两字符之间），取值 [line.charStart, line.charStart + textLength]。
 * y 落在行间空白时归入最近一行；x 超出行首尾时钳到行首/行尾。
 */
fun caretAt(boxes: List<LineBox>, x: Float, y: Float): Long? {
    if (boxes.isEmpty()) return null
    val box = boxes.firstOrNull { y >= it.yTop && y < it.yBottom }
        ?: if (y < boxes.first().yTop) boxes.first() else boxes.last()
    val n = box.textLength
    var caret = n
    for (i in 0 until n) {
        val mid = (box.boundaryX(i) + box.boundaryX(i + 1)) / 2f
        if (x < mid) {
            caret = i
            break
        }
    }
    return box.line.charStart + caret
}

/**
 * 标注区间 [rangeStart, rangeEnd) 与各行求交，返回逐行片段（供底色/下划线绘制）。
 * 行尾换行符（包含在 charEnd 内但不在 text 内）不产生片段。
 */
fun segmentsForRange(
    boxes: List<LineBox>,
    rangeStart: Long,
    rangeEnd: Long,
): List<RangeSegment> {
    if (rangeEnd <= rangeStart) return emptyList()
    val segments = ArrayList<RangeSegment>()
    boxes.forEachIndexed { index, box ->
        val lineStart = box.line.charStart
        val lineTextEnd = lineStart + box.textLength
        val s = maxOf(rangeStart, lineStart)
        val e = minOf(rangeEnd, lineTextEnd)
        if (s < e) {
            segments += RangeSegment(
                lineIndex = index,
                xStart = box.boundaryX((s - lineStart).toInt()),
                xEnd = box.boundaryX((e - lineStart).toInt()),
                yTop = box.yTop,
                lineHeightPx = box.lineHeightPx,
            )
        }
    }
    return segments
}

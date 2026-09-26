package com.llzx373.foldreader.core.ocr

/**
 * 归一化矩形（0..1，页图片左上为原点），与 v2.2 页内锚点（feature 侧 PageRect）同一坐标系。
 *
 * core 层不依赖 feature 包，所以这里单独定义；UI 边界处与 PageRect 一一互转。
 * 存归一化的原因与 PageRect 相同：渲染尺寸随适配/缩放变化，锚点必须分辨率无关。
 */
data class OcrRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val area: Float get() = width * height

    fun containsPoint(x: Float, y: Float): Boolean =
        x in left..right && y in top..bottom

    /** 两矩形的交集面积占自身面积的比例（0 表示不相交）。 */
    fun overlapRatio(other: OcrRect): Float {
        val w = minOf(right, other.right) - maxOf(left, other.left)
        val h = minOf(bottom, other.bottom) - maxOf(top, other.top)
        if (w <= 0f || h <= 0f || area <= 0f) return 0f
        return (w * h) / area
    }

    fun union(other: OcrRect): OcrRect = OcrRect(
        left = minOf(left, other.left),
        top = minOf(top, other.top),
        right = maxOf(right, other.right),
        bottom = maxOf(bottom, other.bottom),
    )

    companion object {
        fun of(x: Float, y: Float, w: Float, h: Float) = OcrRect(
            x.coerceIn(0f, 1f),
            y.coerceIn(0f, 1f),
            (x + w).coerceIn(0f, 1f),
            (y + h).coerceIn(0f, 1f),
        )
    }
}

/** OCR 识别出的一行文字。 */
data class OcrTextLine(
    val text: String,
    val box: OcrRect,
    val confidence: Float,
)

/** 一页的 OCR 结果（扫描 PDF 文本层 / 漫画气泡内文字共用的中间产物）。 */
data class OcrPage(
    val lines: List<OcrTextLine>,
)

/**
 * 气泡检测结果（RT-DETR 检测 + 行归并后）：一个气泡区域 + 归属其中的文字行。
 *
 * [index] 是页内气泡序号（阅读序排序后），作为气泡的稳定标识贯穿翻译与覆盖层。
 */
data class OcrBubble(
    val index: Int,
    val rect: OcrRect,
    val lines: List<OcrTextLine>,
    val confidence: Float,
) {
    /** 气泡内文字按阅读序拼接的原文。 */
    val text: String get() = lines.joinToString("") { it.text }
}

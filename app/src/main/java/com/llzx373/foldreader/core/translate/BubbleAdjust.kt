package com.llzx373.foldreader.core.translate

import com.llzx373.foldreader.core.ocr.OcrBubble
import com.llzx373.foldreader.core.ocr.OcrRect
import kotlinx.serialization.Serializable

/**
 * 气泡位置手动微调（M23）：纯逻辑——命中判定、移动/缩放的几何约束、
 * 微调结果的存储模型与套用。
 *
 * 微调不落进 `.ocr.json`（那是识别缓存，重跑识别即重生）：单独存
 * `<page>.adjust.json`，按气泡序号（`OcrBubble.index`）记覆盖矩形；
 * 覆盖层/对照面板取气泡时经 [apply] 套用。识别缓存重建后序号可能漂移，
 * 所以微调与缓存同生命周期——调用方在重写 `.ocr.json` 时一并作废微调。
 */
@Serializable
data class BubbleAdjustments(
    val version: Int = ADJUST_VERSION,
    /** 气泡序号 → 覆盖矩形（归一化页内坐标）。 */
    val rects: Map<Int, OcrRect> = emptyMap(),
) {
    companion object {
        const val ADJUST_VERSION = 1
    }
}

object BubbleAdjust {

    /** 气泡矩形的最小边长（归一化）：再小拖柄就点不中了。 */
    const val MIN_SIZE = 0.02f

    /**
     * 命中判定：点 (x, y)（归一化）落在哪个气泡里。
     * 命中多个（气泡重叠）时取面积最小的——小气泡不该被大气泡挡住。
     */
    fun bubbleAt(bubbles: List<OcrBubble>, x: Float, y: Float): OcrBubble? =
        bubbles.filter { it.rect.containsPoint(x, y) }.minByOrNull { it.rect.area }

    /** 移动：整框平移 (dx, dy)，夹取在页内（贴边即停，不出页）。 */
    fun move(rect: OcrRect, dx: Float, dy: Float): OcrRect {
        // 超界宽高（损坏的 .adjust.json）先钳回页内：否则 1f - w < 0，coerceIn 直接抛
        val w = rect.width.coerceAtMost(1f)
        val h = rect.height.coerceAtMost(1f)
        val left = (rect.left + dx).coerceIn(0f, 1f - w)
        val top = (rect.top + dy).coerceIn(0f, 1f - h)
        return OcrRect(left, top, left + w, top + h)
    }

    /** 缩放（拖右下角）：右下角移到 (newRight, newBottom)，夹最小边长与页界。 */
    fun resize(rect: OcrRect, newRight: Float, newBottom: Float): OcrRect {
        // 左上角本身超界时 left+MIN_SIZE 会越过 1f，下限先钳住，coerceIn 才不构成空区间
        val minRight = (rect.left + MIN_SIZE).coerceAtMost(1f)
        val minBottom = (rect.top + MIN_SIZE).coerceAtMost(1f)
        val right = newRight.coerceIn(minRight, 1f)
        val bottom = newBottom.coerceIn(minBottom, 1f)
        return OcrRect(rect.left.coerceIn(0f, 1f), rect.top.coerceIn(0f, 1f), right, bottom)
    }

    /** 套用微调：有序号覆盖的换矩形，其余原样。 */
    fun apply(bubbles: List<OcrBubble>, adjustments: BubbleAdjustments?): List<OcrBubble> {
        if (adjustments == null || adjustments.rects.isEmpty()) return bubbles
        return bubbles.map { bubble ->
            val override = adjustments.rects[bubble.index] ?: return@map bubble
            bubble.copy(rect = override)
        }
    }
}

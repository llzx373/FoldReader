package com.llzx373.foldreader.core.inpaint

import com.llzx373.foldreader.core.ocr.OcrRect
import kotlin.math.roundToInt

/**
 * 气泡抹除（inpainting，M31）的纯逻辑部分：抹除区域的几何计算。
 *
 * 不碰 Android / ONNX：位图与张量搬运在 core/ocr/android/InpaintEngine。
 * 这里算三件事——ROI（气泡并集外扩，给模型留上下文）、模型输入尺寸
 * （等比缩放 + 8 对齐，LaMa 系模型下采样 3 次要求边长是 8 的倍数）、
 * 掩码字节图（气泡矩形映射进 ROI 后按像素膨胀）。
 */
object InpaintMask {

    /** ROI 外扩（页宽/高比例）：给模型留上下文，也让掩码边缘的接缝远离气泡边。 */
    const val ROI_MARGIN = 0.04f

    /** 掩码膨胀（模型输入坐标系的像素）：气泡检出框常贴着文字边，外扩几像素才能盖全。 */
    const val MASK_DILATE_PX = 4

    /** 模型输入长边上限：整页直接进模型既慢又占内存，ROI 等比缩到这个尺寸内。 */
    const val MAX_INPUT_SIDE = 1024

    /** LaMa 系模型的边长对齐（3 次 2× 下采样）。 */
    const val ALIGN = 8

    /**
     * 抹除区域的 ROI：全部气泡矩形的并集外扩 [margin] 后钳到页内。
     * 没有气泡返回 null（调用方跳过抹除）。
     */
    fun roiOf(rects: List<OcrRect>, margin: Float = ROI_MARGIN): OcrRect? {
        if (rects.isEmpty()) return null
        var union = rects.first()
        for (i in 1 until rects.size) union = union.union(rects[i])
        return OcrRect(
            (union.left - margin).coerceIn(0f, 1f),
            (union.top - margin).coerceIn(0f, 1f),
            (union.right + margin).coerceIn(0f, 1f),
            (union.bottom + margin).coerceIn(0f, 1f),
        ).takeIf { it.width > 0f && it.height > 0f }
    }

    /**
     * 模型输入尺寸：ROI 像素尺寸等比缩到长边 ≤ [maxSide]，再向上对齐 [ALIGN] 的倍数。
     * 对齐最多让长边超出上限 [ALIGN]-1 像素，无关紧要。
     */
    fun inputSizeOf(roiW: Int, roiH: Int, maxSide: Int = MAX_INPUT_SIDE): Pair<Int, Int> {
        require(roiW > 0 && roiH > 0) { "ROI 像素尺寸必须为正：$roiW x $roiH" }
        val scale = minOf(1f, maxSide.toFloat() / maxOf(roiW, roiH))
        return alignUp(maxOf(1, (roiW * scale).roundToInt())) to
            alignUp(maxOf(1, (roiH * scale).roundToInt()))
    }

    /**
     * 生成模型输入坐标系下的掩码（[width]×[height]，255 = 待抹除）。
     * 气泡矩形是页归一化坐标，先映射进 [roi] 再按 [dilatePx] 四向外扩。
     * 与 ROI 不相交的气泡（裁边映射退化等）自然落空，不影响其余。
     */
    fun maskBytes(
        rects: List<OcrRect>,
        roi: OcrRect,
        width: Int,
        height: Int,
        dilatePx: Int = MASK_DILATE_PX,
    ): ByteArray {
        require(width > 0 && height > 0) { "掩码尺寸必须为正：$width x $height" }
        val mask = ByteArray(width * height)
        for (r in rects) {
            val l = ((r.left - roi.left) / roi.width * width).roundToInt() - dilatePx
            val t = ((r.top - roi.top) / roi.height * height).roundToInt() - dilatePx
            val rr = ((r.right - roi.left) / roi.width * width).roundToInt() + dilatePx
            val b = ((r.bottom - roi.top) / roi.height * height).roundToInt() + dilatePx
            val x0 = l.coerceIn(0, width)
            val y0 = t.coerceIn(0, height)
            val x1 = rr.coerceIn(0, width)
            val y1 = b.coerceIn(0, height)
            for (y in y0 until y1) {
                mask.fill(255.toByte(), y * width + x0, y * width + x1)
            }
        }
        return mask
    }

    /**
     * 裁边（M31 自动裁白边）启用时：气泡矩形是**原页**归一化坐标，抹除作用在裁后位图上，
     * 先按裁框 [cropBox]（"l,t,r,b" 归一化，与 MarginCrop 同一口径）映射到裁后坐标系。
     * 完全落在裁掉区域里的气泡返回 null（那部分页面已经看不见，不用抹）。
     */
    fun remapToCrop(rect: OcrRect, cropBox: FloatArray): OcrRect? {
        if (cropBox.size < 4) return null
        val cropW = cropBox[2] - cropBox[0]
        val cropH = cropBox[3] - cropBox[1]
        if (cropW <= 0f || cropH <= 0f) return null
        return OcrRect(
            ((rect.left - cropBox[0]) / cropW).coerceIn(0f, 1f),
            ((rect.top - cropBox[1]) / cropH).coerceIn(0f, 1f),
            ((rect.right - cropBox[0]) / cropW).coerceIn(0f, 1f),
            ((rect.bottom - cropBox[1]) / cropH).coerceIn(0f, 1f),
        ).takeIf { it.width > 0f && it.height > 0f }
    }

    private fun alignUp(v: Int): Int = ((v + ALIGN - 1) / ALIGN) * ALIGN
}

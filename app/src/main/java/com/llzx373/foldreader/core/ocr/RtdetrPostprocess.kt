package com.llzx373.foldreader.core.ocr

/**
 * RT-DETR-v2 气泡检测输出后处理（M21/M22，R9 偏差见 ModelCatalog 注释）。
 *
 * 纯 JVM：输入是 ONNX 会话层取出的原始张量，输出归一化坐标，合成张量即可单测。
 *
 * ogkalu detector-v4 的输出已是端到端结果（检测头自带去重，无需 NMS）：
 * - labels `[1, n]`（int64）：0=bubble，1=text_bubble，2=text_free；
 * - boxes  `[1, n, 4]`（cxcywh，已按 orig_target_sizes 还原到原图像素）；
 * - scores `[1, n]`。
 * 只需按得分阈值过滤 + 归一化。
 */
object RtdetrPostprocess {

    /** 类别：有框气泡 / 无框文本气泡 / 自由文本（旁白、拟声词）。 */
    const val CLASS_BUBBLE = 0
    const val CLASS_TEXT_BUBBLE = 1
    const val CLASS_TEXT_FREE = 2

    class BubbleDetection(
        val rect: OcrRect,
        val classId: Int,
        val score: Float,
    )

    /**
     * 过滤 + 归一化。[pageWidth]/[pageHeight] 为原图像素尺寸（即喂模型时声明的
     * orig_target_sizes），boxes 已在该坐标系。
     */
    fun decode(
        labels: IntArray,
        boxes: FloatArray,
        scores: FloatArray,
        count: Int,
        pageWidth: Int,
        pageHeight: Int,
        scoreThreshold: Float = 0.5f,
    ): List<BubbleDetection> {
        require(boxes.size == count * 4) { "boxes 尺寸与声明不符" }
        require(labels.size >= count && scores.size >= count) { "labels/scores 尺寸与声明不符" }
        val out = ArrayList<BubbleDetection>()
        for (i in 0 until count) {
            if (scores[i] < scoreThreshold) continue
            val cx = boxes[i * 4 + 0]
            val cy = boxes[i * 4 + 1]
            val w = boxes[i * 4 + 2]
            val h = boxes[i * 4 + 3]
            if (w <= 0f || h <= 0f) continue
            out += BubbleDetection(
                rect = OcrRect(
                    left = ((cx - w / 2f) / pageWidth).coerceIn(0f, 1f),
                    top = ((cy - h / 2f) / pageHeight).coerceIn(0f, 1f),
                    right = ((cx + w / 2f) / pageWidth).coerceIn(0f, 1f),
                    bottom = ((cy + h / 2f) / pageHeight).coerceIn(0f, 1f),
                ),
                classId = labels[i],
                score = scores[i],
            )
        }
        return out
    }
}

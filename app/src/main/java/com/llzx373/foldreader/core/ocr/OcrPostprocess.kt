package com.llzx373.foldreader.core.ocr

import kotlin.math.ceil
import kotlin.math.floor

/**
 * OCR 后处理（M21）：PaddleOCR det（DBNet）与 rec（CTC）的模型输出解码。
 *
 * 纯 JVM、不碰 onnx/android：输入是 ONNX 会话层取出的原始张量数组，输出是
 * 归一化坐标，便于用合成张量做单测。
 *
 * 与官方实现的偏差（有意为之，注释备查）：
 * - DBNet 官方后处理取多边形轮廓 + Vatti unclip；这里退化为「连通域外接矩形 +
 *   按比例外扩」。漫画气泡与扫描件正文行都以横排矩形为主，矩形近似的漏检率
 *   可接受，换来零图形库依赖与完整可测性。斜排/弯排气泡文字由 M22 的
 *   气泡检测模型兜底（气泡级框不依赖行级精度）。
 */
object OcrPostprocess {

    /**
     * DBNet 概率图 → 文本行框（像素坐标）。
     *
     * [probMap] 为模型输出的 H×W 概率图（行优先展平，值域 0..1）；
     * [mapWidth]/[mapHeight] 为概率图尺寸，[pageWidth]/[pageHeight] 为原图像素尺寸
     * （框坐标输出到原图坐标系，由调用方再归一化）。
     *
     * 流程：阈值二值化 → 连通域标记（4 邻域）→ 过滤过小域 → 外接矩形按比例外扩。
     */
    fun decodeDetection(
        probMap: FloatArray,
        mapWidth: Int,
        mapHeight: Int,
        pageWidth: Int,
        pageHeight: Int,
        threshold: Float = 0.3f,
        minAreaRatio: Float = 0.00005f,
        expandRatio: Float = 0.15f,
    ): List<FloatArray> {
        require(probMap.size == mapWidth * mapHeight) { "概率图尺寸与声明不符" }
        val bin = BooleanArray(probMap.size) { probMap[it] > threshold }
        val labels = IntArray(probMap.size) { -1 }
        var nextLabel = 0
        val boxes = ArrayList<FloatArray>()
        val queue = IntArray(probMap.size)

        for (start in bin.indices) {
            if (!bin[start] || labels[start] >= 0) continue
            // BFS 连通域
            var head = 0
            var tail = 0
            queue[tail++] = start
            labels[start] = nextLabel
            var minX = Int.MAX_VALUE
            var minY = Int.MAX_VALUE
            var maxX = Int.MIN_VALUE
            var maxY = Int.MIN_VALUE
            var count = 0
            while (head < tail) {
                val cur = queue[head++]
                val x = cur % mapWidth
                val y = cur / mapWidth
                if (x < minX) minX = x
                if (y < minY) minY = y
                if (x > maxX) maxX = x
                if (y > maxY) maxY = y
                count++
                // 4 邻域
                if (x > 0 && bin[cur - 1] && labels[cur - 1] < 0) {
                    labels[cur - 1] = nextLabel; queue[tail++] = cur - 1
                }
                if (x < mapWidth - 1 && bin[cur + 1] && labels[cur + 1] < 0) {
                    labels[cur + 1] = nextLabel; queue[tail++] = cur + 1
                }
                if (y > 0 && bin[cur - mapWidth] && labels[cur - mapWidth] < 0) {
                    labels[cur - mapWidth] = nextLabel; queue[tail++] = cur - mapWidth
                }
                if (y < mapHeight - 1 && bin[cur + mapWidth] && labels[cur + mapWidth] < 0) {
                    labels[cur + mapWidth] = nextLabel; queue[tail++] = cur + mapWidth
                }
            }
            nextLabel++
            // 过小的域当噪点丢弃（按整页面积比例）
            if (count < pageWidth * pageHeight * minAreaRatio) continue
            // 外接矩形按比例外扩（unclip 的矩形近似），并映射回原图坐标系
            val scaleX = pageWidth.toFloat() / mapWidth
            val scaleY = pageHeight.toFloat() / mapHeight
            val w = (maxX - minX + 1) * scaleX
            val h = (maxY - minY + 1) * scaleY
            val ex = w * expandRatio / 2f
            val ey = h * expandRatio / 2f
            boxes += floatArrayOf(
                (minX * scaleX - ex).coerceIn(0f, pageWidth.toFloat()),
                (minY * scaleY - ey).coerceIn(0f, pageHeight.toFloat()),
                ((maxX + 1) * scaleX + ex).coerceIn(0f, pageWidth.toFloat()),
                ((maxY + 1) * scaleY + ey).coerceIn(0f, pageHeight.toFloat()),
            )
        }
        return boxes
    }

    /**
     * rec 模型的 CTC 输出 → 文本 + 平均置信度。
     *
     * [scores] 为 T×C 的模型输出（行优先展平，T=时间步，C=字符集大小），
     * [charset] 下标即类别（0 号约定为 CTC blank；清单模型（RapidOCR mobile rec）
     * 输出已过 softmax，逐时间步最大值即该字符置信度）。
     * 贪心解码：逐时间步取 argmax → 去连续重复 → 去 blank。
     */
    fun decodeRecognition(
        scores: FloatArray,
        steps: Int,
        charset: List<String>,
    ): Pair<String, Float> {
        val classes = charset.size
        require(scores.size == steps * classes) { "rec 输出尺寸与声明不符" }
        val text = StringBuilder()
        var confSum = 0f
        var confCount = 0
        var prev = -1
        for (t in 0 until steps) {
            val base = t * classes
            var best = 0
            var bestV = scores[base]
            for (c in 1 until classes) {
                if (scores[base + c] > bestV) {
                    bestV = scores[base + c]
                    best = c
                }
            }
            if (best != 0 && best != prev) {
                text.append(charset[best])
                confSum += bestV.coerceIn(0f, 1f)
                confCount++
            }
            prev = best
        }
        val confidence = if (confCount == 0) 0f else confSum / confCount
        return text.toString() to confidence
    }

    /** 像素框 → 归一化 [OcrRect]（页左上原点，0..1）。 */
    fun normalizeBox(box: FloatArray, pageWidth: Int, pageHeight: Int): OcrRect = OcrRect(
        left = (box[0] / pageWidth).coerceIn(0f, 1f),
        top = (box[1] / pageHeight).coerceIn(0f, 1f),
        right = (box[2] / pageWidth).coerceIn(0f, 1f),
        bottom = (box[3] / pageHeight).coerceIn(0f, 1f),
    )

    /**
     * det 预处理：等比缩放到边长为 [multiple] 的倍数、长边不超过 [maxSide]。
     * 返回 (目标宽, 目标高)；会话层按此 resize 后喂模型。
     */
    fun detInputSize(pageWidth: Int, pageHeight: Int, maxSide: Int = 960, multiple: Int = 32): IntArray {
        val scale = minOf(maxSide.toFloat() / pageWidth, maxSide.toFloat() / pageHeight, 1f)
        val w = (ceil((pageWidth * scale) / multiple) * multiple).toInt().coerceAtLeast(multiple)
        val h = (ceil((pageHeight * scale) / multiple) * multiple).toInt().coerceAtLeast(multiple)
        return intArrayOf(w, h)
    }

    /** rec 预处理：行图等比缩放到高 [height]、宽取整到 [widthMultiple] 的倍数且至少 1 个字符宽。 */
    fun recInputSize(lineWidth: Int, lineHeight: Int, height: Int = 48, widthMultiple: Int = 8): IntArray {
        val ratio = lineWidth.toFloat() / lineHeight.coerceAtLeast(1)
        val w = (floor(ratio * height) .toInt()).coerceAtLeast(widthMultiple)
        val rounded = ((w + widthMultiple - 1) / widthMultiple) * widthMultiple
        return intArrayOf(rounded, height)
    }
}

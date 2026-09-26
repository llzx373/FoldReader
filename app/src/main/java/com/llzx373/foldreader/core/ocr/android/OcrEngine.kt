package com.llzx373.foldreader.core.ocr.android

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import com.llzx373.foldreader.core.ai.android.ModelManager
import com.llzx373.foldreader.core.ocr.BubbleGrouping
import com.llzx373.foldreader.core.ocr.ModelCatalog
import com.llzx373.foldreader.core.ocr.OcrBubble
import com.llzx373.foldreader.core.ocr.OcrModelSpec
import com.llzx373.foldreader.core.ocr.OcrPage
import com.llzx373.foldreader.core.ocr.OcrPostprocess
import com.llzx373.foldreader.core.ocr.OcrTextLine
import com.llzx373.foldreader.core.ocr.RtdetrPostprocess
import java.io.Closeable
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * ONNX 会话层（M21）：det → rec 文本层管线 与 气泡检测管线。
 *
 * 有意保持「薄」：所有可测逻辑（后处理、归并、选字、搜索）都在 core/ocr 纯 JVM 层，
 * 这里只做位图预处理、张量搬运与会话管理。Robolectric 不加载 native .so，
 * 本层不进单测，正确性靠纯逻辑层的覆盖率与真机验证兜底。
 *
 * 惰性铁律同 AI 网络组件：模型未导入不创建 OrtEnvironment/会话。
 * 会话全部经 [mutex] 串行（与 PDF 沙箱「一次一页」同源约束；ONNX 会话本身非线程安全）。
 */
class OcrEngine(
    private val context: Context,
    private val modelManager: ModelManager,
) : Closeable {

    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    private var detSession: OrtSession? = null
    private val recSessions = HashMap<String, OrtSession>()
    private var bubbleSession: OrtSession? = null
    private val charsets = HashMap<String, List<String>>()
    private val mutex = Mutex()

    /**
     * 页位图 → OCR 文本层（扫描 PDF / 漫画页级 OCR 共用）。
     * [recSpec] 决定识别语言（词典随模型走 assets）。
     */
    suspend fun recognizePage(bitmap: Bitmap, recSpec: OcrModelSpec): OcrPage =
        withContext(Dispatchers.Default) {
            mutex.withLock { recognizePageLocked(bitmap, recSpec) }
        }

    /**
     * 页位图 → 气泡（漫画翻译管线）：RT-DETR 气泡检测 + 页级 OCR + 行归并。
     * [rtl] 决定阅读序（日漫 true）。
     */
    suspend fun detectBubbles(bitmap: Bitmap, recSpec: OcrModelSpec, rtl: Boolean): List<OcrBubble> =
        withContext(Dispatchers.Default) {
            mutex.withLock {
                val detections = detectBubbleRectsLocked(bitmap)
                // 页级 OCR 后用检测结果归并：气泡类检出 → 气泡框；自由文本检出本身即文字行，
                // 无框文本气泡按气泡处理（行由 OCR 提供）。
                val ocrPage = recognizePageLocked(bitmap, recSpec)
                val bubbleRects = detections
                    .filter { it.classId != RtdetrPostprocess.CLASS_TEXT_FREE }
                    .map { it.rect to it.score }
                val grouped = BubbleGrouping.group(
                    bubbleRects,
                    ocrPage.lines,
                    rtl = rtl,
                    orphanPolicy = BubbleGrouping.OrphanPolicy.AS_OWN_BUBBLE,
                )
                // text_free 检测补充 OCR 漏掉的自由文本（拟声词等）：与已归并行不重叠才补
                val freeTexts = detections.filter { it.classId == RtdetrPostprocess.CLASS_TEXT_FREE }
                val covered = grouped.flatMap { bubble -> bubble.lines.map { it.box } }
                val extra = freeTexts
                    .filter { free -> covered.none { it.overlapRatio(free.rect) > 0.3f } }
                    .map { free ->
                        OcrBubble(
                            index = -1,
                            rect = free.rect,
                            lines = listOf(OcrTextLine("", free.rect, free.score)),
                            confidence = free.score,
                        )
                    }
                BubbleGrouping.sortBubbles(grouped + extra, rtl)
                    .mapIndexed { index, bubble -> bubble.copy(index = index) }
            }
        }

    /** 词典（assets 随 APK，与清单模型的类别表一致：blank + 逐行 + 空格）。 */
    fun charset(recSpec: OcrModelSpec): List<String> = charsets.getOrPut(recSpec.id) {
        val asset = when (recSpec.id) {
            "rec_ch" -> "ocr/ppocr_keys_v1.txt"
            "rec_en" -> "ocr/en_dict.txt"
            "rec_ja" -> "ocr/japan_dict.txt"
            else -> error("${recSpec.id} 不是识别模型")
        }
        val lines = context.assets.open(asset).bufferedReader(Charsets.UTF_8).use { reader ->
            reader.readLines().map { it.trimEnd('\r') }.filter { it.isNotEmpty() }
        }
        listOf("") + lines + listOf(" ")
    }

    /** det → rec 管线（调用方须已持有 [mutex]）。 */
    private fun recognizePageLocked(bitmap: Bitmap, recSpec: OcrModelSpec): OcrPage {
        val det = detSession()
        val rec = recSession(recSpec)
        val charset = charset(recSpec)
        val pageWidth = bitmap.width
        val pageHeight = bitmap.height

        // det：等比缩放 → (x/255-0.5) → NCHW
        val (detW, detH) = OcrPostprocess.detInputSize(pageWidth, pageHeight)
        val detInput = scaled(bitmap, detW, detH)
        val detTensor = tensor3ch(detInput, detW, detH)
        val probMap: FloatArray
        detTensor.use { input ->
            val inputName = det.inputNames.first()
            det.run(mapOf(inputName to input)).use { result ->
                probMap = readFloats(result.get(0) as OnnxTensor)
            }
        }
        val boxes = OcrPostprocess.decodeDetection(probMap, detW, detH, pageWidth, pageHeight)

        // rec：逐行裁剪 → 高 48 等比 → CTC 解码
        val lines = ArrayList<OcrTextLine>(boxes.size)
        for (box in boxes) {
            val crop = crop(bitmap, box) ?: continue
            val (recW, recH) = OcrPostprocess.recInputSize(crop.width, crop.height)
            val line = scaled(crop, recW, recH)
            if (line !== crop) crop.recycle()
            val recTensor = tensor3ch(line, recW, recH)
            if (line !== bitmap) line.recycle()
            val scores: FloatArray
            recTensor.use { input ->
                val inputName = rec.inputNames.first()
                rec.run(mapOf(inputName to input)).use { result ->
                    scores = readFloats(result.get(0) as OnnxTensor)
                }
            }
            val steps = scores.size / charset.size
            val (text, confidence) = OcrPostprocess.decodeRecognition(scores, steps, charset)
            if (text.isBlank()) continue
            lines += OcrTextLine(
                text = text,
                box = OcrPostprocess.normalizeBox(box, pageWidth, pageHeight),
                confidence = confidence,
            )
        }
        return OcrPage(lines)
    }

    /** RT-DETR 气泡检测（调用方须已持有 [mutex]）。 */
    private fun detectBubbleRectsLocked(bitmap: Bitmap): List<RtdetrPostprocess.BubbleDetection> {
        val bubble = bubbleSession()
        val pageWidth = bitmap.width
        val pageHeight = bitmap.height
        val input = scaled(bitmap, BUBBLE_INPUT, BUBBLE_INPUT)
        val images = tensor3ch(input, BUBBLE_INPUT, BUBBLE_INPUT, normalizeZeroOne = true)
        if (input !== bitmap) input.recycle()
        val sizes = OnnxTensor.createTensor(
            env,
            LongBuffer.wrap(longArrayOf(pageHeight.toLong(), pageWidth.toLong())),
            longArrayOf(1, 2),
        )
        images.use { img ->
            sizes.use { sz ->
                bubble.run(mapOf("images" to img, "orig_target_sizes" to sz)).use { result ->
                    val labels = readLongs(result.get("labels").get() as OnnxTensor)
                    val boxes = readFloats(result.get("boxes").get() as OnnxTensor)
                    val scores = readFloats(result.get("scores").get() as OnnxTensor)
                    return RtdetrPostprocess.decode(
                        labels = IntArray(labels.size) { labels[it].toInt() },
                        boxes = boxes,
                        scores = scores,
                        count = labels.size,
                        pageWidth = pageWidth,
                        pageHeight = pageHeight,
                    )
                }
            }
        }
    }

    private fun detSession(): OrtSession =
        detSession ?: env.createSession(modelManager.fileOf(ModelCatalog.DET).absolutePath)
            .also { detSession = it }

    private fun recSession(spec: OcrModelSpec): OrtSession =
        recSessions.getOrPut(spec.id) {
            env.createSession(modelManager.fileOf(spec).absolutePath)
        }

    private fun bubbleSession(): OrtSession =
        bubbleSession ?: env.createSession(modelManager.fileOf(ModelCatalog.BUBBLE).absolutePath)
            .also { bubbleSession = it }

    override fun close() {
        detSession?.close(); detSession = null
        recSessions.values.forEach { it.close() }; recSessions.clear()
        bubbleSession?.close(); bubbleSession = null
    }

    private fun scaled(src: Bitmap, w: Int, h: Int): Bitmap =
        if (src.width == w && src.height == h) src else Bitmap.createScaledBitmap(src, w, h, true)

    private fun crop(src: Bitmap, box: FloatArray): Bitmap? {
        val l = box[0].toInt().coerceIn(0, src.width - 1)
        val t = box[1].toInt().coerceIn(0, src.height - 1)
        val r = box[2].toInt().coerceIn(l + 1, src.width)
        val b = box[3].toInt().coerceIn(t + 1, src.height)
        return runCatching { Bitmap.createBitmap(src, l, t, r - l, b - t) }.getOrNull()
    }

    /**
     * 位图 → NCHW float 张量（RGB 三通道）。
     * 默认归一化 `(x/255-0.5)`（PaddleOCR det/rec，模型内部再除 0.5 的等效写法见
     * RapidOCR 预处理）；[normalizeZeroOne] 为 RT-DETR 的 `x/255`（不做均值方差）。
     */
    private fun tensor3ch(
        bitmap: Bitmap,
        w: Int,
        h: Int,
        normalizeZeroOne: Boolean = false,
    ): OnnxTensor {
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val data = FloatArray(3 * w * h)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            if (normalizeZeroOne) {
                data[i] = r / 255f; data[w * h + i] = g / 255f; data[2 * w * h + i] = b / 255f
            } else {
                data[i] = r / 255f - 0.5f; data[w * h + i] = g / 255f - 0.5f
                data[2 * w * h + i] = b / 255f - 0.5f
            }
        }
        return OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(data),
            longArrayOf(1, 3, h.toLong(), w.toLong()),
        )
    }

    private fun readFloats(tensor: OnnxTensor): FloatArray {
        val buffer = tensor.floatBuffer
        val out = FloatArray(buffer.remaining())
        buffer.get(out)
        return out
    }

    private fun readLongs(tensor: OnnxTensor): LongArray {
        val buffer = tensor.longBuffer
        val out = LongArray(buffer.remaining())
        buffer.get(out)
        return out
    }

    companion object {
        private const val BUBBLE_INPUT = 640
    }
}

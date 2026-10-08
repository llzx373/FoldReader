package com.llzx373.foldreader.core.ocr.android

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import com.llzx373.foldreader.core.ai.android.ModelManager
import com.llzx373.foldreader.core.inpaint.InpaintMask
import com.llzx373.foldreader.core.ocr.ModelCatalog
import com.llzx373.foldreader.core.ocr.OcrRect
import java.io.Closeable
import java.nio.FloatBuffer
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 气泡抹除（inpainting，M31）的 ONNX 会话层。
 *
 * 与 [OcrEngine] 同一套纪律：薄封装（可测几何都在 [InpaintMask] 纯 JVM 层）、
 * 模型未导入不创建 OrtEnvironment/会话、会话经 [mutex] 串行。
 *
 * 自定义模型契约（LaMa 系导出）：两个输入——image(1,3,H,W) 与 mask(1,1,H,W)
 * （按通道数区分，不依赖输入名），值域 0..1、mask 非零即抹除区，H/W 为 8 的倍数；
 * 输出 (1,3,H,W) 或 (3,H,W)，值域 0..1。任何一步不符或失败都返回 null——
 * 调用方（阅读器）回落「气泡铺采样底色」的既有渲染，功能永远可用。
 */
class InpaintEngine(
    private val modelManager: ModelManager,
) : Closeable {

    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    private var session: OrtSession? = null
    private val mutex = Mutex()

    /**
     * 抹除 [bitmap] 上 [rects]（页归一化坐标）的气泡区域。
     * 成功返回**新位图**（原图不改动，归调用方所有）；未就绪 / 失败返回 null。
     */
    suspend fun erase(bitmap: Bitmap, rects: List<OcrRect>): Bitmap? = withContext(Dispatchers.Default) {
        if (rects.isEmpty() || !modelManager.inpaintReady()) return@withContext null
        mutex.withLock { runCatching { eraseLocked(bitmap, rects) }.getOrNull() }
    }

    private fun eraseLocked(bitmap: Bitmap, rects: List<OcrRect>): Bitmap? {
        val session = session()
        val roi = InpaintMask.roiOf(rects) ?: return null

        // 输入按通道数辨认：3 通道 = image，1 通道 = mask（自定义模型输入名不可依赖）
        var imageName: String? = null
        var maskName: String? = null
        for ((name, info) in session.inputInfo) {
            val shape = (info.info as? TensorInfo)?.shape ?: continue
            if (shape.size != 4 || shape[0] != 1L) continue
            when (shape[1]) {
                3L -> imageName = name
                1L -> maskName = name
            }
        }
        if (imageName == null || maskName == null) return null

        // ROI 像素矩形（页归一化 → 位图像素）
        val rl = (roi.left * bitmap.width).roundToInt().coerceIn(0, bitmap.width - 1)
        val rt = (roi.top * bitmap.height).roundToInt().coerceIn(0, bitmap.height - 1)
        val rr = (roi.right * bitmap.width).roundToInt().coerceIn(rl + 1, bitmap.width)
        val rb = (roi.bottom * bitmap.height).roundToInt().coerceIn(rt + 1, bitmap.height)
        val roiBitmap = Bitmap.createBitmap(bitmap, rl, rt, rr - rl, rb - rt)

        val (iw, ih) = InpaintMask.inputSizeOf(roiBitmap.width, roiBitmap.height)
        val input = if (roiBitmap.width == iw && roiBitmap.height == ih) {
            roiBitmap
        } else {
            Bitmap.createScaledBitmap(roiBitmap, iw, ih, true)
        }
        try {
            val mask = InpaintMask.maskBytes(rects, roi, iw, ih)
            val imageTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(imageNchw(input, iw, ih)), longArrayOf(1, 3, ih.toLong(), iw.toLong()))
            val maskTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(maskNchw(mask)), longArrayOf(1, 1, ih.toLong(), iw.toLong()))
            val output: FloatArray
            val outShape: LongArray
            imageTensor.use { img ->
                maskTensor.use { msk ->
                    session.run(mapOf(imageName to img, maskName to msk)).use { result ->
                        val tensor = result.get(0) as? OnnxTensor ?: return null
                        outShape = tensor.info.shape
                        val buffer = tensor.floatBuffer
                        output = FloatArray(buffer.remaining())
                        buffer.get(output)
                    }
                }
            }

            // 输出 (1,3,H,W) 或 (3,H,W)，尺寸须与输入一致
            val outHw = when {
                outShape.size == 4 && outShape[0] == 1L && outShape[1] == 3L -> outShape[2].toInt() to outShape[3].toInt()
                outShape.size == 3 && outShape[0] == 3L -> outShape[1].toInt() to outShape[2].toInt()
                else -> null
            } ?: return null
            if (outHw.first != ih || outHw.second != iw || output.size < 3 * ih * iw) return null

            val pasted = bitmap.copy(Bitmap.Config.ARGB_8888, true) ?: return null
            val erasedRoi = nchwToBitmap(output, iw, ih)
            Canvas(pasted).drawBitmap(erasedRoi, null, android.graphics.Rect(rl, rt, rr, rb), null)
            erasedRoi.recycle()
            return pasted
        } finally {
            if (input !== roiBitmap) input.recycle()
            roiBitmap.recycle()
        }
    }

    private fun session(): OrtSession =
        session ?: env.createSession(modelManager.resolvedFileOf(ModelCatalog.INPAINT).absolutePath)
            .also { session = it }

    /** RGB → NCHW float，值域 0..1。 */
    private fun imageNchw(bitmap: Bitmap, w: Int, h: Int): FloatArray {
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val data = FloatArray(3 * w * h)
        for (i in pixels.indices) {
            val p = pixels[i]
            data[i] = ((p shr 16) and 0xFF) / 255f
            data[w * h + i] = ((p shr 8) and 0xFF) / 255f
            data[2 * w * h + i] = (p and 0xFF) / 255f
        }
        return data
    }

    /** 掩码字节图 → (1,1,H,W) float：非零 = 1.0（抹除区）。 */
    private fun maskNchw(mask: ByteArray): FloatArray =
        FloatArray(mask.size) { if (mask[it] != 0.toByte()) 1f else 0f }

    /** NCHW float（0..1，越界钳制）→ ARGB 位图。 */
    private fun nchwToBitmap(data: FloatArray, w: Int, h: Int): Bitmap {
        val pixels = IntArray(w * h)
        val plane = w * h
        for (i in pixels.indices) {
            fun ch(offset: Int): Int = (data[offset + i].coerceIn(0f, 1f) * 255f).roundToInt()
            pixels[i] = (0xFF shl 24) or (ch(0) shl 16) or (ch(plane) shl 8) or ch(2 * plane)
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    override fun close() {
        session?.close(); session = null
    }
}

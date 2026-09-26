package com.llzx373.foldreader.core.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OCR 后处理：DBNet 概率图连通域解码、CTC 贪心解码、输入尺寸换算。
 * 全部用合成张量验证，不依赖真实模型。
 */
class OcrPostprocessTest {

    /** 在 w×h 概率图上画一个高概率矩形区域。 */
    private fun heatmap(w: Int, h: Int, vararg rects: IntArray): FloatArray {
        val map = FloatArray(w * h) { 0.05f }
        for (r in rects) {
            for (y in r[1] until r[3]) {
                for (x in r[0] until r[2]) {
                    map[y * w + x] = 0.9f
                }
            }
        }
        return map
    }

    @Test
    fun `单个热区解码出一个外扩矩形并映射回原图`() {
        // 概率图 50x25，原图 100x50（2 倍）；热区 (10..20, 5..10)
        val map = heatmap(50, 25, intArrayOf(10, 5, 20, 10))
        val boxes = OcrPostprocess.decodeDetection(map, 50, 25, 100, 50, expandRatio = 0.0f)

        assertEquals(1, boxes.size)
        val box = boxes[0]
        assertEquals(20f, box[0], 0.01f)
        assertEquals(10f, box[1], 0.01f)
        assertEquals(40f, box[2], 0.01f)
        assertEquals(20f, box[3], 0.01f)
    }

    @Test
    fun `两个分离热区解码出两个框`() {
        val map = heatmap(50, 25, intArrayOf(2, 2, 10, 6), intArrayOf(30, 15, 45, 20))
        val boxes = OcrPostprocess.decodeDetection(map, 50, 25, 100, 50)
        assertEquals(2, boxes.size)
    }

    @Test
    fun `低于阈值的噪点不产框`() {
        val map = FloatArray(50 * 25) { 0.2f } // 全部低于 0.3 阈值
        val boxes = OcrPostprocess.decodeDetection(map, 50, 25, 100, 50)
        assertTrue(boxes.isEmpty())
    }

    @Test
    fun `过小连通域被当噪点过滤`() {
        // 2x2=4 像素，远小于 100*50*0.00005=0.25？不，4>0.25——用更大的 minAreaRatio
        val map = heatmap(50, 25, intArrayOf(10, 10, 12, 12))
        val kept = OcrPostprocess.decodeDetection(map, 50, 25, 100, 50, minAreaRatio = 0.0f)
        val dropped = OcrPostprocess.decodeDetection(map, 50, 25, 100, 50, minAreaRatio = 0.01f)
        assertEquals(1, kept.size)
        assertTrue(dropped.isEmpty())
    }

    @Test
    fun `外扩比例放大框且夹在页内`() {
        val map = heatmap(50, 25, intArrayOf(10, 5, 20, 10))
        val box = OcrPostprocess.decodeDetection(map, 50, 25, 100, 50, expandRatio = 0.2f)[0]
        // 原框 20..40 x 10..20，外扩后更宽更高，且不越界
        assertTrue(box[0] < 20f)
        assertTrue(box[2] > 40f)
        assertTrue(box.all { it >= 0f })
    }

    @Test
    fun `CTC 去重去空白解码`() {
        // charset: 0=blank, 1="あ", 2="い"
        val charset = listOf("", "あ", "い")
        // 时间步: あ あ blank い い い → "あい"
        val steps = 6
        val logits = FloatArray(steps * 3) { -10f }
        fun set(t: Int, c: Int) { logits[t * 3 + c] = 10f }
        set(0, 1); set(1, 1); set(2, 0); set(3, 2); set(4, 2); set(5, 2)
        val (text, confidence) = OcrPostprocess.decodeRecognition(logits, steps, charset)
        assertEquals("あい", text)
        assertTrue(confidence > 0.9f)
    }

    @Test
    fun `CTC 连续重复跨空白保留`() {
        val charset = listOf("", "a", "b")
        // a a blank a → "aa"（空白分隔的重复不去掉）
        val steps = 3
        val logits = FloatArray(steps * 3) { -10f }
        fun set(t: Int, c: Int) { logits[t * 3 + c] = 10f }
        set(0, 1); set(1, 0); set(2, 1)
        val (text, _) = OcrPostprocess.decodeRecognition(logits, steps, charset)
        assertEquals("aa", text)
    }

    @Test
    fun `全空白解码为空串零置信`() {
        val charset = listOf("", "x")
        val logits = FloatArray(4 * 2) { -10f }
        for (t in 0 until 4) logits[t * 2] = 10f // 全部 argmax=blank
        val (text, confidence) = OcrPostprocess.decodeRecognition(logits, 4, charset)
        assertEquals("", text)
        assertEquals(0f, confidence, 0.001f)
    }

    @Test
    fun `det 输入尺寸等比缩放到 32 倍数且长边受限`() {
        val (w, h) = OcrPostprocess.detInputSize(3000, 2000, maxSide = 960)
        assertTrue(w <= 960 && h <= 960)
        assertEquals(0, w % 32)
        assertEquals(0, h % 32)
        // 宽高比大致保持
        assertEquals(3000f / 2000f, w.toFloat() / h, 0.05f)
    }

    @Test
    fun `rec 输入尺寸高度固定宽度按比例取整`() {
        val (w, h) = OcrPostprocess.recInputSize(200, 40, height = 48)
        assertEquals(48, h)
        assertEquals(0, w % 8)
        assertTrue(w >= 200f / 40f * 48f)
    }
}

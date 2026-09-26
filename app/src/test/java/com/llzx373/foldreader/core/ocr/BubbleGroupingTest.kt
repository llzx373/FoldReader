package com.llzx373.foldreader.core.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 气泡归并：行中心/重叠归属、孤儿行策略、气泡框扩展、RTL/LTR 阅读序。
 */
class BubbleGroupingTest {

    private fun line(text: String, l: Float, t: Float, r: Float, b: Float, conf: Float = 0.9f) =
        OcrTextLine(text, OcrRect(l, t, r, b), conf)

    @Test
    fun `行中心落在气泡内即归属`() {
        val bubbles = listOf(OcrRect(0.1f, 0.1f, 0.5f, 0.4f) to 0.95f)
        val inside = line("台詞", 0.2f, 0.15f, 0.4f, 0.2f)
        val outside = line("旁白", 0.6f, 0.6f, 0.9f, 0.65f)

        val result = BubbleGrouping.group(bubbles, listOf(inside, outside), orphanPolicy = BubbleGrouping.OrphanPolicy.DROP)

        assertEquals(1, result.size)
        assertEquals(listOf("台詞"), result[0].lines.map { it.text })
    }

    @Test
    fun `行面积过半重叠即归属且取重叠最大者`() {
        val big = OcrRect(0.0f, 0.0f, 0.6f, 0.3f) to 0.9f
        val small = OcrRect(0.4f, 0.1f, 0.7f, 0.2f) to 0.9f
        // 行横跨两气泡，与 big 重叠 (0.2/0.4=0.5 边界不到)，与 small 重叠 0.2/0.4=0.5……
        // 构造：行 (0.3..0.6, 0.12..0.18)：与 big 交 0.3→0.5/0.3>0.5？算一下：
        // 行宽 0.3；与 big(0..0.6) 交 0.3 全宽 → overlap 1.0（行在 big 内）
        // 与 small(0.4..0.7) 交 0.2 → 0.67。应归 big。
        val row = line("x", 0.3f, 0.12f, 0.6f, 0.18f)
        val result = BubbleGrouping.group(listOf(big, small), listOf(row))
        assertEquals(1, result.size)
        assertTrue(result[0].rect.right >= 0.6f) // big 的并集
    }

    @Test
    fun `孤儿行成为单行气泡`() {
        val bubbles = listOf(OcrRect(0.1f, 0.1f, 0.3f, 0.2f) to 0.9f)
        val orphan = line("ゴゴゴ", 0.7f, 0.7f, 0.95f, 0.78f)
        val result = BubbleGrouping.group(
            bubbles, listOf(orphan),
            orphanPolicy = BubbleGrouping.OrphanPolicy.AS_OWN_BUBBLE,
        )
        assertEquals(1, result.size)
        assertEquals("ゴゴゴ", result[0].text)
        assertEquals(0, result[0].index)
    }

    @Test
    fun `空气泡（无行归属）被丢弃`() {
        val bubbles = listOf(
            OcrRect(0.1f, 0.1f, 0.3f, 0.2f) to 0.9f,
            OcrRect(0.5f, 0.5f, 0.7f, 0.7f) to 0.9f,
        )
        val row = line("あ", 0.15f, 0.12f, 0.25f, 0.18f)
        val result = BubbleGrouping.group(bubbles, listOf(row))
        assertEquals(1, result.size)
    }

    @Test
    fun `气泡框扩展为行框并集`() {
        val bubbles = listOf(OcrRect(0.2f, 0.2f, 0.4f, 0.3f) to 0.9f)
        // 行超出气泡右缘（检测框切掉文字的情形）
        val row = line("台詞", 0.25f, 0.22f, 0.5f, 0.28f)
        val result = BubbleGrouping.group(bubbles, listOf(row))
        assertEquals(0.5f, result[0].rect.right, 0.001f)
    }

    @Test
    fun `RTL 阅读序右上先 LTR 左上先`() {
        val lines = emptyList<OcrTextLine>()
        val bubbles = listOf(
            OcrRect(0.05f, 0.1f, 0.3f, 0.3f) to 0.9f,  // 左上
            OcrRect(0.6f, 0.1f, 0.9f, 0.3f) to 0.9f,   // 右上
            OcrRect(0.3f, 0.5f, 0.6f, 0.7f) to 0.9f,   // 下中
        )
        val inLines = listOf(
            line("左上", 0.1f, 0.15f, 0.25f, 0.2f),
            line("右上", 0.65f, 0.15f, 0.85f, 0.2f),
            line("下中", 0.35f, 0.55f, 0.55f, 0.6f),
        )
        val rtl = BubbleGrouping.group(bubbles, inLines, rtl = true)
        assertEquals(listOf("右上", "左上", "下中"), rtl.map { it.text })
        assertEquals(listOf(0, 1, 2), rtl.map { it.index })
        val ltr = BubbleGrouping.group(bubbles, inLines, rtl = false)
        assertEquals(listOf("左上", "右上", "下中"), ltr.map { it.text })
    }

    @Test
    fun `同带内按横排方向排序跨带先上后下`() {
        val bubbles = listOf(
            OcrRect(0.1f, 0.05f, 0.25f, 0.2f) to 0.9f,
            OcrRect(0.7f, 0.08f, 0.9f, 0.22f) to 0.9f,
        )
        val inLines = listOf(
            line("A", 0.12f, 0.08f, 0.2f, 0.15f),
            line("B", 0.75f, 0.1f, 0.85f, 0.18f),
        )
        val rtl = BubbleGrouping.group(bubbles, inLines, rtl = true)
        assertEquals(listOf("B", "A"), rtl.map { it.text })
    }

    @Test
    fun `气泡置信度取检测与行识别的最小值`() {
        val bubbles = listOf(OcrRect(0.1f, 0.1f, 0.5f, 0.4f) to 0.8f)
        val row = line("台詞", 0.2f, 0.15f, 0.4f, 0.2f, conf = 0.4f)
        val result = BubbleGrouping.group(bubbles, listOf(row))
        assertEquals(0.4f, result[0].confidence, 0.001f)
    }
}

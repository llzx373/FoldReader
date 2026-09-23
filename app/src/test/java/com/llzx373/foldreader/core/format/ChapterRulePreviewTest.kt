package com.llzx373.foldreader.core.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterRulePreviewTest {

    private val pattern = "^第\\d+章.*$"

    private fun buildBook(chapterCount: Int): String =
        (1..chapterCount).joinToString("\n") { "第${it}章 标题$it\n正文内容$it。" }

    @Test
    fun `正常切分且标题截取不超过 20 个`() {
        val preview = ChapterRulePreview.preview(buildBook(25), pattern)

        assertEquals(25, preview.chapterCount)
        assertEquals(ChapterRulePreview.TITLE_PREVIEW_LIMIT, preview.titles.size)
        assertEquals("第1章 标题1", preview.titles.first())
        assertEquals("第20章 标题20", preview.titles.last())
        assertTrue(preview.anomalies.isEmpty())
    }

    @Test
    fun `空章被标记`() {
        val text = "第1章 空章\n第2章 有内容\n正文。"
        val preview = ChapterRulePreview.preview(text, pattern)

        assertEquals(2, preview.chapterCount)
        assertTrue(preview.anomalies.any { it.contains("「第1章 空章」内容为空") })
    }

    @Test
    fun `超长章被标记`() {
        val text = "第1章 超长\n" + "字".repeat(41000) + "\n第2章 短\n正文。"
        val preview = ChapterRulePreview.preview(text, pattern)

        assertTrue(preview.anomalies.any { it.contains("「第1章 超长」超长") })
    }

    @Test
    fun `超过全文一半的章也被视为超长`() {
        // 全文约 110 字,第 1 章约 100 字,未达 40000 绝对阈值但超过 50%
        val text = "第1章 大头\n" + "字".repeat(100) + "\n第2章 小\n正文。"
        val preview = ChapterRulePreview.preview(text, pattern)

        assertTrue(preview.anomalies.any { it.contains("超长") })
    }

    @Test
    fun `孤儿文本被标记`() {
        val text = "引".repeat(600) + "\n第1章 标题\n正文。"
        val preview = ChapterRulePreview.preview(text, pattern)

        assertEquals(1, preview.chapterCount)
        assertTrue(preview.anomalies.any { it.contains("孤儿文本") })
    }

    @Test
    fun `少量孤儿文本不算异常`() {
        val text = "作者简介。\n第1章 标题\n正文。\n第2章 继续\n更多。"
        val preview = ChapterRulePreview.preview(text, pattern)

        assertTrue(preview.anomalies.none { it.contains("孤儿文本") })
    }

    @Test
    fun `无匹配时章节数过少异常`() {
        val preview = ChapterRulePreview.preview(buildBook(5), "^【.+】$")

        assertEquals(0, preview.chapterCount)
        assertTrue(preview.titles.isEmpty())
        assertTrue(preview.anomalies.any { it.contains("仅切出 0 章") })
    }

    @Test
    fun `正则无法编译时返回异常报告`() {
        val preview = ChapterRulePreview.preview(buildBook(3), "(")

        assertEquals(0, preview.chapterCount)
        assertTrue(preview.anomalies.single().startsWith("正则无法编译"))
    }
}

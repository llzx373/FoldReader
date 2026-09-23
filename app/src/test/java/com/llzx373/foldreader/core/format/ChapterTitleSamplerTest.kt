package com.llzx373.foldreader.core.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterTitleSamplerTest {

    @Test
    fun `各种标题形态均被粗筛命中`() {
        val text = buildString {
            append("第一章 关键词形态\n")
            append("第三卷 风起\n")
            append("第十二回 激战\n")
            append("Chapter 7 English Style\n")
            append("12、数字顿号\n")
            append("3. 数字点\n")
            append("7 纯数字空格开头\n")
            append("PROLOGUE\n")
            append("IV 罗马数字开头\n")
            append("楔子\n")
        }
        val sampled = ChapterTitleSampler.sample(text)

        assertEquals(text.lines().map { it.trim() }.filter { it.isNotEmpty() }, sampled)
    }

    @Test
    fun `超长行与明显正文行被排除`() {
        val longTitle = "第一章" + "很长的标题".repeat(20)
        val text = buildString {
            append("$longTitle\n")
            append("他走进了屋子,环顾四周。\n")
            append("今天天气不错,适合出门散步。\n")
            append("第一章 正常标题\n")
            append("\n")
            append("   \n")
        }
        val sampled = ChapterTitleSampler.sample(text)

        assertEquals(listOf("第一章 正常标题"), sampled)
    }

    @Test
    fun `略放宽的长度上限内容含关键词的 41 到 60 字行`() {
        val line = "第X章" + "长".repeat(50)
        assertTrue(line.length in 41..60)

        assertEquals(listOf(line), ChapterTitleSampler.sample(line))
    }

    @Test
    fun `超过上限时头中尾均匀抽取且首末必含`() {
        val total = 1000
        val text = (1..total).joinToString("\n") { "第${it}章 标题$it" }
        val sampled = ChapterTitleSampler.sample(text, maxLines = 100)

        assertEquals(100, sampled.size)
        assertEquals("第1章 标题1", sampled.first())
        assertEquals("第${total}章 标题$total", sampled.last())
        // 中段样本存在:约一半位置处的候选行应在结果中
        assertTrue(sampled.any { it.startsWith("第50") })
        // 顺序保持递增
        val indices = sampled.map { it.removePrefix("第").substringBefore("章").toInt() }
        assertEquals(indices.sorted(), indices)
    }

    @Test
    fun `不足上限时全部保留`() {
        val text = "第一章 甲\n正文。\n第二章 乙"
        assertEquals(listOf("第一章 甲", "第二章 乙"), ChapterTitleSampler.sample(text, maxLines = 300))
    }

    @Test
    fun `空文本与非法上限返回空列表`() {
        assertEquals(emptyList<String>(), ChapterTitleSampler.sample(""))
        assertEquals(emptyList<String>(), ChapterTitleSampler.sample("第一章 甲", maxLines = 0))
    }
}

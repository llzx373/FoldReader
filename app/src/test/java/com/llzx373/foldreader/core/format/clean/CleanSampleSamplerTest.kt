package com.llzx373.foldreader.core.format.clean

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M16 脏文本采样器单测：头尾广告高发区必含、小文本原样返回、确定性、整行对齐。
 */
class CleanSampleSamplerTest {

    @Test
    fun `空文本返回空串`() {
        assertEquals("", CleanSampleSampler.sample(""))
    }

    @Test
    fun `不超过预算的文本原样返回`() {
        val text = "第一章 开始\n" + "正文。".repeat(100)

        assertEquals(text, CleanSampleSampler.sample(text))
    }

    @Test
    fun `长文本头部与尾部的广告行都采到`() {
        val headAd = "本站网址 www.example.com 请记住"
        val tailAd = "全书完 更多精彩小说尽在 example 站"
        val text = buildString {
            appendLine(headAd)
            repeat(400) { appendLine("正文段落 $it，".repeat(30)) }
            appendLine(tailAd)
        }

        val sample = CleanSampleSampler.sample(text)

        assertTrue(sample.contains(headAd))
        assertTrue(sample.contains(tailAd))
    }

    @Test
    fun `长文本含中段切片与省略标记`() {
        val text = buildString {
            repeat(400) { appendLine("第 $it 段正文，".repeat(30)) }
        }
        // 真正跨过全文中点的那一行
        var offset = 0
        val middleLine = text.lines().first { line ->
            offset += line.length + 1
            offset > text.length / 2
        }

        val sample = CleanSampleSampler.sample(text)

        assertTrue(sample.contains("……（中间省略）……"))
        assertTrue(sample.contains(middleLine))
        // 采样体积有界：三段预算 + 两个分隔标记
        assertTrue(
            sample.length <= CleanSampleSampler.HEAD_CHARS +
                CleanSampleSampler.MIDDLE_CHARS +
                CleanSampleSampler.TAIL_CHARS + 40,
        )
    }

    @Test
    fun `采样确定性：同一文本两次结果一致`() {
        val text = buildString {
            repeat(400) { appendLine("正文 $it。".repeat(30)) }
        }

        assertEquals(CleanSampleSampler.sample(text), CleanSampleSampler.sample(text))
    }

    @Test
    fun `各段按整行对齐，不输出半截行`() {
        val text = buildString {
            repeat(400) { appendLine("整行文本 $it，".repeat(30)) }
        }

        val sample = CleanSampleSampler.sample(text)

        sample.split("……（中间省略）……").forEach { section ->
            val lines = section.trim('\n').lines()
            // 每段第一行都应是完整行（原文里存在这一行且以换行收尾）
            assertTrue(text.contains(lines.first() + "\n") || text.trimEnd().endsWith(lines.first()))
        }
    }
}

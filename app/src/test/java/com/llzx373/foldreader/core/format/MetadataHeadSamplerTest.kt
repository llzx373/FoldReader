package com.llzx373.foldreader.core.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** M17 元数据头部采样器：短文本原样、超长截断且整行对齐、确定性。 */
class MetadataHeadSamplerTest {

    @Test
    fun `短文本原样返回`() {
        val text = "书名：测试之书\n作者：某人\n\n第一章 开始\n正文……"
        assertEquals(text, MetadataHeadSampler.sample(text))
        // 空白文本 → 空串
        assertEquals("", MetadataHeadSampler.sample(""))
        assertEquals("", MetadataHeadSampler.sample("  \n  "))
    }

    @Test
    fun `超长截断到上限且丢掉末尾半行`() {
        val text = buildString {
            repeat(300) { appendLine("第 $it 行内容，".repeat(10)) }
        }
        val sample = MetadataHeadSampler.sample(text)
        assertTrue(sample.length < MetadataHeadSampler.HEAD_CHARS)
        assertTrue(sample.isNotEmpty())
        // 整行对齐：结尾必为完整行（最后一个字符是行内容，且截断点是换行处）
        assertEquals(text.substring(0, sample.length), sample)
        assertEquals('\n', text[sample.length])
    }

    @Test
    fun `采样确定性同输入同输出`() {
        val text = "开头".repeat(3000)
        assertEquals(MetadataHeadSampler.sample(text), MetadataHeadSampler.sample(text))
    }
}

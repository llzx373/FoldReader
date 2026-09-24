package com.llzx373.foldreader.core.ai.prompt

import com.llzx373.foldreader.core.ai.AiContent
import com.llzx373.foldreader.core.ai.AiRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 术语回填提示词的消息结构与解析容错。 */
class GlossaryBackfillPromptTest {

    @Test
    fun `消息结构 SYSTEM 契约 USER 对照样本`() {
        val messages = GlossaryBackfillPrompt.buildMessages("原文样本", "译文样本")
        assertEquals(2, messages.size)
        assertEquals(AiRole.SYSTEM, messages[0].role)
        assertEquals(AiRole.USER, messages[1].role)
        val systemText = (messages[0].content.single() as AiContent.Text).text
        val userText = (messages[1].content.single() as AiContent.Text).text
        assertTrue(systemText.contains("{\"terms\""))
        assertTrue(userText.contains("原文样本"))
        assertTrue(userText.contains("译文样本"))
    }

    @Test
    fun `解析标准输出`() {
        val terms = GlossaryBackfillPrompt.parseTerms(
            """{"terms": [{"source": "张三", "target": "Zhang San"}, {"source": "青云山", "target": "Azure Mountain"}]}""",
        )
        assertEquals(listOf("张三" to "Zhang San", "青云山" to "Azure Mountain"), terms)
    }

    @Test
    fun `容忍散文包裹与代码围栏`() {
        val raw = "好的，以下是提取结果：\n```json\n{\"terms\": [{\"source\": \"甲\", \"target\": \"A\"}]}\n```\n以上。"
        assertEquals(listOf("甲" to "A"), GlossaryBackfillPrompt.parseTerms(raw))
    }

    @Test
    fun `空 terms 与空数组都是空表`() {
        assertEquals(emptyList<Pair<String, String>>(), GlossaryBackfillPrompt.parseTerms("""{"terms": []}"""))
        assertEquals(emptyList<Pair<String, String>>(), GlossaryBackfillPrompt.parseTerms("没有找到术语"))
    }

    @Test
    fun `畸形 JSON 与空字段条目静默丢弃`() {
        assertEquals(emptyList<Pair<String, String>>(), GlossaryBackfillPrompt.parseTerms("{terms: [broken"))
        val terms = GlossaryBackfillPrompt.parseTerms(
            """{"terms": [{"source": "", "target": "x"}, {"source": "甲", "target": " "}, {"source": "乙", "target": "B"}]}""",
        )
        assertEquals(listOf("乙" to "B"), terms)
    }
}

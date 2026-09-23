package com.llzx373.foldreader.core.ai.prompt

import com.llzx373.foldreader.core.ai.AiContent
import com.llzx373.foldreader.core.ai.AiRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterRulePromptTest {

    private val validJson = """
        {"candidates": [
            {"regex": "^第[0-9０-９零一二三四五六七八九十百千万两]+章", "explanation": "第X章"},
            {"regex": "^[Cc]hapter\\s+\\d+", "explanation": "英文章节"}
        ]}
    """.trimIndent()

    @Test
    fun `正常 JSON 解析出全部候选`() {
        val candidates = ChapterRulePrompt.parseChapterRuleCandidates(validJson)

        assertEquals(2, candidates.size)
        assertEquals("^第[0-9０-９零一二三四五六七八九十百千万两]+章", candidates[0].regex)
        assertEquals("第X章", candidates[0].explanation)
    }

    @Test
    fun `代码围栏包裹可解析`() {
        val raw = "```json\n$validJson\n```"

        assertEquals(2, ChapterRulePrompt.parseChapterRuleCandidates(raw).size)
    }

    @Test
    fun `散文前后缀被截取忽略`() {
        val raw = "好的，这是归纳出的正则：\n$validJson\n希望对你有帮助！"

        assertEquals(2, ChapterRulePrompt.parseChapterRuleCandidates(raw).size)
    }

    @Test
    fun `JSON 畸形返回空列表`() {
        assertTrue(ChapterRulePrompt.parseChapterRuleCandidates("{\"candidates\": [ 不是JSON").isEmpty())
        assertTrue(ChapterRulePrompt.parseChapterRuleCandidates("完全没有花括号").isEmpty())
        assertTrue(ChapterRulePrompt.parseChapterRuleCandidates("").isEmpty())
    }

    @Test
    fun `非法正则候选被丢弃但合法候选保留`() {
        val raw = """
            {"candidates": [
                {"regex": "[未闭合", "explanation": "编译失败"},
                {"regex": "^第\\d+章", "explanation": "合法"}
            ]}
        """.trimIndent()

        val candidates = ChapterRulePrompt.parseChapterRuleCandidates(raw)

        assertEquals(1, candidates.size)
        assertEquals("^第\\d+章", candidates[0].regex)
    }

    @Test
    fun `regex 为空白的候选被丢弃`() {
        val raw = """
            {"candidates": [
                {"regex": "   ", "explanation": "空白"},
                {"regex": "", "explanation": "空串"},
                {"regex": "^尾声", "explanation": "合法"}
            ]}
        """.trimIndent()

        val candidates = ChapterRulePrompt.parseChapterRuleCandidates(raw)

        assertEquals(1, candidates.size)
        assertEquals("^尾声", candidates[0].regex)
    }

    @Test
    fun `candidates 缺失或为空返回空列表`() {
        assertTrue(ChapterRulePrompt.parseChapterRuleCandidates("{}").isEmpty())
        assertTrue(ChapterRulePrompt.parseChapterRuleCandidates("{\"candidates\": []}").isEmpty())
    }

    @Test
    fun `超过三条候选截断为三`() {
        val items = (1..5).joinToString(",") { """{"regex": "^第${it}章", "explanation": "$it"}""" }
        val candidates = ChapterRulePrompt.parseChapterRuleCandidates("""{"candidates": [$items]}""")

        assertEquals(ChapterRulePrompt.MAX_CANDIDATES, candidates.size)
        assertEquals("^第3章", candidates.last().regex)
    }

    @Test
    fun `buildMessages 返回 SYSTEM 与 USER 两条消息`() {
        val messages = ChapterRulePrompt.buildMessages(listOf("第一章 开始"))

        assertEquals(2, messages.size)
        assertEquals(AiRole.SYSTEM, messages[0].role)
        assertEquals(AiRole.USER, messages[1].role)
        val systemText = (messages[0].content.single() as AiContent.Text).text
        assertEquals(ChapterRulePrompt.SYSTEM_PROMPT, systemText)
        assertTrue(systemText.contains("{\"candidates\""))
    }

    @Test
    fun `buildMessages USER 原样附上采样行`() {
        val samples = listOf("第一章  风起", "  第二章 云涌  ", "Chapter 3")
        val messages = ChapterRulePrompt.buildMessages(samples)
        val userText = (messages[1].content.single() as AiContent.Text).text

        samples.forEach { assertTrue(userText.contains(it)) }
    }

    @Test
    fun `BuiltinPrompts 登记章节规则生成提示词全文`() {
        val entry = BuiltinPrompts.all.single()

        assertEquals("章节规则生成", entry.feature)
        assertEquals(ChapterRulePrompt.SYSTEM_PROMPT, entry.template)
    }
}

package com.llzx373.foldreader.core.ai.prompt

import com.llzx373.foldreader.core.ai.AiRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AI 校对提示词：消息结构（USER 只放正文保证偏移对齐）、JSON 契约解析、
 * 越界/错位/重复条目的丢弃与就近重定位。
 */
class ProofreadPromptTest {

    private val text = "他走的很快，心里即高兴又紧张。明天还要赶路。"

    @Test
    fun `buildMessages 结构为 SYSTEM + USER，USER 原样附正文`() {
        val messages = ProofreadPrompt.buildMessages(text)

        assertEquals(2, messages.size)
        assertEquals(AiRole.SYSTEM, messages[0].role)
        assertEquals(AiRole.USER, messages[1].role)
        val user = messages[1].content.single() as com.llzx373.foldreader.core.ai.AiContent.Text
        assertEquals(text, user.text)
    }

    @Test
    fun `解析标准契约输出`() {
        val offset = text.indexOf("的很快")
        val raw = """[{"offset": $offset, "length": 1, "original": "的", "suggestion": "得", "type": "typo"}]"""
        val issues = ProofreadPrompt.parseIssues(raw, text)

        assertEquals(1, issues!!.size)
        val issue = issues[0]
        assertEquals(offset, issue.offset)
        assertEquals(1, issue.length)
        assertEquals("的", issue.original)
        assertEquals("得", issue.suggestion)
        assertEquals(ProofreadIssue.Type.TYPO, issue.type)
    }

    @Test
    fun `解析容忍散文包裹与代码围栏`() {
        val offset = text.indexOf("即")
        val raw = "校对结果如下：\n```json\n[{\"offset\": $offset, \"length\": 1, \"original\": \"即\", \"suggestion\": \"既\", \"type\": \"typo\"}]\n```\n以上。"
        val issues = ProofreadPrompt.parseIssues(raw, text)

        assertEquals(1, issues!!.size)
        assertEquals("既", issues[0].suggestion)
    }

    @Test
    fun `JSON 畸形返回 null 由调用方重试`() {
        assertNull(ProofreadPrompt.parseIssues("没有发现数组", text))
        assertNull(ProofreadPrompt.parseIssues("[{not json}]", text))
    }

    @Test
    fun `空数组合法返回空表`() {
        assertEquals(emptyList<ProofreadIssue>(), ProofreadPrompt.parseIssues("[]", text))
    }

    @Test
    fun `区间越界或原文不符且无法重定位的条目丢弃`() {
        val raw = """
            [
              {"offset": 999, "length": 2, "original": "的", "suggestion": "得", "type": "typo"},
              {"offset": 0, "length": 1, "original": "不存在", "suggestion": "x", "type": "typo"},
              {"offset": 0, "length": 0, "original": "他", "suggestion": "她", "type": "typo"},
              {"offset": 0, "length": 1, "original": "他", "suggestion": "他", "type": "typo"}
            ]
        """.trimIndent()
        // 999 的重定位起点（offset-64）仍超出正文长度，找不到「的」；其余三条本就不合法 → 全丢
        val issues = ProofreadPrompt.parseIssues(raw, text)!!

        assertEquals(0, issues.size)
    }

    @Test
    fun `偏移数错但原文片段能对上时就近重定位`() {
        val raw = """[{"offset": 2, "length": 5, "original": "即", "suggestion": "既", "type": "typo"}]"""
        val issues = ProofreadPrompt.parseIssues(raw, text)!!

        assertEquals(1, issues.size)
        assertEquals(text.indexOf("即"), issues[0].offset)
        assertEquals(1, issues[0].length)
        assertEquals(ProofreadIssue.Type.TYPO, issues[0].type)
    }

    @Test
    fun `同一偏移重复条目只留第一条且结果按偏移升序`() {
        val a = text.indexOf("的")
        val b = text.indexOf("即")
        val raw = """[
            {"offset": $b, "length": 1, "original": "即", "suggestion": "既", "type": "typo"},
            {"offset": $a, "length": 1, "original": "的", "suggestion": "得", "type": "typo"},
            {"offset": $a, "length": 1, "original": "的", "suggestion": "地", "type": "typo"}
        ]"""
        val issues = ProofreadPrompt.parseIssues(raw, text)!!

        assertEquals(listOf(a, b), issues.map { it.offset })
        assertEquals("得", issues[0].suggestion)
    }

    @Test
    fun `未知 type 归 OTHER，grammar 映射病句`() {
        val a = text.indexOf("的")
        val b = text.indexOf("即")
        val raw = """[
            {"offset": $a, "length": 1, "original": "的", "suggestion": "得", "type": "grammar"},
            {"offset": $b, "length": 1, "original": "即", "suggestion": "既", "type": "whatever"}
        ]"""
        val issues = ProofreadPrompt.parseIssues(raw, text)!!

        assertEquals(ProofreadIssue.Type.GRAMMAR, issues[0].type)
        assertEquals(ProofreadIssue.Type.OTHER, issues[1].type)
    }

    @Test
    fun `超过 MAX_ISSUES 截断`() {
        // 构造 60 条都合法的条目（原文片段与区间逐字一致）
        val longText = "abcdefghijklmnopqrstuvwxyz0123456789甲乙丙丁戊己庚辛壬癸子丑寅卯辰巳午未申酉戌亥乾坤"
        val items = (0 until 60).map { i ->
            val ch = longText[i]
            """{"offset": $i, "length": 1, "original": "$ch", "suggestion": "${ch}x", "type": "typo"}"""
        }
        val issues = ProofreadPrompt.parseIssues(items.joinToString(",", "[", "]"), longText)!!

        assertEquals(ProofreadPrompt.MAX_ISSUES, issues.size)
    }

    @Test
    fun `登记表含 AI 校对且提示词声明输出契约`() {
        val registered = BuiltinPrompts.all.firstOrNull { it.feature == "AI 校对" }

        assertTrue(registered != null)
        assertEquals(ProofreadPrompt.SYSTEM_PROMPT, registered!!.template)
        assertTrue(registered.template.contains("\"offset\""))
        assertTrue(registered.template.contains("\"suggestion\""))
    }
}

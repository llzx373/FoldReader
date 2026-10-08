package com.llzx373.foldreader.core.ai.prompt

import com.llzx373.foldreader.core.ai.AiContent
import com.llzx373.foldreader.core.ai.AiRole
import com.llzx373.foldreader.core.ai.AiTargetLang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterSummaryPromptTest {

    @Test
    fun `buildMessages 返回 SYSTEM 与 USER 两条消息`() {
        val messages = ChapterSummaryPrompt.buildMessages("第一章 起风", "正文……", AiTargetLang.ZH_HANS)

        assertEquals(2, messages.size)
        assertEquals(AiRole.SYSTEM, messages[0].role)
        assertEquals(AiRole.USER, messages[1].role)
    }

    @Test
    fun `摘要语言注入 SYSTEM 且不留占位符`() {
        val cases = mapOf(
            AiTargetLang.ZH_HANS to "简体中文",
            AiTargetLang.EN to "English",
        )
        cases.forEach { (lang, name) ->
            val messages = ChapterSummaryPrompt.buildMessages("章", "正文", lang)
            val systemText = (messages[0].content.single() as AiContent.Text).text

            assertTrue(systemText.contains(name))
            assertFalse(systemText.contains(ChapterSummaryPrompt.TARGET_LANG_PLACEHOLDER))
            // 摘要契约：150~300 字、不评论不剧透
            assertTrue(systemText.contains("150~300"))
        }
    }

    @Test
    fun `单位标题与正文进 USER 消息`() {
        val messages = ChapterSummaryPrompt.buildMessages("第二章", "正文内容", AiTargetLang.ZH_HANS)
        val userText = (messages[1].content.single() as AiContent.Text).text

        assertTrue(userText.startsWith("《第二章》"))
        assertTrue(userText.contains("正文内容"))
    }

    @Test
    fun `parseSummary 剥离围栏与空白`() {
        assertEquals("摘要正文", ChapterSummaryPrompt.parseSummary("  摘要正文  \n"))
        assertEquals("摘要正文", ChapterSummaryPrompt.parseSummary("```\n摘要正文\n```"))
        assertEquals("摘要正文", ChapterSummaryPrompt.parseSummary("```text\n摘要正文```"))
    }

    @Test
    fun `parseSummary 空输出返回 null`() {
        assertNull(ChapterSummaryPrompt.parseSummary(""))
        assertNull(ChapterSummaryPrompt.parseSummary("   \n  "))
        assertNull(ChapterSummaryPrompt.parseSummary("```\n```"))
    }

    @Test
    fun `登记表含章节摘要与全书大纲且模板保留占位符`() {
        val summary = BuiltinPrompts.all.single { it.feature == "章节摘要" }
        val outline = BuiltinPrompts.all.single { it.feature == "全书大纲" }

        assertEquals(ChapterSummaryPrompt.SYSTEM_PROMPT, summary.template)
        assertTrue(summary.template.contains(ChapterSummaryPrompt.TARGET_LANG_PLACEHOLDER))
        assertEquals(BookOutlinePrompt.SYSTEM_PROMPT, outline.template)
        assertTrue(outline.template.contains(BookOutlinePrompt.TARGET_LANG_PLACEHOLDER))
    }
}

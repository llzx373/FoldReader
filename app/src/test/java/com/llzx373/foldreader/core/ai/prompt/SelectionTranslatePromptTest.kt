package com.llzx373.foldreader.core.ai.prompt

import com.llzx373.foldreader.core.ai.AiContent
import com.llzx373.foldreader.core.ai.AiRole
import com.llzx373.foldreader.core.ai.AiTargetLang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionTranslatePromptTest {

    @Test
    fun `buildMessages 返回 SYSTEM 与 USER 两条消息`() {
        val messages = SelectionTranslatePrompt.buildMessages("hello", AiTargetLang.ZH_HANS)

        assertEquals(2, messages.size)
        assertEquals(AiRole.SYSTEM, messages[0].role)
        assertEquals(AiRole.USER, messages[1].role)
    }

    @Test
    fun `目标语言注入 SYSTEM 且不留占位符`() {
        val cases = mapOf(
            AiTargetLang.ZH_HANS to "简体中文",
            AiTargetLang.ZH_HANT to "繁體中文",
            AiTargetLang.EN to "English",
            AiTargetLang.JA to "日本語",
        )
        cases.forEach { (lang, name) ->
            val messages = SelectionTranslatePrompt.buildMessages("text", lang)
            val systemText = (messages[0].content.single() as AiContent.Text).text

            assertTrue(systemText.contains(name))
            assertFalse(systemText.contains(SelectionTranslatePrompt.TARGET_LANG_PLACEHOLDER))
        }
    }

    @Test
    fun `原文原样进 USER 消息`() {
        val source = "  The quick brown fox。\n第二行  "
        val messages = SelectionTranslatePrompt.buildMessages(source, AiTargetLang.JA)
        val userText = (messages[1].content.single() as AiContent.Text).text

        assertEquals(source, userText)
    }

    @Test
    fun `登记表中的 SYSTEM_PROMPT 保留占位符模板`() {
        val entry = BuiltinPrompts.all.single { it.feature == "选中即译" }

        assertEquals(SelectionTranslatePrompt.SYSTEM_PROMPT, entry.template)
        assertTrue(entry.template.contains(SelectionTranslatePrompt.TARGET_LANG_PLACEHOLDER))
    }
}

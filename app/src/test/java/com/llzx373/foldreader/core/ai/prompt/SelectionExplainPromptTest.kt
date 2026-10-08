package com.llzx373.foldreader.core.ai.prompt

import com.llzx373.foldreader.core.ai.AiContent
import com.llzx373.foldreader.core.ai.AiRole
import com.llzx373.foldreader.core.ai.AiTargetLang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionExplainPromptTest {

    @Test
    fun `buildMessages 返回 SYSTEM 与 USER 两条消息`() {
        val messages = SelectionExplainPrompt.buildMessages("apple", AiTargetLang.ZH_HANS)

        assertEquals(2, messages.size)
        assertEquals(AiRole.SYSTEM, messages[0].role)
        assertEquals(AiRole.USER, messages[1].role)
    }

    @Test
    fun `解释语言注入 SYSTEM 且不留占位符`() {
        val cases = mapOf(
            AiTargetLang.ZH_HANS to "简体中文",
            AiTargetLang.EN to "English",
        )
        cases.forEach { (lang, name) ->
            val messages = SelectionExplainPrompt.buildMessages("word", lang)
            val systemText = (messages[0].content.single() as AiContent.Text).text

            assertTrue(systemText.contains(name))
            assertFalse(systemText.contains(SelectionExplainPrompt.TARGET_LANG_PLACEHOLDER))
            // 词典式输出契约：释义 / 词性 / 例句
            assertTrue(systemText.contains("词性"))
            assertTrue(systemText.contains("例句"))
        }
    }

    @Test
    fun `选中文字原样进 USER 消息`() {
        val source = "  take into account  "
        val messages = SelectionExplainPrompt.buildMessages(source, AiTargetLang.ZH_HANS)
        val userText = (messages[1].content.single() as AiContent.Text).text

        assertEquals(source, userText)
    }

    @Test
    fun `登记表含划词解释且模板保留占位符`() {
        val entry = BuiltinPrompts.all.single { it.feature == "划词解释" }

        assertEquals(SelectionExplainPrompt.SYSTEM_PROMPT, entry.template)
        assertTrue(entry.template.contains(SelectionExplainPrompt.TARGET_LANG_PLACEHOLDER))
    }
}

package com.llzx373.foldreader.core.ai.prompt

import com.llzx373.foldreader.core.ai.AiContent
import com.llzx373.foldreader.core.ai.AiRole
import com.llzx373.foldreader.core.ai.AiTargetLang
import com.llzx373.foldreader.core.summary.BookQaContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookQaPromptTest {

    private val context = BookQaContext(
        summaries = listOf("第一章" to "摘要一", "第二章" to "摘要二"),
        currentChapter = "第三章" to "当前章正文",
    )

    @Test
    fun `USER 消息依次拼摘要链与当前章与问题`() {
        val messages = BookQaPrompt.buildMessages("主角是谁？", context, AiTargetLang.ZH_HANS)

        assertEquals(2, messages.size)
        assertEquals(AiRole.SYSTEM, messages[0].role)
        assertEquals(AiRole.USER, messages[1].role)
        val userText = (messages[1].content.single() as AiContent.Text).text
        assertTrue(userText.contains("【前序章节摘要】"))
        assertTrue(userText.contains("《第一章》\n摘要一"))
        assertTrue(userText.contains("【当前章：第三章】\n当前章正文"))
        assertTrue(userText.endsWith("【问题】\n主角是谁？"))
        // 摘要链在前、当前章在后
        assertTrue(userText.indexOf("摘要一") < userText.indexOf("当前章正文"))
    }

    @Test
    fun `SYSTEM 约束来源标注与不虚构且语言注入`() {
        val systemText = (
            BookQaPrompt.buildMessages("问", context, AiTargetLang.EN).first().content.single()
                as AiContent.Text
            ).text

        assertTrue(systemText.contains("English"))
        assertFalse(systemText.contains(BookQaPrompt.TARGET_LANG_PLACEHOLDER))
        assertTrue(systemText.contains("不要虚构"))
        assertTrue(systemText.contains("《章节标题》"))
        assertTrue(systemText.contains("不要推测或剧透"))
    }

    @Test
    fun `登记表含问书且模板保留占位符`() {
        val entry = BuiltinPrompts.all.single { it.feature == "问书" }

        assertEquals(BookQaPrompt.SYSTEM_PROMPT, entry.template)
        assertTrue(entry.template.contains(BookQaPrompt.TARGET_LANG_PLACEHOLDER))
    }
}

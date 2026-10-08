package com.llzx373.foldreader.core.ai.prompt

import com.llzx373.foldreader.core.ai.AiContent
import com.llzx373.foldreader.core.ai.AiRole
import com.llzx373.foldreader.core.ai.AiTargetLang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BookOutlinePromptTest {

    @Test
    fun `摘要链按序编号进 USER 消息`() {
        val messages = BookOutlinePrompt.buildMessages(
            bookTitle = "某书",
            summaries = listOf("第一章" to "摘要一", "第二章" to "摘要二"),
            targetLang = AiTargetLang.ZH_HANS,
        )

        assertEquals(2, messages.size)
        assertEquals(AiRole.SYSTEM, messages[0].role)
        assertEquals(AiRole.USER, messages[1].role)
        val userText = (messages[1].content.single() as AiContent.Text).text
        assertTrue(userText.startsWith("《某书》"))
        // 编号从 1 起连续、标题与摘要都在
        assertTrue(userText.contains("【第1节 · 第一章】\n摘要一"))
        assertTrue(userText.contains("【第2节 · 第二章】\n摘要二"))
        assertTrue(userText.indexOf("摘要一") < userText.indexOf("摘要二"))
    }

    @Test
    fun `大纲语言注入 SYSTEM 且约束只依据摘要`() {
        val systemText = (
            BookOutlinePrompt.buildMessages("书", listOf("章" to "摘"), AiTargetLang.EN)
                .first().content.single() as AiContent.Text
            ).text

        assertTrue(systemText.contains("English"))
        assertTrue(systemText.contains("不要虚构"))
    }

    @Test
    fun `parseOutline 剥离围栏且空输出返回 null`() {
        assertEquals("大纲", BookOutlinePrompt.parseOutline("```\n大纲\n```"))
        assertNull(BookOutlinePrompt.parseOutline("  \n "))
    }
}

package com.llzx373.foldreader.core.ai.prompt

import com.llzx373.foldreader.core.ai.AiContent
import com.llzx373.foldreader.core.ai.AiRole
import com.llzx373.foldreader.core.ai.AiTargetLang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnitTranslatePromptTest {

    private fun systemText(messages: List<com.llzx373.foldreader.core.ai.AiMessage>) =
        (messages[0].content.single() as AiContent.Text).text

    private fun userText(messages: List<com.llzx373.foldreader.core.ai.AiMessage>) =
        (messages[1].content.single() as AiContent.Text).text

    @Test
    fun `buildMessages 返回 SYSTEM 与 USER 且目标语言注入不留占位符`() {
        val messages = UnitTranslatePrompt.buildMessages(listOf("甲", "乙"), AiTargetLang.JA)

        assertEquals(2, messages.size)
        assertEquals(AiRole.SYSTEM, messages[0].role)
        assertEquals(AiRole.USER, messages[1].role)
        assertTrue(systemText(messages).contains("日本語"))
        assertFalse(systemText(messages).contains(UnitTranslatePrompt.TARGET_LANG_PLACEHOLDER))
    }

    @Test
    fun `USER 消息按编号逐段附上`() {
        val messages = UnitTranslatePrompt.buildMessages(listOf("第一段", "第二段"), AiTargetLang.ZH_HANS)

        assertEquals("<1>\n第一段\n\n<2>\n第二段", userText(messages))
    }

    @Test
    fun `术语表非空时 SYSTEM 追加术语对照节`() {
        val withGlossary = UnitTranslatePrompt.buildMessages(
            listOf("x"), AiTargetLang.ZH_HANS,
            glossary = listOf("葉山" to "叶山", "桜" to "樱"),
        )
        val text = systemText(withGlossary)
        assertTrue(text.contains("术语对照"))
        assertTrue(text.contains("葉山 → 叶山"))
        assertTrue(text.contains("桜 → 樱"))

        val without = UnitTranslatePrompt.buildMessages(listOf("x"), AiTargetLang.ZH_HANS)
        assertFalse(systemText(without).contains("术语对照"))
    }

    @Test
    fun `systemOverride 整体替换内置系统提示词`() {
        val messages = UnitTranslatePrompt.buildMessages(
            listOf("x"), AiTargetLang.EN, systemOverride = "临时提示词",
        )

        assertEquals("临时提示词", systemText(messages))
    }

    @Test
    fun `parseParagraphs 解析正常输出并容忍散文包裹与代码围栏`() {
        val raw = "好的，以下是翻译：\n```json\n{\"paragraphs\": [\"一\", \"二\"]}\n```\n完毕。"
        assertEquals(listOf("一", "二"), UnitTranslatePrompt.parseParagraphs(raw, 2))
    }

    @Test
    fun `parseParagraphs 数量不一致或 JSON 畸形返回 null`() {
        assertNull(UnitTranslatePrompt.parseParagraphs("{\"paragraphs\": [\"一\"]}", 2))
        assertNull(UnitTranslatePrompt.parseParagraphs("{\"paragraphs\": [\"一\", \"二\", \"三\"]}", 2))
        assertNull(UnitTranslatePrompt.parseParagraphs("不是 JSON", 1))
        assertNull(UnitTranslatePrompt.parseParagraphs("{\"other\": []}", 1))
    }

    @Test
    fun `登记表中的 SYSTEM_PROMPT 保留占位符模板`() {
        val entry = BuiltinPrompts.all.single { it.feature == "章节翻译" }

        assertEquals(UnitTranslatePrompt.SYSTEM_PROMPT, entry.template)
        assertTrue(entry.template.contains(UnitTranslatePrompt.TARGET_LANG_PLACEHOLDER))
    }
}

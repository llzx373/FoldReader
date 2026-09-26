package com.llzx373.foldreader.core.ai.prompt

import com.llzx373.foldreader.core.ai.AiRole
import com.llzx373.foldreader.core.ai.AiTargetLang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 漫画翻译提示词：消息结构、目标语言注入、术语注入、畸形 JSON、数量校验、
 * 流式部分数组提取、临时提示词当次生效（R11）、登记表占位符。
 */
class ComicTranslatePromptTest {

    @Test
    fun `消息结构 SYSTEM 任务契约 USER 编号气泡`() {
        val messages = ComicTranslatePrompt.buildMessages(
            bubbleTexts = listOf("行くぞ！", "待って…"),
            targetLang = AiTargetLang.ZH_HANS,
        )
        assertEquals(2, messages.size)
        assertEquals(AiRole.SYSTEM, messages[0].role)
        assertEquals(AiRole.USER, messages[1].role)
        val system = (messages[0].content[0] as com.llzx373.foldreader.core.ai.AiContent.Text).text
        val user = (messages[1].content[0] as com.llzx373.foldreader.core.ai.AiContent.Text).text
        assertTrue(system.contains("简体中文"))
        assertTrue(system.contains("{\"translations\""))
        assertTrue(user.contains("<1>\n行くぞ！"))
        assertTrue(user.contains("<2>\n待って…"))
    }

    @Test
    fun `目标语言按枚举注入`() {
        for ((lang, name) in mapOf(
            AiTargetLang.ZH_HANS to "简体中文",
            AiTargetLang.ZH_HANT to "繁體中文",
            AiTargetLang.EN to "English",
            AiTargetLang.JA to "日本語",
        )) {
            val messages = ComicTranslatePrompt.buildMessages(listOf("a"), lang)
            val system = (messages[0].content[0] as com.llzx373.foldreader.core.ai.AiContent.Text).text
            assertTrue("$lang 未注入", system.contains(name))
        }
    }

    @Test
    fun `术语对照非空时追加注入`() {
        val messages = ComicTranslatePrompt.buildMessages(
            bubbleTexts = listOf("あ"),
            targetLang = AiTargetLang.ZH_HANS,
            glossary = listOf("桜木花道" to "樱木花道", "流川楓" to "流川枫"),
        )
        val system = (messages[0].content[0] as com.llzx373.foldreader.core.ai.AiContent.Text).text
        assertTrue(system.contains("术语对照"))
        assertTrue(system.contains("桜木花道 → 樱木花道"))
        assertTrue(system.contains("流川楓 → 流川枫"))
    }

    @Test
    fun `临时提示词整体替换 SYSTEM 且不动模板`() {
        val messages = ComicTranslatePrompt.buildMessages(
            bubbleTexts = listOf("あ"),
            targetLang = AiTargetLang.ZH_HANS,
            systemOverride = "译成本地方言",
        )
        val system = (messages[0].content[0] as com.llzx373.foldreader.core.ai.AiContent.Text).text
        assertEquals("译成本地方言", system)
        // 内置模板仍带占位符原文（设置页看到的是模板）
        assertTrue(ComicTranslatePrompt.SYSTEM_PROMPT.contains(ComicTranslatePrompt.TARGET_LANG_PLACEHOLDER))
    }

    @Test
    fun `解析容忍散文包裹与代码围栏`() {
        val raw = "好的，翻译如下：\n```json\n{\"translations\": [\"走吧！\", \"等等…\"]}\n```\n完毕"
        assertEquals(listOf("走吧！", "等等…"), ComicTranslatePrompt.parseTranslations(raw, 2))
    }

    @Test
    fun `数量不一致或畸形返回 null`() {
        assertNull(ComicTranslatePrompt.parseTranslations("{\"translations\": [\"只有一个\"]}", 2))
        assertNull(ComicTranslatePrompt.parseTranslations("{not json", 1))
        assertNull(ComicTranslatePrompt.parseTranslations("{\"other\": []}", 0))
    }

    @Test
    fun `流式部分数组逐元素闭合提取`() {
        val prefix = "{\"translations\": [\"走吧！\", \"等等"
        assertEquals(listOf("走吧！"), PartialJsonArray.extractCompleteStrings(prefix))
        val more = "$prefix…\", \"第三个"
        assertEquals(listOf("走吧！", "等等…"), PartialJsonArray.extractCompleteStrings(more))
    }

    @Test
    fun `登记表含漫画翻译且模板保留占位符`() {
        val registered = BuiltinPrompts.all.firstOrNull { it.feature == "漫画翻译" }
        assertTrue(registered != null)
        assertTrue(registered!!.template.contains(ComicTranslatePrompt.TARGET_LANG_PLACEHOLDER))
    }
}

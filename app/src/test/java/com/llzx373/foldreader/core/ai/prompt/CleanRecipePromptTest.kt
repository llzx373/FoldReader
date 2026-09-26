package com.llzx373.foldreader.core.ai.prompt

import com.llzx373.foldreader.core.ai.AiContent
import com.llzx373.foldreader.core.ai.AiRole
import com.llzx373.foldreader.core.format.clean.CleanLevel
import com.llzx373.foldreader.core.format.clean.CleanToggles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CleanRecipePromptTest {

    private val validJson = """
        {"toggles": {"normalizeRepeatedPunctuation": true, "reflowParagraphs": false},
         "adPatterns": ["^更多精彩小说.*$", "^本书首发.*$"],
         "explanation": "尾部有推广行，正文被硬换行拆开但标点完整"}
    """.trimIndent()

    @Test
    fun `正常 JSON 解析出开关覆盖与广告正则`() {
        val suggestion = CleanRecipePrompt.parseSuggestion(validJson)!!

        assertEquals(
            mapOf("normalizeRepeatedPunctuation" to true, "reflowParagraphs" to false),
            suggestion.toggles,
        )
        assertEquals(listOf("^更多精彩小说.*$", "^本书首发.*$"), suggestion.adPatterns)
        assertTrue(suggestion.explanation.contains("推广行"))
    }

    @Test
    fun `代码围栏包裹可解析`() {
        val raw = "```json\n$validJson\n```"

        assertEquals(2, CleanRecipePrompt.parseSuggestion(raw)!!.adPatterns.size)
    }

    @Test
    fun `散文前后缀被截取忽略`() {
        val raw = "好的，这是我的建议：\n$validJson\n希望对你有帮助！"

        assertEquals(2, CleanRecipePrompt.parseSuggestion(raw)!!.toggles.size)
    }

    @Test
    fun `JSON 畸形返回 null`() {
        assertNull(CleanRecipePrompt.parseSuggestion("{\"toggles\": 不是JSON"))
        assertNull(CleanRecipePrompt.parseSuggestion("完全没有花括号"))
        assertNull(CleanRecipePrompt.parseSuggestion(""))
    }

    @Test
    fun `未知开关 key 被丢弃，已知 key 保留`() {
        val raw = """
            {"toggles": {"maskRuns": false, "inventedToggle": true, "filterNoise2": false},
             "explanation": "x"}
        """.trimIndent()

        val suggestion = CleanRecipePrompt.parseSuggestion(raw)!!

        assertEquals(mapOf("maskRuns" to false), suggestion.toggles)
    }

    @Test
    fun `非法或空白正则丢弃但合法正则保留`() {
        val raw = """
            {"adPatterns": ["[未闭合", "   ", "^广告$"], "explanation": "x"}
        """.trimIndent()

        val suggestion = CleanRecipePrompt.parseSuggestion(raw)!!

        assertEquals(listOf("^广告$"), suggestion.adPatterns)
    }

    @Test
    fun `广告正则超过三条截断为三`() {
        val items = (1..5).joinToString(",") { """"^广告$it"""" }
        val suggestion = CleanRecipePrompt.parseSuggestion("""{"adPatterns": [$items]}""")!!

        assertEquals(CleanRecipePrompt.MAX_AD_PATTERNS, suggestion.adPatterns.size)
        assertEquals("^广告3", suggestion.adPatterns.last())
    }

    @Test
    fun `字段缺失时按空建议解析而非失败`() {
        val suggestion = CleanRecipePrompt.parseSuggestion("{}")!!

        assertTrue(suggestion.toggles.isEmpty())
        assertTrue(suggestion.adPatterns.isEmpty())
        assertEquals("", suggestion.explanation)
    }

    @Test
    fun `buildProfile 以标准档为基线应用覆盖并落到自定义档`() {
        val profile = CleanRecipePrompt.buildProfile(
            CleanRecipeSuggestion(
                toggles = mapOf("normalizeRepeatedPunctuation" to true, "reflowParagraphs" to false),
                adPatterns = listOf("^广告$"),
                explanation = "x",
            ),
        )

        assertEquals(CleanLevel.CUSTOM, profile.level)
        // 覆盖生效
        assertTrue(profile.toggles.normalizeRepeatedPunctuation)
        assertFalse(profile.toggles.reflowParagraphs)
        // 标准档基线其余开关不受影响
        assertTrue(profile.toggles.filterNoise)
        assertTrue(profile.toggles.canonicalIndent)
        assertEquals(CleanToggles.preset(CleanLevel.STANDARD).trimLines, profile.toggles.trimLines)
        assertEquals(listOf(Regex("^广告$").pattern), profile.adPatterns.map { it.pattern })
    }

    @Test
    fun `buildProfile 容忍未知 key 与空建议`() {
        val profile = CleanRecipePrompt.buildProfile(
            CleanRecipeSuggestion(toggles = mapOf("inventedToggle" to true)),
        )

        assertEquals(CleanToggles.preset(CleanLevel.STANDARD), profile.toggles)
        assertTrue(profile.adPatterns.isEmpty())
    }

    @Test
    fun `buildMessages 返回 SYSTEM 与 USER 两条消息且清单覆盖全部开关`() {
        val messages = CleanRecipePrompt.buildMessages("采样文本")

        assertEquals(2, messages.size)
        assertEquals(AiRole.SYSTEM, messages[0].role)
        assertEquals(AiRole.USER, messages[1].role)
        val systemText = (messages[0].content.single() as AiContent.Text).text
        assertEquals(CleanRecipePrompt.SYSTEM_PROMPT, systemText)
        CleanToggles.ENTRIES.forEach { entry ->
            assertTrue("清单应包含 ${entry.key}", systemText.contains(entry.key))
        }
        val userText = (messages[1].content.single() as AiContent.Text).text
        assertTrue(userText.contains("采样文本"))
    }

    @Test
    fun `BuiltinPrompts 登记清洗配方推荐提示词全文`() {
        val entry = BuiltinPrompts.all.single { it.feature == "清洗配方推荐" }

        assertEquals(CleanRecipePrompt.SYSTEM_PROMPT, entry.template)
    }
}

package com.llzx373.foldreader.core.ai.prompt

import com.llzx373.foldreader.core.ai.AiContent
import com.llzx373.foldreader.core.ai.AiRole
import com.llzx373.foldreader.core.metadata.GenreTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M17「AI 元数据补全」提示词与解析的单测：
 * 消息结构、散文/代码围栏容忍、畸形 JSON、字段归一（空白 / 未知题材 / 简介截断）、登记表。
 */
class MetadataPromptTest {

    @Test
    fun `规整 JSON 解析出全部字段`() {
        val suggestion = MetadataPrompt.parseSuggestion(
            """{"author": "猫腻", "synopsis": "少年逆天改命。", "genreTag": "玄幻"}""",
        )!!
        assertEquals("猫腻", suggestion.author)
        assertEquals("少年逆天改命。", suggestion.synopsis)
        assertEquals("玄幻", suggestion.genreTag)
    }

    @Test
    fun `容忍散文包裹与代码围栏`() {
        val fenced = """
            好的，这是分析结果：
            ```json
            {"author": "刘慈欣", "synopsis": "面壁计划。", "genreTag": "科幻"}
            ```
            希望对你有帮助。
        """.trimIndent()
        val suggestion = MetadataPrompt.parseSuggestion(fenced)!!
        assertEquals("刘慈欣", suggestion.author)
        assertEquals("科幻", suggestion.genreTag)
    }

    @Test
    fun `畸形 JSON 或纯散文返回 null`() {
        assertNull(MetadataPrompt.parseSuggestion("这本书的作者大概是某某"))
        assertNull(MetadataPrompt.parseSuggestion("""{"author": "无结尾"""))
        assertNull(MetadataPrompt.parseSuggestion(""))
    }

    @Test
    fun `空白字段归一为 null 且缺省字段可解析`() {
        val suggestion = MetadataPrompt.parseSuggestion(
            """{"author": "  ", "genreTag": "悬疑"}""",
        )!!
        assertNull(suggestion.author)
        assertNull(suggestion.synopsis)
        assertEquals("悬疑", suggestion.genreTag)
    }

    @Test
    fun `未知题材标签丢弃而非自造`() {
        val suggestion = MetadataPrompt.parseSuggestion(
            """{"author": "甲", "genreTag": "赛博朋克"}""",
        )!!
        assertNull(suggestion.genreTag)
        // 枚举内的「其他」是合法输出
        assertEquals("其他", MetadataPrompt.parseSuggestion("""{"genreTag": "其他"}""")!!.genreTag)
    }

    @Test
    fun `简介超长截断到上限`() {
        val longSynopsis = "简".repeat(MetadataPrompt.MAX_SYNOPSIS_CHARS + 50)
        val suggestion = MetadataPrompt.parseSuggestion(
            """{"synopsis": "$longSynopsis"}""",
        )!!
        assertEquals(MetadataPrompt.MAX_SYNOPSIS_CHARS, suggestion.synopsis!!.length)
    }

    @Test
    fun `buildMessages 含文件名提示与采样文本且题材清单进系统提示词`() {
        val messages = MetadataPrompt.buildMessages("开头文本若干", "三体.txt")
        assertEquals(2, messages.size)
        assertEquals(AiRole.SYSTEM, messages[0].role)
        assertEquals(AiRole.USER, messages[1].role)
        val systemText = (messages[0].content.single() as AiContent.Text).text
        val userText = (messages[1].content.single() as AiContent.Text).text
        GenreTags.ALL.forEach { assertTrue(systemText.contains(it)) }
        assertTrue(systemText.contains("不要输出书名"))
        assertTrue(userText.contains("三体.txt"))
        assertTrue(userText.contains("开头文本若干"))
    }

    @Test
    fun `提示词登记进内置提示词登记表`() {
        val registered = BuiltinPrompts.all.singleOrNull { it.feature == "元数据补全" }
        assertNotNull(registered)
        assertEquals(MetadataPrompt.SYSTEM_PROMPT, registered!!.template)
    }
}

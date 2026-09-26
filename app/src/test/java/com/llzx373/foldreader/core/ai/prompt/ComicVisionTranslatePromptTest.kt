package com.llzx373.foldreader.core.ai.prompt

import com.llzx373.foldreader.core.ai.AiContent
import com.llzx373.foldreader.core.ai.AiTargetLang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M23 视觉翻译提示词：消息结构（图片块在 USER）、目标语言与术语注入、
 * 输出契约解析（界内校验、围栏/散文容忍、垃圾即整页失败）。
 */
class ComicVisionTranslatePromptTest {

    @Test
    fun `消息结构为系统加图片用户消息且注入目标语言与术语`() {
        val messages = ComicVisionTranslatePrompt.buildMessages(
            imageJpegBase64 = "QUJD",
            targetLang = AiTargetLang.ZH_HANS,
            glossary = listOf("勇者" to "Brave"),
        )
        assertEquals(2, messages.size)
        val system = (messages[0].content[0] as AiContent.Text).text
        assertTrue(system.contains("简体中文"))
        assertTrue(system.contains("勇者 → Brave"))
        val user = messages[1].content
        val image = user.filterIsInstance<AiContent.Image>().single()
        assertEquals("image/jpeg", image.mimeType)
        assertEquals("QUJD", image.base64)
        assertTrue(user.filterIsInstance<AiContent.Text>().isNotEmpty())
    }

    @Test
    fun `解析合法数组并保留坐标与原文`() {
        val raw = """
            [{"box":[0.1,0.2,0.5,0.4],"source":"行くぞ","translation":"走吧"},
             {"box":[0.6,0.5,0.9,0.8],"source":"待って","translation":"等等"}]
        """.trimIndent()
        val parsed = ComicVisionTranslatePrompt.parseBubbles(raw)!!
        assertEquals(2, parsed.size)
        assertEquals("行くぞ", parsed[0].source)
        assertEquals("走吧", parsed[0].translation)
        assertEquals(0.1f, parsed[0].rect.left, 0.001f)
        assertEquals(0.4f, parsed[0].rect.bottom, 0.001f)
    }

    @Test
    fun `容忍散文包裹与代码围栏`() {
        val raw = "好的，结果如下：\n```json\n[{\"box\":[0.1,0.1,0.2,0.2],\"source\":\"あ\",\"translation\":\"啊\"}]\n```\n以上。"
        val parsed = ComicVisionTranslatePrompt.parseBubbles(raw)
        assertNotNull(parsed)
        assertEquals(1, parsed!!.size)
    }

    @Test
    fun `空数组合法返回空表`() {
        assertEquals(0, ComicVisionTranslatePrompt.parseBubbles("[]")!!.size)
    }

    @Test
    fun `坐标越界或框不成立即整体失败`() {
        // 越界
        assertNull(ComicVisionTranslatePrompt.parseBubbles("[{\"box\":[-0.1,0.2,0.5,0.4],\"source\":\"a\",\"translation\":\"b\"}]"))
        // l >= r
        assertNull(ComicVisionTranslatePrompt.parseBubbles("[{\"box\":[0.5,0.2,0.5,0.4],\"source\":\"a\",\"translation\":\"b\"}]"))
        // 四元不全
        assertNull(ComicVisionTranslatePrompt.parseBubbles("[{\"box\":[0.1,0.2,0.5],\"source\":\"a\",\"translation\":\"b\"}]"))
        // 空译文
        assertNull(ComicVisionTranslatePrompt.parseBubbles("[{\"box\":[0.1,0.2,0.5,0.4],\"source\":\"a\",\"translation\":\" \"}]"))
        // 畸形 JSON
        assertNull(ComicVisionTranslatePrompt.parseBubbles("[{broken"))
    }
}

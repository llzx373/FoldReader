package com.llzx373.foldreader.core.ai.real

import com.llzx373.foldreader.core.ai.AiConfig
import com.llzx373.foldreader.core.ai.AiProviderFactory
import com.llzx373.foldreader.core.ai.AiTargetLang
import com.llzx373.foldreader.core.ai.prompt.ComicTranslatePrompt
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 漫画气泡翻译的真实 API 冒烟：两个假气泡（一句日文对白 + 一个拟声词）走
 * ComicTranslatePrompt 的结构化输出契约。会产生极小费用。
 *
 * 无 AI_API_KEY / 无可用模型时整组 assume 跳过（CI / 无凭据环境不阻塞构建）。
 * 模型口径：优先 AI_MODEL_TRANSLATION，缺省回落 RealApiTest 同款的 AI_MODEL_GENERAL。
 */
class ComicTranslateRealApiTest {

    @Test(timeout = 180_000)
    fun `漫画气泡翻译真实调用`() = runBlocking {
        assumeTrue("无 AI_API_KEY，跳过真实 API 测试", RealApiConfig.apiKey.isNotBlank())
        val model = RealApiConfig.props["AI_MODEL_TRANSLATION"].orEmpty()
            .ifBlank { RealApiConfig.props["AI_MODEL_GENERAL"].orEmpty() }
        assumeTrue("无 AI_MODEL_TRANSLATION/AI_MODEL_GENERAL，跳过真实 API 测试", model.isNotBlank())

        val config = AiConfig(
            protocol = RealApiConfig.protocol,
            baseUrl = RealApiConfig.baseUrl,
            apiKey = RealApiConfig.apiKey,
        )
        val provider = AiProviderFactory.create(config, AiProviderFactory.defaultClient(60))
        val messages = ComicTranslatePrompt.buildMessages(
            bubbleTexts = listOf("おい、待てよ！", "ドドドドド"),
            targetLang = AiTargetLang.ZH_HANS,
        )
        val text = provider.chat(messages, model).toList().joinToString("")
        println("[ComicTranslateRealApiTest] 漫画翻译回复（$model）：$text")

        val parsed = ComicTranslatePrompt.parseTranslations(text, 2)
        assertNotNull("模型输出应能解析出 2 个气泡译文，实际：$text", parsed)
        assertEquals(2, parsed!!.size)
    }
}

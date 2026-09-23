package com.llzx373.foldreader.core.ai.real

import com.llzx373.foldreader.core.ai.AiConfig
import com.llzx373.foldreader.core.ai.AiContent
import com.llzx373.foldreader.core.ai.AiMessage
import com.llzx373.foldreader.core.ai.AiProtocol
import com.llzx373.foldreader.core.ai.AiProviderFactory
import com.llzx373.foldreader.core.ai.AiRole
import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 真实 API 冒烟测试：命中 .env 配置的服务商，会产生极小费用。
 *
 * 无 AI_API_KEY 时整组跳过（CI / 无凭据环境不阻塞构建）。
 * 用 runBlocking 而非 runTest：真实网络 + 虚拟时间不兼容。
 */
class RealApiTest {

    companion object {
        private val props: Map<String, String> get() = RealApiConfig.props

        private val apiKey: String get() = RealApiConfig.apiKey
        private val baseUrl: String get() = RealApiConfig.baseUrl
        private val protocol: AiProtocol get() = RealApiConfig.protocol
    }

    @Test(timeout = 180_000)
    fun `文本流式真实调用`() = runBlocking {
        assumeTrue("无 AI_API_KEY，跳过真实 API 测试", apiKey.isNotBlank())
        val model = props["AI_MODEL_GENERAL"].orEmpty()
        assumeTrue("无 AI_MODEL_GENERAL，跳过真实 API 测试", model.isNotBlank())

        val config = AiConfig(protocol = protocol, baseUrl = baseUrl, apiKey = apiKey)
        val provider = AiProviderFactory.create(config, AiProviderFactory.defaultClient(60))
        val chunks = provider.chat(
            listOf(AiMessage.of(AiRole.USER, "用三个字回答：1+1=?")),
            model,
        ).toList()
        val text = chunks.joinToString("")

        println("[RealApiTest] 文本回复（$protocol / $model）：$text")
        assertTrue("真实回复不应为空", text.isNotBlank())
    }

    @Test(timeout = 180_000)
    fun `图片识别真实调用`() = runBlocking {
        assumeTrue("无 AI_API_KEY，跳过真实 API 测试", apiKey.isNotBlank())
        val model = props["AI_MODEL_VISION"].orEmpty()
        assumeTrue("无 AI_MODEL_VISION（.env 未配置视觉模型），跳过视觉真实测试", model.isNotBlank())

        val config = AiConfig(protocol = protocol, baseUrl = baseUrl, apiKey = apiKey)
        val provider = AiProviderFactory.create(config, AiProviderFactory.defaultClient(60))
        val message = AiMessage(
            AiRole.USER,
            listOf(
                AiContent.Text("图片里的文字是什么？只回答文字本身"),
                AiContent.Image("image/png", renderTextPngBase64("FoldReader AI")),
            ),
        )
        val chunks = provider.chat(listOf(message), model).toList()
        val text = chunks.joinToString("")

        println("[RealApiTest] 视觉回复（$protocol / $model）：$text")
        assertTrue("视觉回复不应为空", text.isNotBlank())
    }

    /** 白底黑字渲染一行文字，输出 PNG 的 Base64（不带 data: 前缀）。 */
    private fun renderTextPngBase64(text: String): String {
        val image = BufferedImage(480, 120, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        try {
            g.color = Color.WHITE
            g.fillRect(0, 0, image.width, image.height)
            g.color = Color.BLACK
            g.font = Font(Font.SANS_SERIF, Font.PLAIN, 48)
            g.drawString(text, 20, 75)
        } finally {
            g.dispose()
        }
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        return Base64.getEncoder().encodeToString(out.toByteArray())
    }
}

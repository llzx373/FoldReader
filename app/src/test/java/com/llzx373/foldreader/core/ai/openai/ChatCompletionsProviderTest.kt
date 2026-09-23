package com.llzx373.foldreader.core.ai.openai

import com.llzx373.foldreader.core.ai.AiConfig
import com.llzx373.foldreader.core.ai.AiContent
import com.llzx373.foldreader.core.ai.AiException
import com.llzx373.foldreader.core.ai.AiMessage
import com.llzx373.foldreader.core.ai.AiProtocol
import com.llzx373.foldreader.core.ai.AiRole
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class ChatCompletionsProviderTest {

    private lateinit var server: MockWebServer
    private lateinit var provider: ChatCompletionsProvider

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val config = AiConfig(
            protocol = AiProtocol.OPENAI_CHAT,
            baseUrl = server.url("/").toString(),
            apiKey = "test-key",
        )
        provider = ChatCompletionsProvider(config, OkHttpClient())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun sseBody(vararg deltas: String): String = buildString {
        for (d in deltas) {
            append("data: {\"choices\":[{\"delta\":{\"content\":\"$d\"}}]}\n\n")
        }
        append("data: [DONE]\n\n")
    }

    private fun streamResponse(body: String) = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(body)

    private suspend fun collectBody(vararg deltas: String): String {
        server.enqueue(streamResponse(sseBody(*deltas)))
        return provider.chat(listOf(AiMessage.of(AiRole.USER, "hi")), "test-model")
            .toList().joinToString("")
    }

    @Test
    fun `正常增量流拼接为完整文本`() = runTest {
        val text = collectBody("你好", "，世界", "！")

        assertEquals("你好，世界！", text)
    }

    @Test
    fun `DONE 之后的内容被忽略`() = runTest {
        server.enqueue(streamResponse(sseBody("前") + "data: {\"choices\":[{\"delta\":{\"content\":\"后\"}}]}\n\n"))

        val text = provider.chat(listOf(AiMessage.of(AiRole.USER, "hi")), "m").toList().joinToString("")

        assertEquals("前", text)
    }

    @Test
    fun `慢速分块传输结果一致`() = runTest {
        val body = sseBody("你好", "，世界", "！")
        server.enqueue(
            streamResponse(body).throttleBody(3, 20, TimeUnit.MILLISECONDS),
        )

        val text = provider.chat(listOf(AiMessage.of(AiRole.USER, "hi")), "m").toList().joinToString("")

        assertEquals("你好，世界！", text)
    }

    @Test
    fun `中途断流抛出网络类异常`() = runTest {
        server.enqueue(
            streamResponse(sseBody("半截"))
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
        )

        try {
            provider.chat(listOf(AiMessage.of(AiRole.USER, "hi")), "m").toList()
            fail("断流应抛出 AiException")
        } catch (e: AiException) {
            assertTrue(
                "断流异常应为 NETWORK/TIMEOUT，实际 ${e.kind}",
                e.kind == AiException.Kind.NETWORK || e.kind == AiException.Kind.TIMEOUT,
            )
        }
    }

    @Test
    fun `429 映射为限流异常`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(429)
                .setBody("{\"error\":{\"message\":\"rate limited\"}}"),
        )

        try {
            provider.chat(listOf(AiMessage.of(AiRole.USER, "hi")), "m").toList()
            fail("429 应抛出 AiException")
        } catch (e: AiException) {
            assertEquals(AiException.Kind.RATE_LIMITED, e.kind)
        }
    }

    @Test
    fun `401 映射为认证异常`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(401)
                .setBody("{\"error\":{\"message\":\"invalid key\"}}"),
        )

        try {
            provider.chat(listOf(AiMessage.of(AiRole.USER, "hi")), "m").toList()
            fail("401 应抛出 AiException")
        } catch (e: AiException) {
            assertEquals(AiException.Kind.AUTH, e.kind)
        }
    }

    @Test
    fun `500 映射为服务方异常`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(500)
                .setBody("{\"error\":{\"message\":\"boom\"}}"),
        )

        try {
            provider.chat(listOf(AiMessage.of(AiRole.USER, "hi")), "m").toList()
            fail("500 应抛出 AiException")
        } catch (e: AiException) {
            assertEquals(AiException.Kind.SERVER, e.kind)
        }
    }

    @Test
    fun `请求方法路径与认证头正确`() = runTest {
        collectBody("ok")

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/chat/completions", request.path)
        assertEquals("Bearer test-key", request.getHeader("Authorization"))
    }

    @Test
    fun `纯文本消息 content 为字符串形态`() = runTest {
        server.enqueue(streamResponse(sseBody("ok")))
        provider.chat(
            listOf(
                AiMessage.of(AiRole.SYSTEM, "你是助手"),
                AiMessage.of(AiRole.USER, "你好"),
            ),
            "test-model",
        ).toList()

        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("test-model", body.getString("model"))
        assertTrue(body.getBoolean("stream"))
        val messages = body.getJSONArray("messages")
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals("你是助手", messages.getJSONObject(0).getString("content"))
        assertEquals("user", messages.getJSONObject(1).getString("role"))
        assertEquals("你好", messages.getJSONObject(1).getString("content"))
    }

    @Test
    fun `含图片消息使用块数组与 data URL`() = runTest {
        server.enqueue(streamResponse(sseBody("ok")))
        provider.chat(
            listOf(
                AiMessage(
                    AiRole.USER,
                    listOf(
                        AiContent.Text("看图"),
                        AiContent.Image("image/png", "QUJD"),
                    ),
                ),
            ),
            "test-model",
        ).toList()

        val body = JSONObject(server.takeRequest().body.readUtf8())
        val content = body.getJSONArray("messages").getJSONObject(0).getJSONArray("content")
        assertEquals("text", content.getJSONObject(0).getString("type"))
        assertEquals("看图", content.getJSONObject(0).getString("text"))
        val imageBlock = content.getJSONObject(1)
        assertEquals("image_url", imageBlock.getString("type"))
        assertEquals(
            "data:image/png;base64,QUJD",
            imageBlock.getJSONObject("image_url").getString("url"),
        )
    }
}

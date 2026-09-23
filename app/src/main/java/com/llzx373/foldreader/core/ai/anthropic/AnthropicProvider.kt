package com.llzx373.foldreader.core.ai.anthropic

import com.llzx373.foldreader.core.ai.AiConfig
import com.llzx373.foldreader.core.ai.AiContent
import com.llzx373.foldreader.core.ai.AiException
import com.llzx373.foldreader.core.ai.AiMessage
import com.llzx373.foldreader.core.ai.AiProvider
import com.llzx373.foldreader.core.ai.AiRole
import com.llzx373.foldreader.core.ai.httpError
import com.llzx373.foldreader.core.ai.sse.readSseEvents
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Anthropic Messages 协议（`POST {base}/v1/messages`）。
 *
 * `max_tokens` 为必填，固定 4096；system 消息合并为顶层 `system` 字符串。
 */
class AnthropicProvider(
    private val config: AiConfig,
    private val client: OkHttpClient,
) : AiProvider {

    private val json = Json { ignoreUnknownKeys = true }

    override fun chat(messages: List<AiMessage>, model: String): Flow<String> = flow {
        val requestBody = buildJsonObject {
            put("model", model)
            put("max_tokens", 4096)
            put("stream", true)
            val system = messages.filter { it.role == AiRole.SYSTEM }
                .flatMap { it.content.filterIsInstance<AiContent.Text>() }
                .joinToString("\n") { it.text }
            if (system.isNotEmpty()) put("system", system)
            putJsonArray("messages") {
                messages.filter { it.role != AiRole.SYSTEM }.forEach { add(messageJson(it)) }
            }
        }.toString().toRequestBody(JSON_MEDIA)

        val request = Request.Builder()
            .url("${config.normalizedBaseUrl}/v1/messages")
            .header("x-api-key", config.apiKey)
            .header("anthropic-version", "2023-06-01")
            .post(requestBody)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw httpError(response.code, response.body?.string())
                val source = response.body!!.source()
                for (event in source.readSseEvents()) {
                    currentCoroutineContext().ensureActive()
                    when (event.event) {
                        "content_block_delta" -> {
                            val text = extractTextDelta(event.data)
                            if (!text.isNullOrEmpty()) emit(text)
                        }
                        "message_stop" -> break
                        "error" -> throw AiException(
                            AiException.Kind.PROTOCOL,
                            "服务方出错：${extractError(event.data) ?: event.data.take(200)}",
                        )
                    }
                }
            }
        } catch (e: SocketTimeoutException) {
            throw AiException(AiException.Kind.TIMEOUT, "请求超时，请检查网络或调大超时时间", e)
        } catch (e: InterruptedIOException) {
            throw AiException(AiException.Kind.TIMEOUT, "请求被中断", e)
        } catch (e: IOException) {
            throw AiException(AiException.Kind.NETWORK, "网络错误：${e.message}", e)
        }
    }.flowOn(Dispatchers.IO)

    /** content 块为 `text` / `image`（base64 source）。 */
    private fun messageJson(msg: AiMessage) = buildJsonObject {
        put("role", msg.role.name.lowercase())
        putJsonArray("content") {
            for (c in msg.content) when (c) {
                is AiContent.Text -> addJsonObject {
                    put("type", "text")
                    put("text", c.text)
                }
                is AiContent.Image -> addJsonObject {
                    put("type", "image")
                    put("source", buildJsonObject {
                        put("type", "base64")
                        put("media_type", c.mimeType)
                        put("data", c.base64)
                    })
                }
            }
        }
    }

    /** 只取 `delta.type == "text_delta"` 的 `delta.text`。 */
    private fun extractTextDelta(data: String): String? = runCatching {
        val delta = json.parseToJsonElement(data).jsonObject["delta"]?.jsonObject ?: return null
        if (delta["type"]?.jsonPrimitive?.contentOrNull != "text_delta") return null
        delta["text"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()

    /** error 事件的文案在 `error.message`。 */
    private fun extractError(data: String): String? = runCatching {
        json.parseToJsonElement(data).jsonObject["error"]?.jsonObject
            ?.get("message")?.jsonPrimitive?.contentOrNull
    }.getOrNull()

    private companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}

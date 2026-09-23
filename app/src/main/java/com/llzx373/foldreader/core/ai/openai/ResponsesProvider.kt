package com.llzx373.foldreader.core.ai.openai

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
 * OpenAI Responses 协议（`POST /responses`）。
 *
 * system 消息合并进顶层 `instructions`；SSE 按 `event:` 字段分发，
 * 只认 `response.output_text.delta`。
 */
class ResponsesProvider(
    private val config: AiConfig,
    private val client: OkHttpClient,
) : AiProvider {

    private val json = Json { ignoreUnknownKeys = true }

    override fun chat(messages: List<AiMessage>, model: String): Flow<String> = flow {
        val requestBody = buildJsonObject {
            put("model", model)
            put("stream", true)
            val instructions = messages.filter { it.role == AiRole.SYSTEM }
                .flatMap { it.content.filterIsInstance<AiContent.Text>() }
                .joinToString("\n") { it.text }
            if (instructions.isNotEmpty()) put("instructions", instructions)
            putJsonArray("input") {
                messages.filter { it.role != AiRole.SYSTEM }.forEach { add(inputItemJson(it)) }
            }
        }.toString().toRequestBody(JSON_MEDIA)

        val request = Request.Builder()
            .url("${config.normalizedBaseUrl}/responses")
            .header("Authorization", "Bearer ${config.apiKey}")
            .post(requestBody)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw httpError(response.code, response.body?.string())
                val source = response.body!!.source()
                for (event in source.readSseEvents()) {
                    currentCoroutineContext().ensureActive()
                    when (event.event) {
                        "response.output_text.delta" -> {
                            val delta = extractString(event.data, "delta")
                            if (!delta.isNullOrEmpty()) emit(delta)
                        }
                        "response.completed" -> break
                        "response.failed", "response.incomplete" ->
                            throw AiException(
                                AiException.Kind.PROTOCOL,
                                "生成未完成：${extractError(event.data) ?: event.data.take(200)}",
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

    /** user/assistant 的 content 块统一为 `input_text` / `input_image`。 */
    private fun inputItemJson(msg: AiMessage) = buildJsonObject {
        put("role", msg.role.name.lowercase())
        putJsonArray("content") {
            for (c in msg.content) when (c) {
                is AiContent.Text -> addJsonObject {
                    put("type", "input_text")
                    put("text", c.text)
                }
                is AiContent.Image -> addJsonObject {
                    put("type", "input_image")
                    put("image_url", "data:${c.mimeType};base64,${c.base64}")
                }
            }
        }
    }

    private fun extractString(data: String, key: String): String? = runCatching {
        json.parseToJsonElement(data).jsonObject[key]?.jsonPrimitive?.contentOrNull
    }.getOrNull()

    /** failed/incomplete 事件的错误文案在 `response.error.message`。 */
    private fun extractError(data: String): String? = runCatching {
        json.parseToJsonElement(data).jsonObject["response"]?.jsonObject
            ?.get("error")?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
    }.getOrNull()

    private companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}

package com.llzx373.foldreader.core.ai.openai

import com.llzx373.foldreader.core.ai.AiConfig
import com.llzx373.foldreader.core.ai.AiContent
import com.llzx373.foldreader.core.ai.AiException
import com.llzx373.foldreader.core.ai.AiMessage
import com.llzx373.foldreader.core.ai.AiProvider
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * OpenAI Chat Completions 协议（`POST /chat/completions`），
 * 同时兼容 DeepSeek 等同协议服务。
 */
class ChatCompletionsProvider(
    private val config: AiConfig,
    private val client: OkHttpClient,
) : AiProvider {

    private val json = Json { ignoreUnknownKeys = true }

    override fun chat(messages: List<AiMessage>, model: String): Flow<String> = flow {
        val requestBody = buildJsonObject {
            put("model", model)
            put("stream", true)
            putJsonArray("messages") {
                messages.forEach { add(messageJson(it)) }
            }
        }.toString().toRequestBody(JSON_MEDIA)

        val request = Request.Builder()
            .url("${config.normalizedBaseUrl}/chat/completions")
            .header("Authorization", "Bearer ${config.apiKey}")
            .post(requestBody)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw httpError(response.code, response.body?.string())
                val source = response.body!!.source()
                for (event in source.readSseEvents()) {
                    currentCoroutineContext().ensureActive()
                    if (event.data == "[DONE]") break
                    val delta = extractDelta(event.data)
                    if (!delta.isNullOrEmpty()) emit(delta)
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

    /**
     * 序列化规则：只有文本时 content 用纯字符串（最大兼容）；
     * 含图片时用块数组 `text` / `image_url`（data: 前缀在此拼接）。
     */
    private fun messageJson(msg: AiMessage) = buildJsonObject {
        put("role", msg.role.name.lowercase())
        val texts = msg.content.filterIsInstance<AiContent.Text>()
        val images = msg.content.filterIsInstance<AiContent.Image>()
        if (images.isEmpty()) {
            put("content", texts.joinToString("") { it.text })
        } else {
            putJsonArray("content") {
                for (t in texts) addJsonObject {
                    put("type", "text")
                    put("text", t.text)
                }
                for (img in images) addJsonObject {
                    put("type", "image_url")
                    put("image_url", buildJsonObject {
                        put("url", "data:${img.mimeType};base64,${img.base64}")
                    })
                }
            }
        }
    }

    /** 只取 `choices[0].delta.content`；DeepSeek 推理模型的 `reasoning_content` 直接忽略。 */
    private fun extractDelta(data: String): String? = runCatching {
        val choices = json.parseToJsonElement(data).jsonObject["choices"]?.jsonArray ?: return null
        val delta = choices.firstOrNull()?.jsonObject?.get("delta")?.jsonObject ?: return null
        delta["content"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()

    private companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}

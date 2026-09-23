package com.llzx373.foldreader.core.ai

import com.llzx373.foldreader.core.ai.anthropic.AnthropicProvider
import com.llzx373.foldreader.core.ai.openai.ChatCompletionsProvider
import com.llzx373.foldreader.core.ai.openai.ResponsesProvider
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/** 按协议创建 Provider；OkHttpClient 由调用方共享传入。 */
object AiProviderFactory {

    fun create(config: AiConfig, client: OkHttpClient): AiProvider = when (config.protocol) {
        AiProtocol.OPENAI_CHAT -> ChatCompletionsProvider(config, client)
        AiProtocol.OPENAI_RESPONSES -> ResponsesProvider(config, client)
        AiProtocol.ANTHROPIC -> AnthropicProvider(config, client)
    }

    /** 默认客户端：连接 15s，读/写取配置的超时（SSE 长连接主要靠读超时兜底）。 */
    fun defaultClient(timeoutSeconds: Long): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .writeTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .build()
}

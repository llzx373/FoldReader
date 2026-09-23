package com.llzx373.foldreader.core.ai

/** AI 服务商协议族。 */
enum class AiProtocol { OPENAI_CHAT, OPENAI_RESPONSES, ANTHROPIC }

/** 翻译/输出目标语言。 */
enum class AiTargetLang { ZH_HANS, ZH_HANT, EN, JA }

/**
 * AI 服务连接配置。
 *
 * [baseUrl] 为 scheme + host（可带路径前缀，如 `https://api.deepseek.com/v1`），
 * 使用时经 [normalizedBaseUrl] 去掉尾部 '/' 再拼端点。
 */
data class AiConfig(
    val protocol: AiProtocol,
    val baseUrl: String,
    val apiKey: String,
    val timeoutSeconds: Long = 60,
) {
    val normalizedBaseUrl: String get() = baseUrl.trimEnd('/')
}

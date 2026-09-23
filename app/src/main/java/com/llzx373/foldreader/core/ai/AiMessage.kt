package com.llzx373.foldreader.core.ai

/** 对话角色。 */
enum class AiRole { SYSTEM, USER, ASSISTANT }

/**
 * 消息内容块。
 *
 * 注意：图片只允许出现在 USER 消息中（DeepSeek / OpenAI / Anthropic 三家的限制一致），
 * Provider 不对此做额外校验，调用方负责。
 */
sealed interface AiContent {

    data class Text(val text: String) : AiContent

    /** [base64] 不带 `data:` 前缀，前缀由各 Provider 按协议拼接。 */
    data class Image(val mimeType: String, val base64: String) : AiContent
}

/** 一条对话消息。 */
data class AiMessage(val role: AiRole, val content: List<AiContent>) {
    companion object {
        /** 纯文本消息的便捷构造。 */
        fun of(role: AiRole, text: String) = AiMessage(role, listOf(AiContent.Text(text)))
    }
}

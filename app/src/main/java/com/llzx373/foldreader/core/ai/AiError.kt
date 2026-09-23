package com.llzx373.foldreader.core.ai

/** AI 调用失败的统一异常。 */
class AiException(val kind: Kind, message: String, cause: Throwable? = null) : Exception(message, cause) {

    enum class Kind { NETWORK, TIMEOUT, AUTH, RATE_LIMITED, QUOTA, SERVER, PROTOCOL, INTERRUPTED }
}

/**
 * 把非 2xx 响应映射为可读异常；文案面向用户、不携带 key 等敏感信息。
 */
internal fun httpError(code: Int, body: String?): AiException {
    val detail = body?.take(200)?.let { "（$it）" } ?: ""
    return when {
        code == 401 || code == 403 ->
            AiException(AiException.Kind.AUTH, "认证失败，请检查 API key$detail")
        code == 429 ->
            AiException(AiException.Kind.RATE_LIMITED, "请求过于频繁，请稍后再试$detail")
        code == 402 ->
            AiException(AiException.Kind.QUOTA, "账户余额不足或额度已用尽$detail")
        code in 500..599 ->
            AiException(AiException.Kind.SERVER, "服务方出错（HTTP $code），请稍后再试$detail")
        else ->
            AiException(AiException.Kind.PROTOCOL, "服务方返回了未预期的响应（HTTP $code）$detail")
    }
}

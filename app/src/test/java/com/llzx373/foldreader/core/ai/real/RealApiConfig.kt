package com.llzx373.foldreader.core.ai.real

import com.llzx373.foldreader.core.ai.AiProtocol
import java.io.File

/**
 * 真实 API 冒烟测试共用的配置来源：环境变量优先，否则读仓库根 .env
 * （单测工作目录是 app/ 模块目录）。无 AI_API_KEY 的用例整组 assume 跳过。
 */
internal object RealApiConfig {

    val props: Map<String, String> by lazy { loadConfig() }

    val apiKey: String get() = props["AI_API_KEY"].orEmpty()
    val baseUrl: String get() = props["AI_BASE_URL"].orEmpty()
    val protocol: AiProtocol
        get() = when (props["AI_PROTOCOL"].orEmpty().ifBlank { "openai-chat" }) {
            "openai-responses" -> AiProtocol.OPENAI_RESPONSES
            "anthropic" -> AiProtocol.ANTHROPIC
            else -> AiProtocol.OPENAI_CHAT
        }

    private fun loadConfig(): Map<String, String> {
        val file = sequenceOf(
            File("../.env"),
            File("").absoluteFile.parentFile?.resolve(".env"),
        ).filterNotNull().firstOrNull { it.isFile }

        val fromFile = file?.readLines().orEmpty()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && '=' in it }
            .associate { line ->
                val key = line.substringBefore('=').trim()
                key to line.substringAfter('=').trim()
            }
        val keys = setOf(
            "AI_PROTOCOL", "AI_BASE_URL", "AI_API_KEY",
            "AI_MODEL_GENERAL", "AI_MODEL_TRANSLATION", "AI_MODEL_VISION",
        )
        return keys.associateWith { key ->
            System.getenv(key)?.takeIf { it.isNotBlank() } ?: fromFile[key].orEmpty()
        }.also {
            if (file != null) println("[RealApiConfig] 配置文件：${file.absoluteFile.normalize()}")
        }
    }
}

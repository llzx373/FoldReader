package com.llzx373.foldreader.core.ai.gate

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * AI 出站内容台账：每次向模型发送内容前记一条（时间戳 / 功能 / 范围 / 估算 token 数），
 * 供用户在设置里审查「都发过什么出去」。
 *
 * 纯 JVM（零 android import），只依赖 java.io 与 kotlinx.serialization，
 * 因此可以直接跑单元测试。持久化为整体重写的 JSON 数组文件，上限 [MAX_RECORDS] 条，
 * 超出丢最旧；文件不存在或损坏一律按空列表处理，绝不抛异常给调用方。
 */
class AiContentGate(private val historyFile: File) {

    @Serializable
    data class OutboundRecord(
        val timestamp: Long,
        val feature: String,
        val scope: String,
        val estimatedTokens: Int,
    )

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    @Synchronized
    fun record(
        feature: String,
        scope: String,
        estimatedTokens: Int,
        timestamp: Long = System.currentTimeMillis(),
    ) {
        val records = history() + OutboundRecord(timestamp, feature, scope, estimatedTokens)
        val trimmed = if (records.size > MAX_RECORDS) records.takeLast(MAX_RECORDS) else records
        historyFile.parentFile?.mkdirs()
        historyFile.writeText(json.encodeToString(trimmed))
    }

    @Synchronized
    fun history(): List<OutboundRecord> {
        if (!historyFile.isFile) return emptyList()
        return try {
            json.decodeFromString<List<OutboundRecord>>(historyFile.readText())
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun clear() {
        historyFile.delete()
    }

    private companion object {
        const val MAX_RECORDS = 500
    }
}

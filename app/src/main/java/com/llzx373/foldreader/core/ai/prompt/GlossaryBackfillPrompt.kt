package com.llzx373.foldreader.core.ai.prompt

import com.llzx373.foldreader.core.ai.AiMessage
import com.llzx373.foldreader.core.ai.AiRole
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class BackfillTerm(val source: String = "", val target: String = "")

@Serializable
private data class BackfillResponse(val terms: List<BackfillTerm> = emptyList())

/**
 * M20 术语回填提示词：从「原文样本 + 译文样本」里抽专名对照，产出术语候选。
 *
 * 触发点：一本书前 3 个翻译单位完成时各触发一次（`TranslateEngine`），
 * 候选以 origin=auto / confirmed=0 落术语表，经术语表 UI 确认后才参与注入（R6）。
 * 纯 JVM、不碰 Android；解析失败返回空表（调用方静默跳过，回填只是增强）。
 */
object GlossaryBackfillPrompt {

    /** 系统提示词全文。改动即行为变更：进 CHANGELOG，输出格式变了同步改解析与单测。 */
    val SYSTEM_PROMPT =
        """
        你是术语提取器。用户会给你一段书籍原文和它的译文（同一内容的对照样本）。

        你的任务：找出译文中采用了固定译法的专有名词（人名、地名、组织名、作品特有的术语），给出原文到译文的对照。

        要求：
        1. 只提取在样本中确实出现、且译法固定的词；普通词汇、临时性表达不要提取；
        2. 只输出如下 JSON，不要输出任何其他文字、解释或 Markdown 代码围栏：
        {"terms": [{"source": "原文词", "target": "译文词"}]}
        3. 找不到符合条件的词时输出 {"terms": []}；
        4. 至多输出 20 条，按在原文中出现的先后顺序排列。
        """.trimIndent()

    /** SYSTEM 给任务与输出契约，USER 附对照样本（原文段 + 译文段）。 */
    fun buildMessages(sourceSample: String, translatedSample: String): List<AiMessage> {
        val user = buildString {
            append("【原文】\n").append(sourceSample)
            append("\n\n【译文】\n").append(translatedSample)
        }
        return listOf(
            AiMessage.of(AiRole.SYSTEM, SYSTEM_PROMPT),
            AiMessage.of(AiRole.USER, user),
        )
    }

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 解析模型输出的术语数组。
     *
     * 容忍散文包裹与 ```json 代码围栏：截取第一个 `{` 到最后一个 `}` 再解析。
     * JSON 畸形 / terms 缺失 → 空表；空 source 或空 target 的条目丢弃。
     */
    fun parseTerms(raw: String): List<Pair<String, String>> {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end < start) return emptyList()

        val parsed = runCatching {
            json.decodeFromString<BackfillResponse>(raw.substring(start, end + 1))
        }.getOrNull() ?: return emptyList()

        return parsed.terms
            .map { it.source.trim() to it.target.trim() }
            .filter { it.first.isNotEmpty() && it.second.isNotEmpty() }
    }
}

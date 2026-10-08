package com.llzx373.foldreader.core.ai.prompt

import com.llzx373.foldreader.core.ai.AiMessage
import com.llzx373.foldreader.core.ai.AiRole
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * AI 校对发现的一处问题：错别字 / 病句。
 *
 * [offset] / [length] 是**送校单位文本内**的字符区间（半开），[original] 是该区间的原文片段，
 * [suggestion] 是建议改成的内容。校对「只标不改」：这里只产出标注数据，
 * 是否真的改由用户逐条确认后走 M16 清洗配方链路决定。
 */
data class ProofreadIssue(
    val offset: Int,
    val length: Int,
    val original: String,
    val suggestion: String,
    val type: Type,
) {
    enum class Type(val label: String) {
        TYPO("错别字"),
        GRAMMAR("病句"),
        OTHER("其他"),
    }
}

@Serializable
private data class ProofreadIssueDto(
    val offset: Int,
    val length: Int,
    val original: String,
    val suggestion: String,
    val type: String = "",
)

/**
 * M30「AI 校对」的提示词与响应解析。
 *
 * 输出契约：JSON 数组，元素 `{"offset":n,"length":n,"original":"...","suggestion":"...","type":"typo"|"grammar"}`，
 * offset/length 相对 USER 消息里附上的单位正文（USER 只放正文原文，不带标题前缀，偏移才不会错位）。
 *
 * 解析校验（[parseIssues]）：区间越界 / 原文与区间不符（允许就近重定位一次）/ 空建议 /
 * 原文与建议相同 → 逐条丢弃；整段 JSON 畸形 → null（调用方重试）。宁缺毋滥，
 * 拿不准的条目不进结果——批注标错了比少标更伤信任。
 *
 * 纯 JVM、不碰 Android。
 */
object ProofreadPrompt {

    /** 单次送校最多接受的条目数（超出截断，防模型刷满）。 */
    const val MAX_ISSUES = 50

    /** 原文片段与区间不符时，允许在区间前 [RELOCATE_LOOKBACK] 字符起就近重定位。 */
    private const val RELOCATE_LOOKBACK = 64

    /** 系统提示词全文。改动即行为变更：进 CHANGELOG，输出格式变了同步改解析与单测。 */
    val SYSTEM_PROMPT =
        """
        你是中文/外文书籍的文字校对员。用户会给你一段电子书正文。

        你的任务：找出其中明确的错别字与病句。

        要求：
        1. 只报告确定有误的，宁缺毋滥；拿不准的、风格偏好类的不要报；
        2. offset 与 length 是问题片段在你收到的正文里的字符位置（从 0 开始，length 为字符数），original 必须与该区间原文逐字一致；
        3. suggestion 是建议替换 original 的内容（只给替换片段，不要整句改写）；suggestion 的语言与原文一致；
        4. type 只能是 "typo"（错别字/用字错误）或 "grammar"（病句/语法问题）；
        5. 一次最多报 20 条，按严重程度优先；没有问题就输出 []。

        只输出如下 JSON 数组，不要输出任何其他文字、解释或 Markdown 代码围栏：
        [{"offset": 0, "length": 2, "original": "原文片段", "suggestion": "建议改为", "type": "typo"}, ...]
        """.trimIndent()

    /** SYSTEM 给任务与输出契约，USER 原样附上待校正文（不加任何前缀，保证偏移对齐）。 */
    fun buildMessages(unitText: String): List<AiMessage> = listOf(
        AiMessage.of(AiRole.SYSTEM, SYSTEM_PROMPT),
        AiMessage.of(AiRole.USER, unitText),
    )

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 解析模型输出的校对结果。
     *
     * 容忍散文包裹与 ```json 代码围栏：截取第一个 `[` 到最后一个 `]` 再解析。
     * JSON 畸形 → null（调用方据此整体重试）；单条校验失败只丢该条。
     * 返回按 offset 升序、按 offset 去重的结果，最多 [MAX_ISSUES] 条。
     */
    fun parseIssues(raw: String, unitText: String): List<ProofreadIssue>? {
        val start = raw.indexOf('[')
        val end = raw.lastIndexOf(']')
        if (start < 0 || end < start) return null

        val parsed = runCatching {
            json.decodeFromString<List<ProofreadIssueDto>>(raw.substring(start, end + 1))
        }.getOrNull() ?: return null

        val seen = HashSet<Int>()
        return parsed.mapNotNull { dto -> toIssue(dto, unitText) }
            .filter { seen.add(it.offset) }
            .sortedBy { it.offset }
            .take(MAX_ISSUES)
    }

    private fun toIssue(dto: ProofreadIssueDto, unitText: String): ProofreadIssue? {
        val original = dto.original
        val suggestion = dto.suggestion.trim()
        if (original.isBlank() || suggestion.isEmpty() || original == suggestion) return null
        if (dto.length <= 0 || dto.offset < 0) return null

        // 区间与原文逐字一致才直接采用；否则给一次就近重定位的机会（模型数偏移经常数错）
        var offset = dto.offset
        var length = dto.length
        val matches = offset + length <= unitText.length &&
            unitText.substring(offset, offset + length) == original
        if (!matches) {
            val from = (dto.offset - RELOCATE_LOOKBACK).coerceAtLeast(0)
            val found = unitText.indexOf(original, startIndex = from)
            if (found < 0) return null
            offset = found
            length = original.length
        }
        return ProofreadIssue(
            offset = offset,
            length = length,
            original = original,
            suggestion = suggestion,
            type = when (dto.type) {
                "typo" -> ProofreadIssue.Type.TYPO
                "grammar" -> ProofreadIssue.Type.GRAMMAR
                else -> ProofreadIssue.Type.OTHER
            },
        )
    }
}

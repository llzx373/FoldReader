package com.llzx373.foldreader.core.ai.prompt

import com.llzx373.foldreader.core.ai.AiMessage
import com.llzx373.foldreader.core.ai.AiRole
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** AI 返回的一条章节标题正则候选。 */
@Serializable
data class ChapterRuleCandidate(val regex: String = "", val explanation: String = "")

@Serializable
private data class ChapterRuleResponse(val candidates: List<ChapterRuleCandidate> = emptyList())

/**
 * 「AI 章节规则生成」的提示词与响应解析。
 *
 * 纯 JVM、不碰 Android；模型输出契约由 [parseChapterRuleCandidates] 的单测锁定。
 * 解析失败（JSON 畸形、候选非法）即丢弃，绝不抛异常——调用方按空列表走降级路径。
 */
object ChapterRulePrompt {
    const val MAX_CANDIDATES = 3

    /** 系统提示词全文。改动即行为变更：进 CHANGELOG，输出格式变了同步改解析与单测。 */
    val SYSTEM_PROMPT =
        """
        你是电子书 TXT 章节标题识别助手。用户会给出一批从同一本书中采样出的疑似章节标题行（每行一条）。

        你的任务：根据这些样本归纳 1~$MAX_CANDIDATES 条正则表达式，用于逐行匹配该书的章节标题行。

        要求：
        1. 使用 Kotlin/Java Regex 语法；
        2. 每条正则用于对单行文本做整行匹配，应能捕获或覆盖标题全文（建议用 ^ 与 ${'$'} 锚定）；
        3. 数字部分兼容全角数字（０-９）、半角数字（0-9）与中文数字（零一二三四五六七八九十百千万两），除非样本明显只需要其中一种；
        4. 宁缺毋滥：拿不准的模式不要给，以免误匹配正文行；
        5. 每条候选附一句中文说明（explanation），解释它匹配什么样的标题。

        只输出如下 JSON，不要输出任何其他文字、解释或 Markdown 代码围栏：
        {"candidates": [{"regex": "...", "explanation": "..."}]}
        """.trimIndent()

    /** SYSTEM 给任务与输出契约，USER 原样附上采样行（每行一条）。 */
    fun buildMessages(sampleLines: List<String>): List<AiMessage> = listOf(
        AiMessage.of(AiRole.SYSTEM, SYSTEM_PROMPT),
        AiMessage.of(AiRole.USER, "疑似章节标题行采样：\n" + sampleLines.joinToString("\n")),
    )

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 解析模型输出的章节规则候选。
     *
     * 容忍散文包裹与 ```json 代码围栏：截取第一个 `{` 到最后一个 `}` 再解析。
     * JSON 畸形、candidates 缺失/为空 → 空列表；regex 空白或编译失败 → 丢弃该候选；
     * 候选数截断到 [MAX_CANDIDATES]。
     */
    fun parseChapterRuleCandidates(raw: String): List<ChapterRuleCandidate> {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end < start) return emptyList()

        val parsed = runCatching {
            json.decodeFromString<ChapterRuleResponse>(raw.substring(start, end + 1))
        }.getOrNull() ?: return emptyList()

        return parsed.candidates
            .filter { it.regex.isNotBlank() && runCatching { Regex(it.regex) }.isSuccess }
            .take(MAX_CANDIDATES)
    }
}

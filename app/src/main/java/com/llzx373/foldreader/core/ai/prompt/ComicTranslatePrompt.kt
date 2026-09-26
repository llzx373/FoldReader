package com.llzx373.foldreader.core.ai.prompt

import com.llzx373.foldreader.core.ai.AiMessage
import com.llzx373.foldreader.core.ai.AiRole
import com.llzx373.foldreader.core.ai.AiTargetLang
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class TranslationsResponse(val translations: List<String>)

/**
 * M22「漫画气泡翻译」的提示词：逐气泡结构化输出契约。
 *
 * 模型按编号气泡逐一翻译、以 `{"translations":[...]}` 返回，解析校验（数量必须与气泡数一致）
 * 失败即该页重试——不做事后对齐（同 M19 段落契约口径）。
 * 流式渲染用 `PartialJsonArray.extractCompleteStrings` 提取已闭合元素——气泡译文逐个出现。
 *
 * 纯 JVM、不碰 Android；目标语言经占位符 [TARGET_LANG_PLACEHOLDER] 在 [buildMessages] 时注入。
 */
object ComicTranslatePrompt {

    /** 系统提示词中的目标语言占位符。 */
    const val TARGET_LANG_PLACEHOLDER = "{{目标语言}}"

    /** 系统提示词全文。改动即行为变更：进 CHANGELOG，输出格式变了同步改解析与单测。 */
    val SYSTEM_PROMPT =
        """
        你是漫画翻译器。用户会给你一组编号的对白气泡原文（每个气泡以「<N>」开头单独成行，N 从 1 开始连续编号），它们来自同一页漫画，可能包含对白、旁白与拟声词。

        你的任务：自动检测原文语言，把每个气泡翻译成$TARGET_LANG_PLACEHOLDER。

        要求：
        1. 逐气泡翻译，不得合并、拆分或遗漏，译文与输入气泡一一对应；
        2. 只输出如下 JSON，不要输出任何其他文字、解释或 Markdown 代码围栏：
        {"translations": ["第一个气泡译文", "第二个气泡译文"]}
        3. translations 数组的元素数量必须与输入气泡数量完全一致，顺序一一对应；
        4. 译文要放进漫画气泡里：在保持原意的前提下尽量简短口语化，不逐字硬译；
        5. 拟声词翻译为目标语言习惯的拟声词（无法翻译时给简短描述）；
        6. 人名、地名、专有名词按目标语言惯例翻译并全卷保持一致；
        7. 原文语言与目标语言相同时，原样返回原文。
        """.trimIndent()

    /**
     * SYSTEM 给任务、目标语言与输出契约，USER 附上编号气泡。
     *
     * [glossary] 非空时在 SYSTEM 末尾追加「术语对照」节（R6 术语注入：书>系列>全局，
     * 合并在调用方完成）；[systemOverride] 非空时整体替换内置系统提示词——
     * 「当次临时提示词」，仅当次生效、不写回内置模板（R11）。
     */
    fun buildMessages(
        bubbleTexts: List<String>,
        targetLang: AiTargetLang,
        glossary: List<Pair<String, String>> = emptyList(),
        systemOverride: String? = null,
    ): List<AiMessage> {
        val system = systemOverride ?: buildString {
            append(SYSTEM_PROMPT.replace(TARGET_LANG_PLACEHOLDER, SelectionTranslatePrompt.displayName(targetLang)))
            if (glossary.isNotEmpty()) {
                append("\n\n术语对照（翻译时严格采用以下译法）：\n")
                glossary.forEach { (source, target) -> append(source).append(" → ").append(target).append('\n') }
            }
        }
        val user = bubbleTexts
            .mapIndexed { index, text -> "<${index + 1}>\n$text" }
            .joinToString("\n\n")
        return listOf(
            AiMessage.of(AiRole.SYSTEM, system),
            AiMessage.of(AiRole.USER, user),
        )
    }

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 解析模型输出的气泡译文数组。
     *
     * 容忍散文包裹与 ```json 代码围栏：截取第一个 `{` 到最后一个 `}` 再解析。
     * JSON 畸形、translations 缺失，或数量与 [expectedCount] 不一致 → null（调用方据此重试该页）。
     */
    fun parseTranslations(raw: String, expectedCount: Int): List<String>? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end < start) return null

        val parsed = runCatching {
            json.decodeFromString<TranslationsResponse>(raw.substring(start, end + 1))
        }.getOrNull() ?: return null

        return parsed.translations.takeIf { it.size == expectedCount }
    }
}

package com.llzx373.foldreader.core.ai.prompt

import com.llzx373.foldreader.core.ai.AiMessage
import com.llzx373.foldreader.core.ai.AiRole
import com.llzx373.foldreader.core.ai.AiTargetLang
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class ParagraphsResponse(val paragraphs: List<String> = emptyList())

/**
 * M19「单章（块）翻译」与「按页翻译」的提示词：段落结构化输出契约。
 *
 * 模型按编号段落逐一翻译、以 `{"paragraphs":[...]}` 返回，解析校验（数量必须与输入一致）
 * 失败即该单位重试——不做事后对齐（docs/AI功能需求与实施.md 5.2）。
 *
 * 纯 JVM、不碰 Android；目标语言经占位符 [TARGET_LANG_PLACEHOLDER] 在 [buildMessages] 时注入，
 * 登记表里的 [SYSTEM_PROMPT] 保留占位符原文，设置页「内置提示词」看到的就是模板。
 */
object UnitTranslatePrompt {

    /** 系统提示词中的目标语言占位符。 */
    const val TARGET_LANG_PLACEHOLDER = "{{目标语言}}"

    /** 系统提示词全文。改动即行为变更：进 CHANGELOG，输出格式变了同步改解析与单测。 */
    val SYSTEM_PROMPT =
        """
        你是书籍翻译器。用户会给你一组编号段落（每段以「<N>」开头单独成行，N 从 1 开始连续编号），它们来自同一章/节。

        你的任务：自动检测原文语言，把每一段翻译成$TARGET_LANG_PLACEHOLDER。

        要求：
        1. 逐段翻译，不得合并、拆分或遗漏段落，译文段落与输入段落一一对应；
        2. 只输出如下 JSON，不要输出任何其他文字、解释或 Markdown 代码围栏：
        {"paragraphs": ["第一段译文", "第二段译文"]}
        3. paragraphs 数组的元素数量必须与输入段落数量完全一致，顺序一一对应；
        4. 保持原意与语体，人名、地名、专有名词按目标语言惯例翻译并全文保持一致；
        5. 原文语言与目标语言相同时，原样返回原文段落。
        """.trimIndent()

    /**
     * SYSTEM 给任务、目标语言与输出契约，USER 附上编号段落。
     *
     * [glossary] 非空时在 SYSTEM 末尾追加「术语对照」节（M20 术语表注入点）；
     * [systemOverride] 非空时整体替换内置系统提示词——M19「当次临时提示词」，
     * 仅当次生效、不写回内置模板（R11）。
     */
    fun buildMessages(
        paragraphs: List<String>,
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
        val user = paragraphs
            .mapIndexed { index, paragraph -> "<${index + 1}>\n$paragraph" }
            .joinToString("\n\n")
        return listOf(
            AiMessage.of(AiRole.SYSTEM, system),
            AiMessage.of(AiRole.USER, user),
        )
    }

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 解析模型输出的段落数组。
     *
     * 容忍散文包裹与 ```json 代码围栏：截取第一个 `{` 到最后一个 `}` 再解析。
     * JSON 畸形、paragraphs 缺失，或数量与 [expectedCount] 不一致 → null（调用方据此重试该单位）。
     */
    fun parseParagraphs(raw: String, expectedCount: Int): List<String>? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end < start) return null

        val parsed = runCatching {
            json.decodeFromString<ParagraphsResponse>(raw.substring(start, end + 1))
        }.getOrNull() ?: return null

        return parsed.paragraphs.takeIf { it.size == expectedCount }
    }
}

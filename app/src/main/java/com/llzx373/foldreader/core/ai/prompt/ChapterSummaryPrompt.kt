package com.llzx373.foldreader.core.ai.prompt

import com.llzx373.foldreader.core.ai.AiMessage
import com.llzx373.foldreader.core.ai.AiRole
import com.llzx373.foldreader.core.ai.AiTargetLang

/**
 * M29「章节摘要」的提示词：把一个摘要单位（章 / 块）的正文浓缩成一段纯文本摘要。
 *
 * 输出是纯文本（非 JSON）：摘要就是给人读的小段，不需要结构化契约；解析只做
 * 围栏剥离与空白规整（[parseSummary]），畸形（空输出）即丢弃由调用方重试。
 * 纯 JVM、不碰 Android；摘要语言经占位符 [TARGET_LANG_PLACEHOLDER] 注入，
 * 登记表里的 [SYSTEM_PROMPT] 保留占位符原文。
 */
object ChapterSummaryPrompt {

    /** 系统提示词中的摘要语言占位符。 */
    const val TARGET_LANG_PLACEHOLDER = "{{目标语言}}"

    /** 系统提示词全文。改动即行为变更：进 CHANGELOG。 */
    val SYSTEM_PROMPT =
        """
        你是书籍摘要员。用户会给你电子书中的一章（或一个切块）的正文。

        你的任务：用${TARGET_LANG_PLACEHOLDER}写出这一段内容的摘要。

        要求：
        1. 摘要控制在 150~300 字，涵盖主要事件、出场人物与关键信息；
        2. 按原文叙事顺序概括，不评论、不解读、不剧透本段之外的内容；
        3. 只输出摘要正文，不要复述章节标题、不要输出 Markdown 代码围栏或多余寒暄；
        4. 原文语言与摘要语言相同时也照常概括，不要照抄原文。
        """.trimIndent()

    /** SYSTEM 给任务与摘要语言，USER 附上单位标题 + 正文。 */
    fun buildMessages(
        unitTitle: String,
        unitText: String,
        targetLang: AiTargetLang,
    ): List<AiMessage> = listOf(
        AiMessage.of(
            AiRole.SYSTEM,
            SYSTEM_PROMPT.replace(
                TARGET_LANG_PLACEHOLDER,
                SelectionTranslatePrompt.displayName(targetLang),
            ),
        ),
        AiMessage.of(AiRole.USER, "《$unitTitle》\n\n$unitText"),
    )

    /**
     * 解析模型输出的摘要：剥离 ``` 代码围栏与首尾空白；空结果返回 null（调用方重试）。
     */
    fun parseSummary(raw: String): String? = stripCodeFence(raw).takeIf { it.isNotEmpty() }
}

/** 剥离 Markdown 代码围栏（``` 或 ```text 包裹）与首尾空白；无围栏时原样规整。 */
internal fun stripCodeFence(raw: String): String {
    var text = raw.trim()
    if (text.startsWith("```")) {
        text = text.substringAfter('\n', "").ifEmpty { text.removePrefix("```") }
    }
    if (text.endsWith("```")) text = text.removeSuffix("```")
    return text.trim()
}

package com.llzx373.foldreader.core.ai.prompt

import com.llzx373.foldreader.core.ai.AiMessage
import com.llzx373.foldreader.core.ai.AiRole
import com.llzx373.foldreader.core.ai.AiTargetLang

/**
 * M29「全书大纲」的提示词：把已生成的章节摘要链（按单位顺序）聚合成一份全书大纲。
 *
 * 输入是摘要而不是正文——大纲的语境窗口代价由此封顶（摘要链聚合生成）。
 * 输出纯文本大纲（条目式），解析只做围栏剥离（同 [ChapterSummaryPrompt] 口径）。
 */
object BookOutlinePrompt {

    /** 系统提示词中的大纲语言占位符。 */
    const val TARGET_LANG_PLACEHOLDER = "{{目标语言}}"

    /** 系统提示词全文。改动即行为变更：进 CHANGELOG。 */
    val SYSTEM_PROMPT =
        """
        你是书籍结构分析员。用户会给你一本书按章节顺序排列的各章（块）摘要。

        你的任务：用${TARGET_LANG_PLACEHOLDER}把这些摘要聚合成一份全书大纲。

        要求：
        1. 大纲按情节 / 内容的发展脉络分 3~8 个阶段，每个阶段一行标题加一两句说明，
           并标注该阶段覆盖的章节范围（用「第N节」序号）；
        2. 只依据给出的摘要，不要虚构摘要之外的情节；
        3. 只输出大纲正文，不要输出 Markdown 代码围栏或多余寒暄。
        """.trimIndent()

    /**
     * SYSTEM 给任务与语言，USER 附书名 + 编号摘要链（`【第N节 · 标题】\n摘要`）。
     * [summaries] 按单位顺序排列，序号从 1 起连续编号（与摘要无关，仅供大纲引用章节范围）。
     */
    fun buildMessages(
        bookTitle: String,
        summaries: List<Pair<String, String>>,
        targetLang: AiTargetLang,
    ): List<AiMessage> {
        val chain = summaries.mapIndexed { index, (title, summary) ->
            "【第${index + 1}节 · $title】\n$summary"
        }.joinToString("\n\n")
        return listOf(
            AiMessage.of(
                AiRole.SYSTEM,
                SYSTEM_PROMPT.replace(
                    TARGET_LANG_PLACEHOLDER,
                    SelectionTranslatePrompt.displayName(targetLang),
                ),
            ),
            AiMessage.of(AiRole.USER, "《$bookTitle》\n\n$chain"),
        )
    }

    /** 解析模型输出的大纲：剥离围栏与首尾空白；空结果返回 null（调用方重试 / 提示失败）。 */
    fun parseOutline(raw: String): String? = stripCodeFence(raw).takeIf { it.isNotEmpty() }
}

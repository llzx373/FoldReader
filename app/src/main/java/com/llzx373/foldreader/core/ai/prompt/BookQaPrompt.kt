package com.llzx373.foldreader.core.ai.prompt

import com.llzx373.foldreader.core.ai.AiMessage
import com.llzx373.foldreader.core.ai.AiRole
import com.llzx373.foldreader.core.ai.AiTargetLang
import com.llzx373.foldreader.core.summary.BookQaContext

/**
 * M29「问书」的提示词：上下文 = 前序章节摘要链 + 当前章正文（已读范围之外不进上下文）。
 *
 * 输出纯文本回答，模型自行用《章节标题》标注来源章节（要求 2）；
 * 上下文不足时明说而不是编造（要求 1）。纯 JVM；回答语言经 [TARGET_LANG_PLACEHOLDER] 注入。
 */
object BookQaPrompt {

    /** 系统提示词中的回答语言占位符。 */
    const val TARGET_LANG_PLACEHOLDER = "{{目标语言}}"

    /** 系统提示词全文。改动即行为变更：进 CHANGELOG。 */
    val SYSTEM_PROMPT =
        """
        你是读书助手。用户正在读一本电子书，会给你「前序章节的摘要」与「当前章正文」，然后提问。

        你的任务：用${TARGET_LANG_PLACEHOLDER}回答用户的问题。

        要求：
        1. 只依据给出的摘要与当前章正文回答；上下文没有的信息就明说「目前给出的范围里没有提到」，不要虚构；
        2. 回答中标注信息来源章节（用《章节标题》引用）；
        3. 用户还没读到当前章之后的内容，不要推测或剧透后续情节；
        4. 简明扼要，直接回答问题，不要复述上下文。
        """.trimIndent()

    /**
     * SYSTEM 给任务与回答语言；USER 拼上下文（摘要链在前、当前章正文在后）与问题。
     */
    fun buildMessages(
        question: String,
        context: BookQaContext,
        targetLang: AiTargetLang,
    ): List<AiMessage> {
        val user = buildString {
            if (context.summaries.isNotEmpty()) {
                append("【前序章节摘要】\n")
                context.summaries.forEach { (title, summary) ->
                    append("《").append(title).append("》\n").append(summary).append("\n\n")
                }
            }
            context.currentChapter?.let { (title, text) ->
                append("【当前章：").append(title).append("】\n").append(text).append("\n\n")
            }
            append("【问题】\n").append(question)
        }
        return listOf(
            AiMessage.of(
                AiRole.SYSTEM,
                SYSTEM_PROMPT.replace(
                    TARGET_LANG_PLACEHOLDER,
                    SelectionTranslatePrompt.displayName(targetLang),
                ),
            ),
            AiMessage.of(AiRole.USER, user.trimEnd()),
        )
    }
}

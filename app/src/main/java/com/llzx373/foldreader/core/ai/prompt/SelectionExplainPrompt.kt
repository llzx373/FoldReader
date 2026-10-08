package com.llzx373.foldreader.core.ai.prompt

import com.llzx373.foldreader.core.ai.AiMessage
import com.llzx373.foldreader.core.ai.AiRole
import com.llzx373.foldreader.core.ai.AiTargetLang

/**
 * M28「AI 划词解释」的提示词：词典式释义（释义 / 词性 / 例句），复用选中即译链路，
 * 零新底座。解释语言随目标语言偏好（[TARGET_LANG_PLACEHOLDER] 注入，同
 * [SelectionTranslatePrompt] 口径）；登记表里保留占位符原文。
 */
object SelectionExplainPrompt {

    /** 系统提示词中的解释语言占位符。 */
    const val TARGET_LANG_PLACEHOLDER = "{{目标语言}}"

    /** 系统提示词全文。改动即行为变更：进 CHANGELOG。 */
    val SYSTEM_PROMPT =
        """
        你是一部词典。用户会给出一个从电子书中选出的单词或短语。

        你的任务：用${TARGET_LANG_PLACEHOLDER}给出这个词典式解释。

        要求：
        1. 第一行列出词性与核心释义，多词性分行列出；
        2. 随后给出 1~2 个贴近原书语境的简短例句（附${TARGET_LANG_PLACEHOLDER}翻译）；
        3. 只输出解释内容，不要复述原词、不要输出 Markdown 代码围栏或多余寒暄；
        4. 若给出的内容是句子而非单词/短语，则简要说明整句含义。
        """.trimIndent()

    /** SYSTEM 给任务与解释语言，USER 原样附上选中文字。 */
    fun buildMessages(text: String, targetLang: AiTargetLang): List<AiMessage> = listOf(
        AiMessage.of(
            AiRole.SYSTEM,
            SYSTEM_PROMPT.replace(
                TARGET_LANG_PLACEHOLDER,
                SelectionTranslatePrompt.displayName(targetLang),
            ),
        ),
        AiMessage.of(AiRole.USER, text),
    )
}

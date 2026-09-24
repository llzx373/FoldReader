package com.llzx373.foldreader.core.ai.prompt

import com.llzx373.foldreader.core.ai.AiMessage
import com.llzx373.foldreader.core.ai.AiRole
import com.llzx373.foldreader.core.ai.AiTargetLang

/**
 * M18「选中即译」的提示词。
 *
 * 纯 JVM、不碰 Android；目标语言经占位符 [TARGET_LANG_PLACEHOLDER] 在 [buildMessages] 时注入，
 * 登记表里的 [SYSTEM_PROMPT] 保留占位符原文，设置页「内置提示词」看到的就是模板。
 */
object SelectionTranslatePrompt {

    /** 系统提示词中的目标语言占位符。 */
    const val TARGET_LANG_PLACEHOLDER = "{{目标语言}}"

    /** 系统提示词全文。改动即行为变更：进 CHANGELOG。 */
    val SYSTEM_PROMPT =
        """
        你是通用翻译器。用户会给你一段从电子书中选出的原文。

        你的任务：自动检测原文语言，把它翻译成$TARGET_LANG_PLACEHOLDER。

        要求：
        1. 只输出译文，不要输出任何解释、注音、对照或 Markdown 代码围栏；
        2. 保持原意与语体，人名、地名、专有名词按目标语言惯例翻译；
        3. 保留原文的段落换行与格式；
        4. 原文语言与目标语言相同时，原样返回原文。
        """.trimIndent()

    /** 目标语言的提示词/界面显示名。 */
    fun displayName(lang: AiTargetLang): String = when (lang) {
        AiTargetLang.ZH_HANS -> "简体中文"
        AiTargetLang.ZH_HANT -> "繁體中文"
        AiTargetLang.EN -> "English"
        AiTargetLang.JA -> "日本語"
    }

    /** SYSTEM 给任务与目标语言，USER 原样附上选中原文。 */
    fun buildMessages(text: String, targetLang: AiTargetLang): List<AiMessage> = listOf(
        AiMessage.of(
            AiRole.SYSTEM,
            SYSTEM_PROMPT.replace(TARGET_LANG_PLACEHOLDER, displayName(targetLang)),
        ),
        AiMessage.of(AiRole.USER, text),
    )
}

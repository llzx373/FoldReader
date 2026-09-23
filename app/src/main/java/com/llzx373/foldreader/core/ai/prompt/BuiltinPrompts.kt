package com.llzx373.foldreader.core.ai.prompt

/** 一项内置提示词：[feature] 功能名（如「章节规则生成」），[template] 提示词全文。 */
data class BuiltinPrompt(val feature: String, val template: String)

/**
 * 内置提示词登记表（docs/AI功能需求与实施.md 2.5）。
 *
 * 设置页「内置提示词」只读查看枚举 [all]；新增功能在此追加一项即可。
 */
object BuiltinPrompts {
    val chapterRuleGeneration = BuiltinPrompt("章节规则生成", ChapterRulePrompt.SYSTEM_PROMPT)

    val all: List<BuiltinPrompt> = listOf(chapterRuleGeneration)
}

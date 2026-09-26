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
    val selectionTranslation = BuiltinPrompt("选中即译", SelectionTranslatePrompt.SYSTEM_PROMPT)
    val unitTranslation = BuiltinPrompt("章节翻译", UnitTranslatePrompt.SYSTEM_PROMPT)
    val glossaryBackfill = BuiltinPrompt("术语回填", GlossaryBackfillPrompt.SYSTEM_PROMPT)
    val cleanRecipeRecommendation = BuiltinPrompt("清洗配方推荐", CleanRecipePrompt.SYSTEM_PROMPT)
    val metadataCompletion = BuiltinPrompt("元数据补全", MetadataPrompt.SYSTEM_PROMPT)

    val all: List<BuiltinPrompt> =
        listOf(
            chapterRuleGeneration,
            selectionTranslation,
            unitTranslation,
            glossaryBackfill,
            cleanRecipeRecommendation,
            metadataCompletion,
        )
}

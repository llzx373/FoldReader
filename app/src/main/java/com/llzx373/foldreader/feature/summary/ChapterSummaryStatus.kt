package com.llzx373.foldreader.feature.summary

import com.llzx373.foldreader.core.data.db.ChapterSummaryEntity
import com.llzx373.foldreader.core.format.Chapter
import com.llzx373.foldreader.core.translate.TranslationUnit
import com.llzx373.foldreader.feature.translate.unitsOfChapter

/**
 * 目录面板的「章 ↔ 摘要单位」映射与行状态（M29）。纯 JVM，规则由单测锁定。
 * 与 M19 翻译状态（`feature/translate/ChapterUnitStatus`）同一套单位划分口径。
 */

/** 每章一行的摘要状态标签（与 [chapters] 位置对齐；null = 不标）。 */
data class ChapterSummaryRow(
    /** 状态文案（未摘要 / 摘要中 / 已摘要 x/n / 已摘要 / 失败）；null = 不标。 */
    val label: String?,
    /** 有任一 done 摘要 → 可点开看摘要。 */
    val viewable: Boolean,
    /** 有单位未 done 且无 summarizing → 可点「生成摘要」。 */
    val generatable: Boolean,
)

/**
 * 每章一行的摘要状态。
 *
 * [units] 为空（这本书还没算过单位）→ 全部「无状态」（不标、不可点），目录零变化。规则：
 * 全部 done → 已摘要；有 summarizing → 摘要中；有 failed → 失败；
 * 部分 done → 已摘要 x/n；其余 → 未摘要。
 */
fun chapterSummaryRows(
    chapters: List<Chapter>,
    units: List<TranslationUnit>,
    rows: List<ChapterSummaryEntity>,
): List<ChapterSummaryRow> {
    if (units.isEmpty()) {
        return List(chapters.size) { ChapterSummaryRow(null, viewable = false, generatable = false) }
    }
    val statusByUnit = rows.associateBy({ it.unitIndex }, { it.status })
    return chapters.map { chapter ->
        val chapterUnits = unitsOfChapter(chapter, units)
        if (chapterUnits.isEmpty()) {
            ChapterSummaryRow(null, viewable = false, generatable = false)
        } else {
            val statuses = chapterUnits.map { statusByUnit[it.index] }
            val done = statuses.count { it == ChapterSummaryEntity.STATUS_DONE }
            val summarizing = statuses.any { it == ChapterSummaryEntity.STATUS_SUMMARIZING }
            val label = when {
                done == statuses.size -> "已摘要"
                summarizing -> "摘要中"
                statuses.any { it == ChapterSummaryEntity.STATUS_FAILED } -> "失败"
                done > 0 -> "已摘要 $done/${statuses.size}"
                else -> "未摘要"
            }
            ChapterSummaryRow(
                label = label,
                viewable = done > 0,
                generatable = done < statuses.size && !summarizing,
            )
        }
    }
}

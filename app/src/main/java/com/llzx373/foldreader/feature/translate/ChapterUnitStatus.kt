package com.llzx373.foldreader.feature.translate

import com.llzx373.foldreader.core.data.db.TranslationEntity
import com.llzx373.foldreader.core.format.Chapter
import com.llzx373.foldreader.core.translate.TranslationUnit

/**
 * 目录面板的「章 ↔ 翻译单位」映射与行状态文案（M19）。纯 JVM，规则由单测锁定。
 */

/** 章 `[charStart, charEnd)` 覆盖到的单位（区间相交即算，块单位可能只盖住章的一段）。 */
fun unitsOfChapter(chapter: Chapter, units: List<TranslationUnit>): List<TranslationUnit> =
    units.filter { it.charStart < chapter.charEnd && it.charEnd > chapter.charStart }

/**
 * 每章一行的翻译状态文案（与 [chapters] 位置对齐；null = 不标）。
 *
 * [units] 为空（这本书还没用过翻译）→ 全部 null，目录零变化。规则：
 * 全部 done → 已译；有 translating → 翻译中；有 failed → 失败；
 * 部分 done → 已译 x/n；其余 → 未译。
 */
fun chapterStatusLabels(
    chapters: List<Chapter>,
    units: List<TranslationUnit>,
    rows: List<TranslationEntity>,
): List<String?> {
    if (units.isEmpty()) return List(chapters.size) { null }
    val statusByUnit = rows.associateBy({ it.unitIndex }, { it.status })
    return chapters.map { chapter ->
        val chapterUnits = unitsOfChapter(chapter, units)
        if (chapterUnits.isEmpty()) {
            null
        } else {
            val statuses = chapterUnits.map { statusByUnit[it.index] }
            val done = statuses.count { it == TranslationEntity.STATUS_DONE }
            when {
                done == statuses.size -> "已译"
                statuses.any { it == TranslationEntity.STATUS_TRANSLATING } -> "翻译中"
                statuses.any { it == TranslationEntity.STATUS_FAILED } -> "失败"
                done > 0 -> "已译 $done/${statuses.size}"
                else -> "未译"
            }
        }
    }
}

/** 该行是否可「重译」：覆盖到的单位里有任一 done / failed（未译与翻译中不给）。 */
fun chapterRetranslatable(
    chapters: List<Chapter>,
    units: List<TranslationUnit>,
    rows: List<TranslationEntity>,
): List<Boolean> {
    if (units.isEmpty()) return List(chapters.size) { false }
    val statusByUnit = rows.associateBy({ it.unitIndex }, { it.status })
    return chapters.map { chapter ->
        unitsOfChapter(chapter, units).any {
            val status = statusByUnit[it.index]
            status == TranslationEntity.STATUS_DONE || status == TranslationEntity.STATUS_FAILED
        }
    }
}

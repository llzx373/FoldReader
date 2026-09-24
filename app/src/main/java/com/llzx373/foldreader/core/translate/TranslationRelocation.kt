package com.llzx373.foldreader.core.translate

import com.llzx373.foldreader.core.format.Chapter

/**
 * 视角 1（原文 / 译文切换）的重定位：**单位号 + 单位内进度比例**
 * （docs/AI功能需求与实施.md 5.0/5.4）。
 *
 * 原文与译本是两套互不换算的坐标，切换时不做字符级对齐，只按
 * 「当前偏移落在哪个单位、单位内走了多远」映射到另一侧同一单位的同一比例处。
 * 纯 JVM，单边逻辑由单测锁定。
 */

/**
 * [offset] 落在哪个区间、区间内比例多少（0..1）。
 *
 * 区间 = 半开 `[first, last+1)`、按 first 升序（可以不相邻，缝隙处的偏移归到
 * 前一个区间末端）。空表返回 null；越界 clamp 到首 / 尾区间；零长区间比例恒 0。
 */
fun locateInRanges(ranges: List<LongRange>, offset: Long): Pair<Int, Float>? {
    if (ranges.isEmpty()) return null
    var index = ranges.indexOfLast { it.first <= offset }
    if (index < 0) index = 0
    val range = ranges[index]
    val span = range.last - range.first + 1
    if (span <= 0L) return index to 0f
    return index to ((offset - range.first).toFloat() / span).coerceIn(0f, 1f)
}

/** 原文流偏移 → (单位号, 单位内比例)。单位号为 [units] 的列表序号（与 `TranslationUnit.index` 一致）。 */
fun locateUnit(units: List<TranslationUnit>, offset: Long): Pair<Int, Float>? =
    locateInRanges(units.map { it.charStart until it.charEnd }, offset)

/** 译本流偏移 → (单位号, 单位内比例)；[translatedChapters] 为 assemble 产出的译本章节（与单位一一对齐）。 */
fun locateTranslatedUnit(translatedChapters: List<Chapter>, offset: Long): Pair<Int, Float>? =
    locateInRanges(translatedChapters.map { it.charStart until it.charEnd }, offset)

private fun offsetAt(range: LongRange, fraction: Float): Long {
    val span = range.last - range.first + 1
    if (span <= 0L) return range.first
    // 比例 1.0 收在区间内最后一个字符，避免落到下一单位首字符（翻页定位更贴合"读到这"）
    return range.first + (span * fraction.coerceIn(0f, 1f)).toLong().coerceIn(0L, span - 1)
}

/** 单位号 + 比例 → 原文流偏移（切回原文用）。 */
fun offsetInOriginal(units: List<TranslationUnit>, unitIndex: Int, fraction: Float): Long {
    val unit = units.getOrNull(unitIndex) ?: return 0L
    return offsetAt(unit.charStart until unit.charEnd, fraction)
}

/** 单位号 + 比例 → 译本流偏移（切到译文用）。 */
fun offsetInTranslated(translatedChapters: List<Chapter>, unitIndex: Int, fraction: Float): Long {
    val chapter = translatedChapters.getOrNull(unitIndex) ?: return 0L
    return offsetAt(chapter.charStart until chapter.charEnd, fraction)
}

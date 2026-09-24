package com.llzx373.foldreader.core.translate

import com.llzx373.foldreader.core.format.Chapter

/**
 * 视角 2（双页左原文右译文）的左右同步计算（docs/AI功能需求与实施.md 5.4-2）。
 *
 * 翻页以左侧原文为准，右侧译本只有两个动作：
 * - 左页跨入新单位（右侧当前不在该单位）→ **追齐**：右页跳到该单位在译本流的起点；
 * - 左页仍在右侧当前单位内 → **漂移跟随**：按「单位号 + 单位内比例」定位到译本对应处。
 *
 * 两侧页容量不同（译文长度与原文不成比例），单位内允许按比例漂移，不做字符级对齐。
 * 纯 JVM，单边逻辑由单测锁定。
 */

/** 一次同步的结果。 */
data class BilingualSyncResult(
    /** 右侧（译本流）目标偏移，已 clamp 在对应单位区间内。 */
    val targetOffset: Long,
    /** true = 追齐（左页跨入新单位，右页跳到该单位起点）；false = 单位内按比例漂移。 */
    val caughtUp: Boolean,
)

/**
 * 由左页锚点算右页目标偏移。
 *
 * @param units 原文翻译单位（原文流坐标）；
 * @param translatedChapters assemble 产出的译本章节（译本流坐标，与 [units] 一一对齐）；
 * @param leftAnchor 左页锚点（原文流偏移，翻页 / 跳章 / 进度条拖动后的新值）；
 * @param rightOffset 右侧当前偏移（译本流，用于判断右侧是否还停在左页所在单位）；
 * 任一输入为空返回 null（调用方保持右页不变）。
 */
fun bilingualSyncTarget(
    units: List<TranslationUnit>,
    translatedChapters: List<Chapter>,
    leftAnchor: Long,
    rightOffset: Long,
): BilingualSyncResult? {
    if (translatedChapters.isEmpty()) return null
    val (unitIndex, fraction) = locateUnit(units, leftAnchor) ?: return null
    val rightUnit = locateTranslatedUnit(translatedChapters, rightOffset)?.first
    return if (rightUnit != unitIndex) {
        BilingualSyncResult(offsetInTranslated(translatedChapters, unitIndex, 0f), caughtUp = true)
    } else {
        BilingualSyncResult(offsetInTranslated(translatedChapters, unitIndex, fraction), caughtUp = false)
    }
}

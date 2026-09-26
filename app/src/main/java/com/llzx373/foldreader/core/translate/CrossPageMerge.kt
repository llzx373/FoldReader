package com.llzx373.foldreader.core.translate

import com.llzx373.foldreader.core.ocr.OcrBubble

/**
 * 条漫跨页气泡合并（R10）：长条漫被预切成多页时，一个气泡可能被切在
 * 页 N 底边与页 N+1 顶边之间。不切开来翻会把一句话翻成两半，所以把
 * 页 N+1 顶部的续段并入页 N 的气泡（文字行并入、记续段矩形），并从
 * 页 N+1 的气泡表里移除该片段。
 *
 * 纯逻辑：只产出合并计划（两页的新气泡表），落盘与「只在两页都未译时合并」
 * 的判定在调用方（引擎）——已译页的气泡序号动不得。
 */
object CrossPageMerge {

    /** 贴边判定（归一化）：底边距 1.0 / 顶边距 0.0 在此内即视为被页边切断。 */
    const val EDGE_EPS = 0.01f

    /** 横向重叠下限：重叠宽度占较窄气泡宽度的比例低于此值不配对（防止误吞相邻气泡）。 */
    const val MIN_H_OVERLAP = 0.5f

    /** 一次合并的结果：[owner] 页 N 的新气泡表；[next] 页 N+1 移除片段并重排序的新气泡表。 */
    data class MergePlan(
        val owner: List<OcrBubble>,
        val next: List<OcrBubble>,
        /** 合并了几对（>0 才有落盘的必要）。 */
        val merged: Int,
    )

    /**
     * 规划一次跨页合并；无可配对片段返回 null（调用方不动缓存）。
     *
     * 配对规则：页 N 贴底气泡 × 页 N+1 贴顶片段，横向重叠 ≥ [MIN_H_OVERLAP]；
     * 候选对按重叠降序全局贪心分配——每个片段至多被吞一次，每个底气泡至多吞一个片段。
     */
    fun plan(page: List<OcrBubble>, next: List<OcrBubble>): MergePlan? {
        val bottomers = page.filter { it.rect.bottom > 1f - EDGE_EPS }
        val toppers = next.filter { it.rect.top < EDGE_EPS }
        if (bottomers.isEmpty() || toppers.isEmpty()) return null

        val usedFragments = mutableSetOf<Int>()
        val usedOwners = mutableSetOf<Int>()
        // 全局贪心：所有候选对按重叠降序分配——每个底气泡至多吞一个片段，每个片段至多被吞一次
        val pairs = bottomers.flatMap { owner ->
            toppers.mapNotNull { frag ->
                val overlap = hOverlapRatio(owner, frag)
                if (overlap >= MIN_H_OVERLAP) Triple(owner, frag, overlap) else null
            }
        }.sortedByDescending { it.third }
        val ownerByIndex = HashMap<Int, OcrBubble>()
        for ((owner, frag, _) in pairs) {
            if (owner.index in usedOwners || frag.index in usedFragments) continue
            usedOwners += owner.index
            usedFragments += frag.index
            // 续段文字行并入主气泡（沿用片段内行序），续段矩形记到主气泡上
            ownerByIndex[owner.index] = owner.copy(
                lines = owner.lines + frag.lines,
                continuation = frag.rect,
            )
        }
        if (ownerByIndex.isEmpty()) return null

        val newOwner = page.map { ownerByIndex[it.index] ?: it }
        val newNext = next
            .filter { it.index !in usedFragments }
            .mapIndexed { i, bubble -> bubble.copy(index = i) }
        return MergePlan(owner = newOwner, next = newNext, merged = ownerByIndex.size)
    }

    /** 横向重叠宽度占较窄气泡宽度的比例（0 表示横向不相交）。 */
    internal fun hOverlapRatio(a: OcrBubble, b: OcrBubble): Float {
        val overlap = minOf(a.rect.right, b.rect.right) - maxOf(a.rect.left, b.rect.left)
        if (overlap <= 0f) return 0f
        val narrower = minOf(a.rect.width, b.rect.width)
        if (narrower <= 0f) return 0f
        return overlap / narrower
    }
}

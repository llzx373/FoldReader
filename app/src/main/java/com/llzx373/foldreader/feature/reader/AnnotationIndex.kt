package com.llzx373.foldreader.feature.reader

import com.llzx373.foldreader.core.data.db.AnnotationEntity

/**
 * 标注区间索引：按起始偏移排序后二分，替代「每个可见页都全量遍历标注列表」。
 *
 * 命中判定与原实现一致：`ann.endCharOffset > start && ann.startCharOffset < end`。
 * 反向扫描用前缀最大结束偏移提前终止——只要更早的标注里最大的结束偏移都不超过 [start]，
 * 再往前就不可能命中了。
 *
 * 标注列表变化不频繁，调用方 `remember(annotations)` 构建一次即可复用于多次滚动。
 */
class AnnotationIndex(annotations: List<AnnotationEntity>) {

    private val sorted: List<AnnotationEntity> = annotations.sortedBy { it.startCharOffset }
    private val starts = LongArray(sorted.size) { sorted[it].startCharOffset }
    private val prefixMaxEnd = LongArray(sorted.size).also { maxEnd ->
        var running = Long.MIN_VALUE
        for (i in sorted.indices) {
            running = maxOf(running, sorted[i].endCharOffset)
            maxEnd[i] = running
        }
    }

    val size: Int get() = sorted.size

    /**
     * 与区间 [start, end) 有交集的标注，按起始偏移升序返回。
     *
     * 不做「空区间直接返回空」的守卫：原实现是纯谓词过滤，空页/退化区间同样可能命中，
     * 这里必须逐点等价（否则空页上的划线会消失）。
     */
    fun overlapping(start: Long, end: Long): List<AnnotationEntity> {
        if (sorted.isEmpty()) return emptyList()
        // 首个 startCharOffset >= end 的位置：谓词要求 startCharOffset < end，故它之前的才是候选
        var lo = 0
        var hi = starts.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (starts[mid] < end) lo = mid + 1 else hi = mid
        }
        val out = ArrayList<AnnotationEntity>(4)
        for (i in lo - 1 downTo 0) {
            if (prefixMaxEnd[i] <= start) break
            val ann = sorted[i]
            if (ann.endCharOffset > start) out += ann
        }
        out.reverse()
        return out
    }
}

package com.llzx373.foldreader.feature.reader

/** 标注快照长度上限（字符），超出截断；校验时按快照长度做前缀比对。 */
const val ANNOTATION_SNAPSHOT_MAX_CHARS = 8192

/** 选区 [selStart, selEnd) 与页 [pageStart, pageEnd) 求交，返回页内片段（null = 不相交）。 */
fun intersectRange(
    selStart: Long,
    selEnd: Long,
    pageStart: Long,
    pageEnd: Long,
): LongRange? {
    val s = maxOf(selStart, pageStart)
    val e = minOf(selEnd, pageEnd)
    return if (s < e) s until e else null
}

enum class SelectionEdge { NONE, PREVIOUS, NEXT }

/** 拖动点超出内容区边缘 [marginPx] 时判定翻页方向（x 优先，其次 y）。 */
fun selectionEdgeAt(
    x: Float,
    y: Float,
    areaLeft: Float,
    areaTop: Float,
    areaRight: Float,
    areaBottom: Float,
    marginPx: Float,
): SelectionEdge = when {
    areaRight <= areaLeft || areaBottom <= areaTop -> SelectionEdge.NONE
    x < areaLeft - marginPx || y < areaTop - marginPx -> SelectionEdge.PREVIOUS
    x > areaRight + marginPx || y > areaBottom + marginPx -> SelectionEdge.NEXT
    else -> SelectionEdge.NONE
}

/**
 * 标注快照区间：终点钳到 [totalChars] 与 [ANNOTATION_SNAPSHOT_MAX_CHARS] 上限。
 * 返回 (读取区间, 是否截断)；空选区返回 null。
 */
fun annotationSnapshotRange(start: Long, end: Long, totalChars: Long): Pair<LongRange, Boolean>? {
    val safeStart = start.coerceIn(0L, totalChars)
    val cappedEnd = minOf(end, totalChars, safeStart + ANNOTATION_SNAPSHOT_MAX_CHARS)
    if (cappedEnd <= safeStart) return null
    return (safeStart until cappedEnd) to (cappedEnd < minOf(end, totalChars))
}

/** 快照校验读区间：按快照长度做前缀比对（截断快照与未截断统一处理）。 */
fun snapshotVerifyRange(start: Long, end: Long, snapshotLength: Int, totalChars: Long): LongRange? {
    val safeStart = start.coerceIn(0L, totalChars)
    val compareLen = minOf(end - safeStart, snapshotLength.toLong())
    if (compareLen <= 0L) return null
    return safeStart until safeStart + compareLen
}

/**
 * 滚动模式选中文本 → 字符偏移：在 [pageCharStart, pageCharStart + pageText.length) 内查找
 * [selected]，多处命中时取与 [preferCharIndex]（可见区域中心等）最近的一处。
 * 返回 null 表示页内未命中（调用方可换页或放弃）。
 */
fun mapSelectedTextToOffsets(
    pageCharStart: Long,
    pageText: String,
    selected: String,
    preferCharIndex: Long? = null,
): LongRange? {
    if (selected.isEmpty() || pageText.isEmpty()) return null
    var from = 0
    var best: Int? = null
    while (true) {
        val idx = pageText.indexOf(selected, from)
        if (idx < 0) break
        val cur = best
        best = when {
            cur == null -> idx
            preferCharIndex == null -> cur
            else -> {
                val curDist = kotlin.math.abs(pageCharStart + cur - preferCharIndex)
                val newDist = kotlin.math.abs(pageCharStart + idx - preferCharIndex)
                if (newDist < curDist) idx else cur
            }
        }
        from = idx + 1
    }
    val hit = best ?: return null
    val start = pageCharStart + hit
    return start until start + selected.length
}

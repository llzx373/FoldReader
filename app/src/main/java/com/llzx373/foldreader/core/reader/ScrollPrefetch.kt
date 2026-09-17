package com.llzx373.foldreader.core.reader

/**
 * 滚动模式批量预取：从 [startCursor] 出发连续取页，直到取满 [limit] 页或 [next] 返回 null
 * （null = 已到书首/书尾）。
 *
 * 抽成不依赖分页器的纯逻辑，是为了能直接单测「取多少页、何时停」这段边界，
 * 而不必构造真实 Paginator。[advance] 给出下一页的游标：
 * 向后翻取 `charEnd`，向前翻取 `charStart - 1`。
 */
internal suspend fun collectScrollPages(
    startCursor: Long,
    limit: Int,
    next: suspend (cursor: Long) -> Page?,
    advance: (Page) -> Long,
): List<Page> {
    if (limit <= 0) return emptyList()
    val out = ArrayList<Page>(limit)
    var cursor = startCursor
    while (out.size < limit) {
        val page = next(cursor) ?: break
        out += page
        cursor = advance(page)
    }
    return out
}

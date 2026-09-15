package com.llzx373.foldreader.feature.reader

import com.llzx373.foldreader.core.format.Chapter
import com.llzx373.foldreader.core.format.SearchHit

data class SearchHitGroup(
    val chapterIndex: Int,
    val title: String,
    val hits: List<SearchHit>,
)

/**
 * 搜索结果按章节分组：组序按书中章节顺序，组内按命中偏移升序（流式乱序到达也归组正确）；
 * 无目录书退化为单组「全文」。
 */
fun groupSearchHits(hits: List<SearchHit>, chapters: List<Chapter>): List<SearchHitGroup> {
    if (hits.isEmpty()) return emptyList()
    if (chapters.isEmpty()) {
        return listOf(SearchHitGroup(-1, "全文", hits.sortedBy { it.offset }))
    }
    return hits.groupBy { chapterIndexAt(chapters, it.offset) }
        .toSortedMap()
        .map { (index, groupHits) ->
            SearchHitGroup(
                chapterIndex = index,
                title = chapters.getOrNull(index)?.title.orEmpty().ifEmpty { "正文" },
                hits = groupHits.sortedBy { it.offset },
            )
        }
}

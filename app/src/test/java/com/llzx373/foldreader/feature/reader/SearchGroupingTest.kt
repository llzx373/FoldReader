package com.llzx373.foldreader.feature.reader

import com.llzx373.foldreader.core.format.Chapter
import com.llzx373.foldreader.core.format.SearchHit
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchGroupingTest {

    private val chapters = listOf(
        Chapter("第一章", 0L, 100L),
        Chapter("第二章", 100L, 250L),
        Chapter("第三章", 250L, 400L),
    )

    private fun hit(offset: Long) = SearchHit(
        offset = offset,
        context = "ctx$offset",
        matchStartInContext = 0,
        matchLength = 1,
    )

    @Test
    fun `hits in same chapter merge into one group`() {
        val groups = groupSearchHits(listOf(hit(10), hit(50), hit(120)), chapters)
        assertEquals(2, groups.size)
        assertEquals(0, groups[0].chapterIndex)
        assertEquals("第一章", groups[0].title)
        assertEquals(listOf(10L, 50L), groups[0].hits.map { it.offset })
        assertEquals(1, groups[1].chapterIndex)
        assertEquals(listOf(120L), groups[1].hits.map { it.offset })
    }

    @Test
    fun `groups follow book chapter order`() {
        val groups = groupSearchHits(listOf(hit(300), hit(10), hit(150)), chapters)
        assertEquals(listOf(0, 1, 2), groups.map { it.chapterIndex })
        assertEquals(listOf("第一章", "第二章", "第三章"), groups.map { it.title })
    }

    @Test
    fun `out-of-order streaming arrival groups correctly`() {
        // 流式乱序到达：组序仍按章节，组内按偏移升序
        val groups = groupSearchHits(listOf(hit(260), hit(50), hit(10), hit(255)), chapters)
        assertEquals(2, groups.size)
        assertEquals(0, groups[0].chapterIndex)
        assertEquals(listOf(10L, 50L), groups[0].hits.map { it.offset })
        assertEquals(2, groups[1].chapterIndex)
        assertEquals(listOf(255L, 260L), groups[1].hits.map { it.offset })
    }

    @Test
    fun `book without chapters degrades to single full-text group`() {
        val groups = groupSearchHits(listOf(hit(300), hit(10)), emptyList())
        assertEquals(1, groups.size)
        assertEquals(-1, groups[0].chapterIndex)
        assertEquals("全文", groups[0].title)
        assertEquals(listOf(10L, 300L), groups[0].hits.map { it.offset })
    }

    @Test
    fun `empty hits produce no groups`() {
        assertEquals(emptyList<SearchHitGroup>(), groupSearchHits(emptyList(), chapters))
        assertEquals(emptyList<SearchHitGroup>(), groupSearchHits(emptyList(), emptyList()))
    }
}

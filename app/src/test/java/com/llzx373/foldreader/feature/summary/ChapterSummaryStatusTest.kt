package com.llzx373.foldreader.feature.summary

import com.llzx373.foldreader.core.data.db.ChapterSummaryEntity
import com.llzx373.foldreader.core.format.Chapter
import com.llzx373.foldreader.core.translate.TranslationUnit
import com.llzx373.foldreader.core.translate.UnitKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterSummaryStatusTest {

    private val chapters = listOf(
        Chapter("第一章", 0, 100),
        Chapter("第二章", 100, 200),
        Chapter("第三章", 200, 300),
    )

    private val units = listOf(
        TranslationUnit(0, UnitKind.CHAPTER, "第一章", 0, 100),
        TranslationUnit(1, UnitKind.CHAPTER, "第二章", 100, 200),
        TranslationUnit(2, UnitKind.CHAPTER, "第三章", 200, 300),
    )

    private fun row(unitIndex: Int, status: String) = ChapterSummaryEntity(
        bookId = 7L, lang = "ZH_HANS", unitIndex = unitIndex, unitKind = "chapter",
        unitTitle = "章$unitIndex", status = status, summary = "", model = "m", updatedAt = 1L,
    )

    @Test
    fun `单位清单为空时全部无状态`() {
        val rows = chapterSummaryRows(chapters, emptyList(), emptyList())

        assertEquals(3, rows.size)
        rows.forEach {
            assertNull(it.label)
            assertFalse(it.viewable)
            assertFalse(it.generatable)
        }
    }

    @Test
    fun `无任何摘要行时全部未摘要可生成`() {
        val rows = chapterSummaryRows(chapters, units, emptyList())

        rows.forEach {
            assertEquals("未摘要", it.label)
            assertFalse(it.viewable)
            assertTrue(it.generatable)
        }
    }

    @Test
    fun `全部 done 显示已摘要可看不可生成`() {
        val rows = chapterSummaryRows(
            chapters, units,
            listOf(
                row(0, ChapterSummaryEntity.STATUS_DONE),
                row(1, ChapterSummaryEntity.STATUS_DONE),
                row(2, ChapterSummaryEntity.STATUS_DONE),
            ),
        )

        rows.forEach {
            assertEquals("已摘要", it.label)
            assertTrue(it.viewable)
            assertFalse(it.generatable)
        }
    }

    @Test
    fun `部分 done 显示进度且可看可生成`() {
        val rows = chapterSummaryRows(
            chapters, units,
            listOf(row(0, ChapterSummaryEntity.STATUS_DONE)),
        )

        assertEquals("已摘要", rows[0].label)
        assertEquals("未摘要", rows[1].label)
        // 块单位跨章时的部分覆盖：构造一章覆盖两个单位的情形
        val bigChapter = listOf(Chapter("大章", 0, 200))
        val partial = chapterSummaryRows(
            bigChapter, units,
            listOf(row(0, ChapterSummaryEntity.STATUS_DONE)),
        )
        assertEquals("已摘要 1/2", partial[0].label)
        assertTrue(partial[0].viewable)
        assertTrue(partial[0].generatable)
    }

    @Test
    fun `summarizing 优先于 failed 且不可重复生成`() {
        val rows = chapterSummaryRows(
            chapters, units,
            listOf(
                row(0, ChapterSummaryEntity.STATUS_SUMMARIZING),
                row(1, ChapterSummaryEntity.STATUS_FAILED),
            ),
        )

        assertEquals("摘要中", rows[0].label)
        assertFalse(rows[0].viewable)
        assertFalse(rows[0].generatable)
        assertEquals("失败", rows[1].label)
        assertFalse(rows[1].viewable)
        assertTrue(rows[1].generatable) // 失败可重新生成
    }
}

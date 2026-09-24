package com.llzx373.foldreader.feature.translate

import com.llzx373.foldreader.core.data.db.TranslationEntity
import com.llzx373.foldreader.core.format.Chapter
import com.llzx373.foldreader.core.translate.TranslationUnit
import com.llzx373.foldreader.core.translate.UnitKind
import org.junit.Assert.assertEquals
import org.junit.Test

/** 目录行「章 ↔ 单位」状态文案与重译判据。 */
class ChapterUnitStatusTest {

    private val chapters = listOf(
        Chapter(title = "第一章", charStart = 0, charEnd = 100),
        Chapter(title = "第二章", charStart = 100, charEnd = 300),
        Chapter(title = "第三章", charStart = 300, charEnd = 400),
    )
    private val units = listOf(
        TranslationUnit(0, UnitKind.CHAPTER, "第一章", 0, 100),
        TranslationUnit(1, UnitKind.BLOCK, "第二章 · 节 1", 100, 200),
        TranslationUnit(2, UnitKind.BLOCK, "第二章 · 节 2", 200, 300),
        TranslationUnit(3, UnitKind.CHAPTER, "第三章", 300, 400),
    )

    private fun row(unitIndex: Int, status: String) = TranslationEntity(
        bookId = 1, lang = "ZH_HANS", unitKind = "chapter", unitIndex = unitIndex,
        status = status, model = "m", paragraphCount = 1, updatedAt = 0,
    )

    @Test
    fun `没有单位时全部不标`() {
        assertEquals(listOf<String?>(null, null, null), chapterStatusLabels(chapters, emptyList(), emptyList()))
        assertEquals(listOf(false, false, false), chapterRetranslatable(chapters, emptyList(), emptyList()))
    }

    @Test
    fun `状态文案聚合规则`() {
        val rows = listOf(
            row(0, TranslationEntity.STATUS_DONE),
            row(1, TranslationEntity.STATUS_DONE),
            // 单位 2 未译（无行）
            row(3, TranslationEntity.STATUS_TRANSLATING),
        )
        assertEquals(
            listOf("已译", "已译 1/2", "翻译中"),
            chapterStatusLabels(chapters, units, rows),
        )
    }

    @Test
    fun `失败优先于部分已译`() {
        val rows = listOf(
            row(1, TranslationEntity.STATUS_DONE),
            row(2, TranslationEntity.STATUS_FAILED),
        )
        assertEquals(
            listOf("未译", "失败", "未译"),
            chapterStatusLabels(chapters, units, rows),
        )
    }

    @Test
    fun `重译判据只看 done 与 failed`() {
        val rows = listOf(
            row(0, TranslationEntity.STATUS_DONE),
            row(1, TranslationEntity.STATUS_TRANSLATING),
            row(3, TranslationEntity.STATUS_FAILED),
        )
        assertEquals(
            listOf(true, false, true),
            chapterRetranslatable(chapters, units, rows),
        )
    }

    @Test
    fun `块单位按区间相交归章`() {
        // 第二章 [100,300) 覆盖单位 1 与 2；第一章只覆盖单位 0
        assertEquals(
            listOf(0),
            unitsOfChapter(chapters[0], units).map { it.index },
        )
        assertEquals(
            listOf(1, 2),
            unitsOfChapter(chapters[1], units).map { it.index },
        )
    }
}

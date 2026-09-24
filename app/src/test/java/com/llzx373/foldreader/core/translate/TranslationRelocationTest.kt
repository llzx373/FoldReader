package com.llzx373.foldreader.core.translate

import com.llzx373.foldreader.core.format.Chapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 视角 1 重定位：单位号 + 单位内比例的双向映射。 */
class TranslationRelocationTest {

    private val units = listOf(
        TranslationUnit(0, UnitKind.CHAPTER, "一", 0, 100),
        TranslationUnit(1, UnitKind.CHAPTER, "二", 100, 300),
        TranslationUnit(2, UnitKind.BLOCK, "第 1 节", 300, 400),
    )
    private val translatedChapters = listOf(
        Chapter(title = "一", charStart = 0, charEnd = 50),
        Chapter(title = "二", charStart = 51, charEnd = 201),
        Chapter(title = "第 1 节", charStart = 202, charEnd = 252),
    )

    @Test
    fun `定位落在对应单位且比例正确`() {
        assertEquals(0 to 0.5f, locateUnit(units, 50))
        assertEquals(1 to 0.25f, locateUnit(units, 150))
        assertEquals(2 to 0f, locateUnit(units, 300))
    }

    @Test
    fun `越界 clamp 到首尾单位`() {
        assertEquals(0 to 0f, locateUnit(units, -5))
        // 越过文末：最后一个单位比例 1
        assertEquals(2 to 1f, locateUnit(units, 9999))
    }

    @Test
    fun `空表返回 null`() {
        assertNull(locateUnit(emptyList(), 10))
        assertNull(locateTranslatedUnit(emptyList(), 10))
    }

    @Test
    fun `单位边界偏移归到下一单位开头`() {
        // offset == 下一单位 charStart：属于下一单位比例 0
        assertEquals(1 to 0f, locateUnit(units, 100))
    }

    @Test
    fun `零长单位比例恒 0`() {
        val withEmpty = listOf(TranslationUnit(0, UnitKind.CHAPTER, "空", 10, 10))
        assertEquals(0 to 0f, locateUnit(withEmpty, 10))
        assertEquals(10L, offsetInOriginal(withEmpty, 0, 0.7f))
    }

    @Test
    fun `原文到译文双向定位保持单位与比例`() {
        // 原文 150 → 单位 1 比例 0.25 → 译本单位 1 [51,201) 的 25% 处
        val (unitIndex, fraction) = locateUnit(units, 150)!!
        assertEquals(51L + 37L, offsetInTranslated(translatedChapters, unitIndex, fraction))
    }

    @Test
    fun `译文到原文反向定位`() {
        // 译本 126 → 单位 1 [51,201) 比例 0.5 → 原文单位 1 [100,300) 的 50% 处 = 200
        val (unitIndex, fraction) = locateTranslatedUnit(translatedChapters, 126)!!
        assertEquals(1, unitIndex)
        assertEquals(0.5f, fraction, 0.01f)
        assertEquals(200L, offsetInOriginal(units, unitIndex, fraction))
    }

    @Test
    fun `比例 1 收在单位内最后一字符`() {
        assertEquals(299L, offsetInOriginal(units, 1, 1f))
        assertEquals(201L - 1L, offsetInTranslated(translatedChapters, 1, 1f))
    }

    @Test
    fun `单位号越界返回 0`() {
        assertEquals(0L, offsetInOriginal(units, 9, 0.5f))
        assertEquals(0L, offsetInTranslated(translatedChapters, -1, 0.5f))
    }
}

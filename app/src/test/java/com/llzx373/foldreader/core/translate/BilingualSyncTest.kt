package com.llzx373.foldreader.core.translate

import com.llzx373.foldreader.core.format.Chapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 视角 2 左右同步：追齐 / 漂移 / 边界 clamp。 */
class BilingualSyncTest {

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
    fun `左页跨入新单位时右页追齐到该单位起点`() {
        // 右侧停在单位 0，左页锚点进入单位 1 → 追到译本单位 1 起点
        val result = bilingualSyncTarget(units, translatedChapters, leftAnchor = 120, rightOffset = 10)!!
        assertTrue(result.caughtUp)
        assertEquals(51L, result.targetOffset)
    }

    @Test
    fun `左页回退到前一单位同样追齐`() {
        val result = bilingualSyncTarget(units, translatedChapters, leftAnchor = 50, rightOffset = 100)!!
        assertTrue(result.caughtUp)
        assertEquals(0L, result.targetOffset)
    }

    @Test
    fun `同单位内按比例漂移跟随`() {
        // 原文 150 → 单位 1 比例 0.25 → 译本单位 1 [51,201) 的 25% 处
        val result = bilingualSyncTarget(units, translatedChapters, leftAnchor = 150, rightOffset = 60)!!
        assertFalse(result.caughtUp)
        assertEquals(51L + 37L, result.targetOffset)
    }

    @Test
    fun `左页锚点越界 clamp 到首单位`() {
        val result = bilingualSyncTarget(units, translatedChapters, leftAnchor = -10, rightOffset = 10)!!
        // 右侧已在单位 0 → 同单位，比例 clamp 到 0 → 单位 0 起点
        assertFalse(result.caughtUp)
        assertEquals(0L, result.targetOffset)
    }

    @Test
    fun `左页越过文末 clamp 到尾单位末端`() {
        // 左锚点越界归到尾单位比例 1；右侧已在单位 2 → 漂移跟随，比例 1 收在区间内最后字符
        val result = bilingualSyncTarget(units, translatedChapters, leftAnchor = 9999, rightOffset = 220)!!
        assertFalse(result.caughtUp)
        assertEquals(251L, result.targetOffset)
    }

    @Test
    fun `右偏移越界时按单位不一致追齐`() {
        // rightOffset 越过译本末尾：clamp 到尾单位；左页在单位 0 → 单位不一致 → 追齐
        val result = bilingualSyncTarget(units, translatedChapters, leftAnchor = 20, rightOffset = 9999)!!
        assertTrue(result.caughtUp)
        assertEquals(0L, result.targetOffset)
    }

    @Test
    fun `空输入返回 null`() {
        assertNull(bilingualSyncTarget(emptyList(), translatedChapters, 10, 10))
        assertNull(bilingualSyncTarget(units, emptyList(), 10, 10))
        assertNull(bilingualSyncTarget(emptyList(), emptyList(), 10, 10))
    }

    @Test
    fun `连续翻页先追齐后漂移`() {
        // 模拟翻页序列：右页从 0 出发，左页进入单位 1 → 追齐到 51；
        // 之后右偏移以追齐结果为基准，左页同单位推进 → 漂移
        val first = bilingualSyncTarget(units, translatedChapters, 100, 0)!!
        assertTrue(first.caughtUp)
        assertEquals(51L, first.targetOffset)
        val second = bilingualSyncTarget(units, translatedChapters, 200, first.targetOffset)!!
        assertFalse(second.caughtUp)
        // 原文 200 → 单位 1 比例 0.5 → 译本单位 1 [51,201) 的 50% 处
        assertEquals(51L + 75L, second.targetOffset)
    }
}

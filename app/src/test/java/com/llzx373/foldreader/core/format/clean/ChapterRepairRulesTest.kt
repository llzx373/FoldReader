package com.llzx373.foldreader.core.format.clean

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterRepairRulesTest {

    @Test
    fun `常见章节标题形态识别`() {
        assertTrue(ChapterRepairRules.isTitle("第一章 风起"))
        assertTrue(ChapterRepairRules.isTitle("第12章 云涌"))
        assertTrue(ChapterRepairRules.isTitle("Chapter 3"))
        assertTrue(ChapterRepairRules.isTitle("楔子"))
        assertTrue(ChapterRepairRules.isTitle("番外一"))
    }

    @Test
    fun `带空格的标题不被内置规则识别但可规范化后识别`() {
        assertFalse(ChapterRepairRules.isTitle("第 1 章 初入江湖"))
        assertEquals("第1章 初入江湖", ChapterRepairRules.canonicalizeTitle("第 1 章 初入江湖"))
        assertTrue(ChapterRepairRules.isTitle(ChapterRepairRules.canonicalizeTitle("第 1 章 初入江湖")))
    }

    @Test
    fun `全角数字标题规范化为半角`() {
        assertEquals("第12章 风起", ChapterRepairRules.canonicalizeTitle("第１２章　风起"))
    }

    @Test
    fun `不擅自改写序号类型`() {
        assertEquals("第1章 风起", ChapterRepairRules.canonicalizeTitle("第1章 风起"))
        assertEquals("第一章 风起", ChapterRepairRules.canonicalizeTitle("第一章 风起"))
    }

    @Test
    fun `非标题行规范化后仍是原样`() {
        assertEquals("他停下脚步。", ChapterRepairRules.canonicalizeTitle("他停下脚步。"))
    }

    @Test
    fun `行中标题被切成正文与标题`() {
        val split = ChapterRepairRules.splitMidLineTitle("他站在原地，久久没有动。第一章 风起")
        assertEquals("他站在原地，久久没有动。" to "第一章 风起", split)
    }

    @Test
    fun `正文过短时不切分`() {
        assertNull(ChapterRepairRules.splitMidLineTitle("他翻到第一章"))
        assertNull(ChapterRepairRules.splitMidLineTitle("看第一章 风起"))
    }

    @Test
    fun `标题不在行尾时不切分`() {
        assertNull(ChapterRepairRules.splitMidLineTitle("他站在原地，久久没有动。第一章 风起他又走了两步。"))
    }

    @Test
    fun `本身就是标题的行不切分`() {
        assertNull(ChapterRepairRules.splitMidLineTitle("第一章 风起"))
    }

    @Test
    fun `目录表头识别`() {
        assertTrue(ChapterRepairRules.isTocHeader("目录"))
        assertTrue(ChapterRepairRules.isTocHeader("目 录"))
        assertFalse(ChapterRepairRules.isTocHeader("第一章 风起"))
    }
}

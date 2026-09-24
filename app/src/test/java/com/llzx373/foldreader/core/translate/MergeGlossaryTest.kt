package com.llzx373.foldreader.core.translate

import org.junit.Assert.assertEquals
import org.junit.Test

/** 术语合并的优先级与去重规则（R6：书 > 系列 > 全局，同 source 高层级覆盖低层级）。 */
class MergeGlossaryTest {

    @Test
    fun `同 source 书覆盖系列覆盖全局`() {
        val merged = mergeGlossary(
            book = listOf("张三" to "Zhang San"),
            series = listOf("张三" to "San Zhang", "李四" to "Li Si-series"),
            global = listOf("李四" to "Li Si", "王五" to "Wang Wu"),
        )
        assertEquals(
            listOf("张三" to "Zhang San", "李四" to "Li Si-series", "王五" to "Wang Wu"),
            merged,
        )
    }

    @Test
    fun `低层级独有词条保留`() {
        val merged = mergeGlossary(
            book = emptyList(),
            series = emptyList(),
            global = listOf("魔法" to "magic"),
        )
        assertEquals(listOf("魔法" to "magic"), merged)
    }

    @Test
    fun `空 source 或空 target 的行被丢弃`() {
        val merged = mergeGlossary(
            book = listOf("" to "x", "甲" to ""),
            series = listOf("甲" to "A"),
            global = emptyList(),
        )
        assertEquals(listOf("甲" to "A"), merged)
    }

    @Test
    fun `返回顺序为书系列全局且稳定`() {
        val merged = mergeGlossary(
            book = listOf("b1" to "B1", "b2" to "B2"),
            series = listOf("s1" to "S1"),
            global = listOf("g1" to "G1", "g2" to "G2"),
        )
        assertEquals(
            listOf("b1" to "B1", "b2" to "B2", "s1" to "S1", "g1" to "G1", "g2" to "G2"),
            merged,
        )
    }

    @Test
    fun `全空输入得空表`() {
        assertEquals(emptyList<Pair<String, String>>(), mergeGlossary(emptyList(), emptyList(), emptyList()))
    }
}

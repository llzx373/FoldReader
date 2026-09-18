package com.llzx373.foldreader.core.format.clean

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WhitespaceRulesTest {

    @Test
    fun `行尾空白去除`() {
        assertEquals("他停下脚步。", WhitespaceRules.trimTrailing("他停下脚步。   "))
    }

    @Test
    fun `全空白行归一为空串`() {
        assertEquals("", WhitespaceRules.collapseRuns("   "))
        assertEquals("", WhitespaceRules.collapseRuns(" "))
    }

    @Test
    fun `行内连续空白折叠为一个`() {
        assertEquals("hello world", WhitespaceRules.collapseRuns("hello    world"))
        assertEquals("他说 hello world", WhitespaceRules.collapseRuns("他   说   hello   world"))
    }

    @Test
    fun `中文字符之间的空格删除`() {
        assertEquals("他停下脚步，看着远方。", WhitespaceRules.collapseRuns("他停下脚步， 看着远方。"))
        assertEquals("他说：“你来了。”", WhitespaceRules.collapseRuns("他 说：“你 来了。”"))
    }

    @Test
    fun `拉丁词句之间的空格保留`() {
        val input = "他说：I love this city"
        assertEquals(input, WhitespaceRules.collapseRuns(input))
    }

    @Test
    fun `段首空白折叠为一个空格以保留缩进信号`() {
        assertEquals(" 他停下脚步。", WhitespaceRules.collapseRuns("\u3000\u3000\u3000他停下脚步。"))
        assertEquals(" 他停下脚步。", WhitespaceRules.collapseRuns("   他停下脚步。"))
    }

    @Test
    fun `制表符与全角空格同样参与折叠`() {
        assertEquals("hello world", WhitespaceRules.collapseRuns("hello\t\tworld"))
        assertEquals("他停下脚步，看着远方。", WhitespaceRules.collapseRuns("他停下脚步，\u3000看着远方。"))
    }

    @Test
    fun `段首缩进统一为两个全角空格`() {
        assertEquals("\u3000\u3000他停下脚步。", WhitespaceRules.canonicalizeIndent(" 他停下脚步。"))
        assertEquals("\u3000\u3000他停下脚步。", WhitespaceRules.canonicalizeIndent("    他停下脚步。"))
    }

    @Test
    fun `无缩进的行不被加上缩进`() {
        val input = "他停下脚步。"
        assertEquals(input, WhitespaceRules.canonicalizeIndent(input))
    }
}

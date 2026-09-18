package com.llzx373.foldreader.core.format.clean

import org.junit.Assert.assertEquals
import org.junit.Test

class BlankLineRulesTest {

    @Test
    fun `两侧带缩进时空行全部删除`() {
        assertEquals(0, BlankLineRules.resolve(1, indentedContext = true, atStart = false, atEnd = false))
        assertEquals(0, BlankLineRules.resolve(5, indentedContext = true, atStart = false, atEnd = false))
    }

    @Test
    fun `两侧都无缩进时连续空行收敛为一个`() {
        assertEquals(1, BlankLineRules.resolve(1, indentedContext = false, atStart = false, atEnd = false))
        assertEquals(1, BlankLineRules.resolve(4, indentedContext = false, atStart = false, atEnd = false))
    }

    @Test
    fun `全文开头与结尾的空行一律删除`() {
        assertEquals(0, BlankLineRules.resolve(3, indentedContext = false, atStart = true, atEnd = false))
        assertEquals(0, BlankLineRules.resolve(3, indentedContext = false, atStart = false, atEnd = true))
    }

    @Test
    fun `没有空行时保留零个`() {
        assertEquals(0, BlankLineRules.resolve(0, indentedContext = false, atStart = false, atEnd = false))
    }
}

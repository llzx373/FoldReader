package com.llzx373.foldreader.core.format.clean

import org.junit.Assert.assertEquals
import org.junit.Test

class BlankLineRulesTest {

    private val indented = "\u3000\u3000他停下脚步。"
    private val flush = "他停下脚步。"
    private val title = "第二章 云涌"

    @Test
    fun `下一行有缩进时空行删除`() {
        assertEquals(0, BlankLineRules.resolve(1, flush, indented))
        assertEquals(0, BlankLineRules.resolve(5, flush, indented))
    }

    @Test
    fun `下一行顶格且不是标题时保留一个`() {
        assertEquals(1, BlankLineRules.resolve(1, flush, flush))
        assertEquals(1, BlankLineRules.resolve(4, flush, flush))
        assertEquals(1, BlankLineRules.resolve(3, indented, "【第二篇】作者：某某"))
    }

    @Test
    fun `缩进分段时标题前的空行也算冗余`() {
        assertEquals(0, BlankLineRules.resolve(1, indented, title))
        assertEquals(0, BlankLineRules.resolve(1, indented, "第 2 章 风起云涌"))
    }

    @Test
    fun `空行分段的书不动标题前的空行`() {
        assertEquals(1, BlankLineRules.resolve(1, flush, title))
    }

    @Test
    fun `全文开头与结尾的空行一律删除`() {
        assertEquals(0, BlankLineRules.resolve(3, null, flush))
        assertEquals(0, BlankLineRules.resolve(3, flush, null))
    }

    @Test
    fun `没有空行时保留零个`() {
        assertEquals(0, BlankLineRules.resolve(0, flush, flush))
    }
}

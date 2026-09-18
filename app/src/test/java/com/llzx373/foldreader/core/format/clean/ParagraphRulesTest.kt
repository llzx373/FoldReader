package com.llzx373.foldreader.core.format.clean

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParagraphRulesTest {

    @Test
    fun `以句末标点结尾的行不合并`() {
        assertFalse(merge("他停下脚步。", "风从窗外吹进来。"))
        assertFalse(merge("他说：“你好。”", "她点了点头。"))
    }

    @Test
    fun `下一行有缩进时不合并`() {
        assertFalse(merge("他停下脚步，", " 风从窗外吹进来。"))
        // 全角缩进同样算缩进（unifyChars 关闭时它还没被转成半角空格）
        assertFalse(merge("他停下脚步，", "\u3000风从窗外吹进来。"))
    }

    @Test
    fun `章节标题不参与合并`() {
        assertFalse(merge("第一章 风起", "他睁开眼睛。"))
        // `第 2 章` 带空格，要先规范化才认得出，段落重组也得认（否则标题会被并进正文）
        assertFalse(merge("他停下脚步，", "第 2 章 风起云涌"))
        assertFalse(merge("第二章 云涌", "他睁开眼睛。"))
    }

    @Test
    fun `长行且无句末标点时合并`() {
        val prev = "夜色像一张巨大的网，悄无声息地笼罩了整座城市，"
        assertTrue(merge(prev, "街道上的行人渐渐稀少。"))
    }

    @Test
    fun `短行且无句末标点时不合并`() {
        assertFalse(merge("他笑了", "她也笑了"))
    }

    @Test
    fun `短行以续行标点结尾时合并`() {
        assertTrue(merge("风从山谷里吹过来，", "带着草叶的气息。"))
    }

    @Test
    fun `引号未闭合时强制合并`() {
        val prev = "他抬起头，轻声说道：“你还"
        assertTrue(merge(prev, "记得那年冬天的事吗？”"))
        assertTrue(ParagraphRules.hasUnclosedQuote(prev))
        assertFalse(ParagraphRules.hasUnclosedQuote("他说：“你好。”"))
    }

    @Test
    fun `对话引号起头不合并`() {
        assertFalse(merge("他停下脚步，", "“你来了。”他说。"))
    }

    @Test
    fun `一段一行时允许跨一个空行并`() {
        val prev = "他停下脚步，看着远方，心里想着那些"
        assertTrue(merge(prev, "年前的事，一时竟说不出话来。", blanks = 1))
        assertFalse(merge(prev, "年前的事，一时竟说不出话来。", blanks = 2))
    }

    @Test
    fun `段落用缩进起头时不看标点直接并回`() {
        // 引号闭合了也不代表这段话结束，后面还有正文（真实文件里的形态）
        assertTrue(
            ParagraphRules.shouldMerge(
                "『原来你早就知道了。』",
                "他沉默着没有回答",
                paragraphIndented = true,
                blankLinesBetween = 0,
            ),
        )
        assertTrue(
            ParagraphRules.shouldMerge("他停下脚步。", "风从窗外吹进来。", paragraphIndented = true, blankLinesBetween = 0),
        )
    }

    @Test
    fun `缩进起头时空行必然结束一段`() {
        // 顶格的分篇标题就是「空行 + 顶格」；而且这条在空行折叠前后都成立 → 幂等
        assertFalse(
            ParagraphRules.shouldMerge(
                "他停下脚步。",
                "【第二篇】作者：某某",
                paragraphIndented = true,
                blankLinesBetween = 1,
            ),
        )
    }

    @Test
    fun `缩进起头的段落也不会吞掉有缩进的下一段`() {
        assertFalse(
            ParagraphRules.shouldMerge(
                "他停下脚步。",
                "\u3000风从窗外吹进来。",
                paragraphIndented = true,
                blankLinesBetween = 0,
            ),
        )
    }

    @Test
    fun `缩进起头的段落也不吞章节标题`() {
        assertFalse(
            ParagraphRules.shouldMerge(
                "他停下脚步。",
                "第二章 云涌",
                paragraphIndented = true,
                blankLinesBetween = 0,
            ),
        )
    }

    /** 默认「无缩进起头、两行紧挨」。 */
    private fun merge(prev: String, next: String, blanks: Int = 0): Boolean =
        ParagraphRules.shouldMerge(prev, next, paragraphIndented = false, blankLinesBetween = blanks)

    @Test
    fun `join 中文直接相接`() {
        assertEquals("他停下脚步，看着远方。", ParagraphRules.join("他停下脚步，", "看着远方。"))
    }

    @Test
    fun `join 拉丁词之间补空格`() {
        assertEquals("hello world", ParagraphRules.join("hello", "world"))
    }

    @Test
    fun `join 去掉下一行段首缩进`() {
        assertEquals("他停下脚步，看着远方。", ParagraphRules.join("他停下脚步，", "  看着远方。"))
    }
}

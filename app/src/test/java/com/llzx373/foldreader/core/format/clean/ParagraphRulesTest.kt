package com.llzx373.foldreader.core.format.clean

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParagraphRulesTest {

    @Test
    fun `以句末标点结尾的行不合并`() {
        assertFalse(ParagraphRules.shouldMerge("他停下脚步。", "风从窗外吹进来。"))
        assertFalse(ParagraphRules.shouldMerge("他说：“你好。”", "她点了点头。"))
    }

    @Test
    fun `下一行有缩进时不合并`() {
        assertFalse(ParagraphRules.shouldMerge("他停下脚步，", " 风从窗外吹进来。"))
    }

    @Test
    fun `章节标题不参与合并`() {
        assertFalse(ParagraphRules.shouldMerge("第一章 风起", "他睁开眼睛。"))
        assertFalse(ParagraphRules.shouldMerge("他停下脚步，", "第二章 云涌"))
    }

    @Test
    fun `长行且无句末标点时合并`() {
        val prev = "夜色像一张巨大的网，悄无声息地笼罩了整座城市，"
        assertTrue(ParagraphRules.shouldMerge(prev, "街道上的行人渐渐稀少。"))
    }

    @Test
    fun `短行且无句末标点时不合并`() {
        assertFalse(ParagraphRules.shouldMerge("他笑了", "她也笑了"))
    }

    @Test
    fun `短行以续行标点结尾时合并`() {
        assertTrue(ParagraphRules.shouldMerge("风从山谷里吹过来，", "带着草叶的气息。"))
    }

    @Test
    fun `引号未闭合时强制合并`() {
        val prev = "他抬起头，轻声说道：“你还"
        assertTrue(ParagraphRules.shouldMerge(prev, "记得那年冬天的事吗？”"))
        assertTrue(ParagraphRules.hasUnclosedQuote(prev))
        assertFalse(ParagraphRules.hasUnclosedQuote("他说：“你好。”"))
    }

    @Test
    fun `对话引号起头不合并`() {
        assertFalse(ParagraphRules.shouldMerge("他停下脚步，", "“你来了。”他说。"))
    }

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

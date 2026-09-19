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

    /**
     * 真实文件形态：合集里分篇标题**顶格**写，正文也**顶格**写（不是缩进分段）。
     * 上一行是标题、不以句末标点收尾、又超过 16 字，标点判据下会被判成「长行硬换行」而并掉。
     */
    @Test
    fun `顶格分篇标题不被并进下一段`() {
        assertFalse(
            merge(
                "【外传·某某某篇】（某某某篇）作者：某某某某某",
                "天色暗了下来，街上静悄悄的，",
                blanks = 1,
            ),
        )
    }

    @Test
    fun `顶格分篇标题也不吞掉下一段`() {
        // 对照：同样位置换成普通长行，照常合并
        assertTrue(merge("夜色像一张巨大的网，悄无声息地笼罩了整座城市，", "街道上的行人渐渐稀少。"))
        assertFalse(merge("夜色像一张巨大的网，悄无声息地笼罩了整座城市，", "【第二篇】作者：某某"))
        // 缩进起头的段落同样不能吞掉顶格分篇标题
        assertFalse(
            ParagraphRules.shouldMerge(
                "他停下脚步。",
                "【第二篇】作者：某某",
                paragraphIndented = true,
                blankLinesBetween = 0,
            ),
        )
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

    /**
     * 「每一行都带缩进」的排版版（下载站精校版：全篇每行缩进、行间还留空行）。
     * 这种文件里缩进不是段落信号，只能看上一行有没有说完话，空行也不作数。
     */
    @Test
    fun `每行都缩进的排版版里，没说完的行照样并回`() {
        assertTrue(
            ParagraphRules.shouldMerge(
                "第一段开头在这里被硬换行切断，后面还有",
                "\u3000一句接着往下说，这一行同样带着缩进，",
                paragraphIndented = true,
                blankLinesBetween = 1,
            ),
        )
        // 引号没闭合时更要并
        assertTrue(
            ParagraphRules.shouldMerge(
                "\u3000他抬起头，轻声说道：“你还",
                "\u3000记得那年冬天的事吗？”",
                paragraphIndented = true,
                blankLinesBetween = 1,
            ),
        )
    }

    @Test
    fun `每行都缩进时，收尾的行仍按新段落算`() {
        // 这是这条新判据的安全边界：正常书里段落一定以句末标点收尾，因此行为不变
        assertFalse(
            ParagraphRules.shouldMerge(
                "\u3000第二段只有一行，以句末标点结尾。",
                "\u3000第三段开头。",
                paragraphIndented = true,
                blankLinesBetween = 1,
            ),
        )
        // 短行不认定为「被硬换行切断」——即便每行都缩进也不并
        assertFalse(
            ParagraphRules.shouldMerge(
                "\u3000他停下脚步，",
                "\u3000风从窗外吹进来。",
                paragraphIndented = true,
                blankLinesBetween = 1,
            ),
        )
    }

    @Test
    fun `每行都缩进时，引号起头的下一行仍是新段落`() {
        // 上一段结尾漏了标点很常见，但下一行是对话开头——不能被吞掉
        // （真实文件上实测到过这一处误并，才补的守卫）
        assertFalse(
            ParagraphRules.shouldMerge(
                "\u3000第四段这里没有句末标点收尾，但它确实够长，",
                "\u3000『引号起头的新一段』",
                paragraphIndented = true,
                blankLinesBetween = 1,
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

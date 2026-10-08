package com.llzx373.foldreader.core.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterScannerTest {

    private fun scanAll(text: String, chunkSize: Int = Int.MAX_VALUE): List<Chapter> {
        val scanner = ChapterScanner()
        var i = 0
        while (i < text.length) {
            val end = minOf(i + chunkSize, text.length)
            scanner.feed(text.substring(i, end))
            i = end
        }
        if (text.isEmpty()) scanner.feed("")
        return scanner.finish()
    }

    @Test
    fun `常见网文章节格式全部命中`() {
        val text = buildString {
            append("卷首语：写在前面。\n")
            append("第一章 重生\n")
            append("正文里提到第一章节三个字不应误伤。\n")
            append("第1章 再出发\n")
            append("一些内容。\n")
            append("第两百章 终局之战\n")
            append("更多内容。\n")
            append("Chapter 12 Extra Story\n")
            append("English content.\n")
            append("12、纯数字分卷\n")
            append("最后的内容。")
        }
        val chapters = scanAll(text)

        assertEquals(
            listOf("卷首", "第一章 重生", "第1章 再出发", "第两百章 终局之战", "Chapter 12 Extra Story", "12、纯数字分卷"),
            chapters.map { it.title },
        )
    }

    @Test
    fun `章节偏移连续且与原文对齐`() {
        val text = buildString {
            append("前言废话两行\n第二行前言。\n")
            append("第一章 开始\n")
            append("正文字符。\n")
            append("第二章 继续\n")
            append("更多正文。")
        }
        val chapters = scanAll(text)

        assertEquals(3, chapters.size)
        assertEquals(0L, chapters.first().charStart)
        assertEquals(text.length.toLong(), chapters.last().charEnd)
        chapters.zipWithNext().forEach { (a, b) -> assertEquals(a.charEnd, b.charStart) }
        chapters.drop(1).forEach { chapter ->
            assertEquals(chapter.title, text.substring(chapter.charStart.toInt(), chapter.charEnd.toInt()).lines().first())
        }
    }

    @Test
    fun `正文首章前内容归入卷首`() {
        val text = "一些没有标题的引言。\n第一章 正文开始\n内容。"
        val chapters = scanAll(text)

        assertEquals("卷首", chapters.first().title)
        assertEquals(0L, chapters.first().charStart)
        assertEquals(chapters[1].charStart, chapters.first().charEnd)
    }

    @Test
    fun `无章节文本降级为单章全文`() {
        val text = "这只是一段普通文本。\n没有任何章节标题，第二行也一样。\n第三行。"
        val chapters = scanAll(text)

        assertEquals(1, chapters.size)
        assertEquals("全文", chapters[0].title)
        assertEquals(0L, chapters[0].charStart)
        assertEquals(text.length.toLong(), chapters[0].charEnd)
    }

    @Test
    fun `跨块喂入与整体喂入结果一致`() {
        val text = buildString {
            append("第一章 跨块测试\n")
            append("很长的正文".repeat(200) + "\n")
            append("第3章 标题被切碎在第\n")
            append("第二章 又来了\n")
            append("结尾")
        }
        val whole = scanAll(text)
        val byTinyChunks = scanAll(text, chunkSize = 7)

        assertEquals(whole, byTinyChunks)
        assertEquals(listOf("第一章 跨块测试", "第3章 标题被切碎在第", "第二章 又来了"), whole.map { it.title })
    }

    @Test
    fun `超长行不误判为章节`() {
        val longLine = "第三章" + "很长的标题行".repeat(20)
        val text = "$longLine\n正文内容。\n第一章 短标题\n内容。"
        val chapters = scanAll(text)

        assertTrue(chapters.none { it.title.startsWith("第三章") })
        assertEquals("第一章 短标题", chapters.last().title)
    }

    @Test
    fun `章节编号带空格的变形也能命中`() {
        val text = buildString {
            append("第 1 章 半角空格\n")
            append("正文。\n")
            append("第　2　章 全角空格\n")
            append("正文。\n")
            append("　第3章 行首全角缩进　\n")
            append("正文。\n")
            append("第 两百 章 编号中间有空格\n")
            append("正文。\n")
            append("第 3 天他离开了，这不是标题。\n")
            append("结尾。")
        }
        val chapters = scanAll(text)

        assertEquals(
            listOf(
                "第 1 章 半角空格",
                "第　2　章 全角空格",
                "第3章 行首全角缩进",
                "第 两百 章 编号中间有空格",
            ),
            chapters.map { it.title },
        )
    }

    @Test
    fun `大写数字编号与特别番外命中`() {
        val text = buildString {
            append("番外#1：黑牢，木马，与公共便器\n")
            append("正文内容。\n")
            append("特别番外: 马车，走绳，与烟火大会\n")
            append("正文内容。\n")
            append("零. 楔子\n")
            append("正文。\n")
            append("壹. 密室\n")
            append("正文。\n")
            append("贰拾叁. 夜袭\n")
            append("正文。\n")
            append("叁. 长夜（上）\n")
            append("结尾。")
        }
        val chapters = scanAll(text)

        assertEquals(
            listOf("番外#1：黑牢，木马，与公共便器", "特别番外: 马车，走绳，与烟火大会", "零. 楔子", "壹. 密室", "贰拾叁. 夜袭", "叁. 长夜（上）"),
            chapters.map { it.title },
        )
    }

    @Test
    fun `大写数字正文行不误判为章节`() {
        val text = buildString {
            append("第一章 开始\n")
            append("四十\n")
            append("三十九\n")
            append("拾金不昧是好事。\n")
            append("零。零散的数字没有分隔符。\n")
            append("一、小写数字枚举不收。\n")
            append("结尾。")
        }
        val chapters = scanAll(text)

        assertEquals(listOf("第一章 开始"), chapters.map { it.title })
    }

    @Test
    fun `自定义规则命中默认规则不识别的标题`() {
        val rules = ChapterRules.merge(listOf("^【.+】$"))
        val scanner = ChapterScanner(rules)
        scanner.feed("引言行。\n【第一回 风起云涌】\n正文。\n【第二回 再战】\n结尾。")
        val chapters = scanner.finish()

        assertEquals(listOf("卷首", "【第一回 风起云涌】", "【第二回 再战】"), chapters.map { it.title })
    }

    @Test
    fun `自定义规则排在默认规则之前`() {
        val merged = ChapterRules.merge(listOf("^【.+】$", "^卷 \\d+"))

        assertEquals(ChapterRules.DEFAULT.size + 2, merged.size)
        assertEquals("^【.+】$", merged[0].pattern)
        assertEquals("^卷 \\d+", merged[1].pattern)
        assertEquals(ChapterRules.DEFAULT, merged.drop(2))
    }

    @Test
    fun `非法自定义正则被跳过`() {
        assertEquals(ChapterRules.DEFAULT, ChapterRules.merge(listOf("(", "[")))
    }

    @Test
    fun `命中标题经回调上报规则序号`() {
        val hits = mutableListOf<Triple<Long, String, Int>>()
        // 自定义规则排在内置规则之前，所以「第N章」命中的是序号 1
        val scanner = ChapterScanner(ChapterRules.merge(listOf("^【.+】$"))) { offset, title, ruleIndex ->
            hits += Triple(offset, title, ruleIndex)
        }
        scanner.feed("【第一回 起】\n正文。\n第二章 落\n")
        scanner.finish()

        assertEquals(
            listOf(Triple(0L, "【第一回 起】", 0), Triple(12L, "第二章 落", 1)),
            hits,
        )
    }
}

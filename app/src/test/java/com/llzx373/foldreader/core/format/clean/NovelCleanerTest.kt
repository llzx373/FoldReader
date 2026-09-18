package com.llzx373.foldreader.core.format.clean

import java.io.BufferedReader
import java.io.StringReader
import java.io.StringWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelCleanerTest {

    private val standard = CleanProfile(level = CleanLevel.STANDARD)
    private val conservative = CleanProfile(level = CleanLevel.CONSERVATIVE)

    @Test
    fun `无操作档位恒等返回原文实例`() {
        val text = "第一行\n\n廣告行\n"
        assertSame(text, NovelCleaner.clean(text, CleanProfile.NONE))
    }

    @Test
    fun `流式版本与整段版本结果一致`() {
        val text = "他停下脚步，　看着远方。\n\n请记住本站域名 www.example.com\n他说：“你　来了。”\n"
        val writer = StringWriter()
        NovelCleaner.cleanStream(BufferedReader(StringReader(text)), writer, standard)
        assertEquals(NovelCleaner.clean(text, standard), writer.toString())
    }

    @Test
    fun `CRLF 输入被归一为 LF`() {
        val text = "他停下脚步。\r\n她回过头来看他。\r\n"
        assertEquals("他停下脚步。\n她回过头来看他。\n", NovelCleaner.clean(text, standard))
    }

    @Test
    fun `单独 CR 作为换行符也能处理`() {
        val text = "他停下脚步。\r她回过头来看他。"
        assertEquals("他停下脚步。\n她回过头来看他。\n", NovelCleaner.clean(text, standard))
    }

    @Test
    fun `同一输入清洗两次结果不变`() {
        val text = buildString {
            appendLine("第 1 章　初入江湖")
            appendLine()
            appendLine("　　他停下脚步，　看着远方，心里想着那")
            appendLine("些年前在山中相遇的旧事......")
            appendLine()
            appendLine("请记住本站域名 www.example.com")
            appendLine("　　“你　终于来了。”他说道。")
            appendLine()
            appendLine("☆★☆★☆★☆")
        }
        val once = NovelCleaner.clean(text, standard)
        assertEquals(once, NovelCleaner.clean(once, standard))
    }

    @Test
    fun `报告记录删除的噪音行数与进出总行数`() {
        val text = "请记住本站域名 www.example-novel.com\n他停下脚步，　看着远方。\n"
        val report = NovelCleaner.preview(text, standard)
        assertEquals(2, report.linesIn)
        assertEquals(1, report.linesOut)
        assertEquals(1, report.removedNoiseLines)
        assertTrue(report.changed)
        assertTrue(report.summary().contains("删除广告/噪音 1 行"))
    }

    @Test
    fun `预览返回改动样例且不动原文`() {
        val text = "他站在原地......\n请记住本站域名 www.example-novel.com\n"
        val report = NovelCleaner.preview(text, standard)
        assertTrue(report.samples.isNotEmpty())
        assertTrue(report.changed)
        // 样例是副本，原文是 String 值类型，这里确认 preview 不会写出任何文件/改动入参内容
        assertEquals("他站在原地......\n请记住本站域名 www.example-novel.com\n", text)
    }

    @Test
    fun `保守档不合并硬换行`() {
        val text = "他停下脚步，　看着远方，心里想着那\n些年前在山中相遇的旧事。\n"
        val cleaned = NovelCleaner.clean(text, conservative)
        assertEquals(2, cleaned.trimEnd('\n').split('\n').size)
    }

    @Test
    fun `标准档合并硬换行`() {
        val text = "他停下脚步，　看着远方，心里想着那\n些年前在山中相遇的旧事。\n"
        val cleaned = NovelCleaner.clean(text, standard)
        assertEquals(1, cleaned.trimEnd('\n').split('\n').size)
    }

    @Test
    fun `繁简转换按字表生效`() {
        val toggles = CleanToggles.NONE.copy(traditionalToSimplified = true)
        val profile = CleanProfile(level = CleanLevel.CUSTOM, toggles = toggles)
        val map = mapOf('體' to '体', '學' to '学', '習' to '习')
        assertEquals("身体学习\n", NovelCleaner.clean("身體學習\n", profile, map))
    }

    @Test
    fun `空输入清洗后仍为空`() {
        assertEquals("", NovelCleaner.clean("", standard))
    }

    @Test
    fun `只有空行的输入清洗后为空`() {
        assertEquals("", NovelCleaner.clean("\n\n\n", standard))
    }

    @Test
    fun `没有改动时报告 changed 为假`() {
        val text = "他停下脚步，看着远方。\n风从窗外吹进来。\n她站在门口，没有作声。\n"
        val report = NovelCleaner.preview(text, standard)
        assertFalse(report.changed)
        assertEquals("没有需要整理的内容", report.summary())
    }

    /**
     * 顺序敏感的组合：全角缩进、行中标题提行、章节标题规范化、段首缩进统一，
     * 四个阶段的先后关系同时生效。
     *
     * 这份用例是给「管线顺序」兜底的——`buildPipeline` 现在按数据流顺序列出各阶段，
     * 谁要是不小心把顺序搞反（历史上就是这么错的），这里会立刻红。
     */
    @Test
    fun `各阶段的先后关系同时生效`() {
        val text = "　　他站在原地，久久没有动。第一章 风起\n他睁开眼睛。\n"

        assertEquals(
            // 正文侧保留原文的段首缩进（提行时带上），标题顶格且被规范化
            "　　他站在原地，久久没有动。\n第一章 风起\n他睁开眼睛。\n",
            NovelCleaner.clean(text, standard),
        )
    }
}

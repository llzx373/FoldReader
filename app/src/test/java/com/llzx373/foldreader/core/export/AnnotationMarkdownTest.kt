package com.llzx373.foldreader.core.export

import com.llzx373.foldreader.core.data.db.AnnotationEntity
import com.llzx373.foldreader.core.format.Chapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnotationMarkdownTest {

    private val chapters = listOf(
        Chapter(title = "第一章 起", charStart = 0, charEnd = 1000),
        Chapter(title = "第二章 承", charStart = 1000, charEnd = 2000),
    )

    private fun textAnn(
        start: Long,
        end: Long = start + 10,
        text: String = "选中的原文",
        color: Long = 0xFFFFF176,
        note: String? = null,
        style: String = AnnotationEntity.STYLE_HIGHLIGHT,
    ) = AnnotationEntity(
        bookId = 1,
        startCharOffset = start,
        endCharOffset = end,
        selectedText = text,
        color = color,
        note = note,
        style = style,
        createdAt = 0,
        updatedAt = 0,
    )

    private fun pageAnn(
        page: Long,
        x: Float = 0.12f,
        y: Float = 0.34f,
        w: Float = 0.56f,
        h: Float = 0.08f,
        text: String = "",
    ) = AnnotationEntity(
        bookId = 1,
        startCharOffset = 0,
        endCharOffset = 0,
        selectedText = text,
        color = 0xFF90CAF9,
        note = null,
        createdAt = 0,
        updatedAt = 0,
        pageIndex = page,
        regionX = x,
        regionY = y,
        regionW = w,
        regionH = h,
    )

    private fun render(
        annotations: List<AnnotationEntity>,
        chapters: List<Chapter> = this.chapters,
        options: AnnotationExportOptions = AnnotationExportOptions(),
        totalChars: Long = 0,
    ) = AnnotationMarkdown.render(
        bookTitle = "三体",
        author = "刘慈欣",
        annotations = annotations,
        chapters = chapters,
        options = options,
        exportedAtMs = 0L,
        totalChars = totalChars,
    )

    @Test
    fun `头部含书名作者导出时间与批注计数`() {
        val md = render(listOf(textAnn(10, note = "想法"), textAnn(20)))
        assertTrue(md.startsWith("# 《三体》批注\n"))
        assertTrue(md.contains("- 作者：刘慈欣\n"))
        assertTrue(md.contains("- 导出时间：1970-01-01 "))
        assertTrue(md.contains("- 批注数量：2 条（其中 1 条带笔记）"))
    }

    @Test
    fun `文本批注按章节分组且组内按偏移升序`() {
        val md = render(listOf(textAnn(1500), textAnn(30), textAnn(1200)))
        val firstChapter = md.indexOf("## 第一章 起")
        val secondChapter = md.indexOf("## 第二章 承")
        assertTrue(firstChapter in 1 until secondChapter)
        // 第二章内 1200 在 1500 之前
        assertTrue(md.indexOf("第 1200 字符起") < md.indexOf("第 1500 字符起"))
        // 首章之前的标注落第 0 章（与批注列表 UI 同口径）
        assertTrue(md.indexOf("第 30 字符起") in firstChapter until secondChapter)
    }

    @Test
    fun `多行原文进引用块且逐行加前缀`() {
        val md = render(listOf(textAnn(10, text = "第一行\r\n第二行\n\n# 行首井号")))
        assertTrue(md.contains("> 第一行\n> 第二行\n>\n> # 行首井号\n"))
    }

    @Test
    fun `笔记开关控制笔记块去留`() {
        val withNotes = render(listOf(textAnn(10, note = "第一行想法\n第二行想法")))
        assertTrue(withNotes.contains("- 笔记：\n  第一行想法\n  第二行想法\n"))
        val withoutNotes = render(
            listOf(textAnn(10, note = "想法")),
            options = AnnotationExportOptions(includeNotes = false),
        )
        assertFalse(withoutNotes.contains("- 笔记："))
        // 空白笔记视为无笔记
        assertFalse(render(listOf(textAnn(10, note = "  "))).contains("- 笔记"))
    }

    @Test
    fun `颜色开关控制标记行去留且调色板颜色有名`() {
        val withColors = render(
            listOf(textAnn(10, style = AnnotationEntity.STYLE_UNDERLINE)),
        )
        assertTrue(withColors.contains("- 标记：黄色 · 下划线\n"))
        val withoutColors = render(
            listOf(textAnn(10)),
            options = AnnotationExportOptions(includeColors = false),
        )
        assertFalse(withoutColors.contains("- 标记："))
    }

    @Test
    fun `调色板外颜色落十六进制`() {
        val md = render(listOf(textAnn(10, color = 0xFF112233)))
        assertTrue(md.contains("- 标记：#FF112233 · 底色高亮\n"))
    }

    @Test
    fun `页式批注降级为页码与区域百分比描述`() {
        val md = render(listOf(pageAnn(2), pageAnn(0)), chapters = emptyList())
        // 页序号 0 基 → 导出为 1 基页码，按页升序
        assertTrue(md.indexOf("## 第 1 页") in 1 until md.indexOf("## 第 3 页"))
        assertTrue(md.contains("- 位置：区域：左上 (12%, 34%)，宽 56%、高 8%\n"))
        assertFalse(md.contains("字符起"))
    }

    @Test
    fun `页式批注能附上页式目录的章节名`() {
        val outline = listOf(
            Chapter(title = "序章", charStart = 0, charEnd = 0, pageIndex = 0),
            Chapter(title = "激战", charStart = 0, charEnd = 0, pageIndex = 10),
        )
        val md = render(listOf(pageAnn(12)), chapters = outline)
        assertTrue(md.contains("## 第 13 页 · 激战\n"))
    }

    @Test
    fun `页式批注无区域时说明为整页批注`() {
        val md = render(
            listOf(pageAnn(0, x = 0f, y = 0f, w = 0f, h = 0f).copy(regionX = null)),
            chapters = emptyList(),
        )
        assertTrue(md.contains("- 位置：整页批注（未记录区域）\n"))
    }

    @Test
    fun `文本与页式混存时分正文与页面两个顶级分区`() {
        val md = render(listOf(textAnn(10), pageAnn(4)))
        assertTrue(md.contains("\n## 正文批注\n"))
        assertTrue(md.contains("\n## 页面批注\n"))
        assertTrue(md.contains("\n### 第一章 起\n"))
        assertTrue(md.contains("\n### 第 5 页\n"))
    }

    @Test
    fun `空批注渲染为有效文档且明确标注零条`() {
        val md = render(emptyList())
        assertTrue(md.contains("- 批注数量：0 条"))
        assertTrue(md.contains("本书暂无批注。"))
        assertFalse(md.contains("---"))
    }

    @Test
    fun `位置行可带全书百分比`() {
        val md = render(listOf(textAnn(500, end = 560)), totalChars = 2000)
        assertTrue(md.contains("- 位置：第 500 字符起，共 60 字（全书约 25%）\n"))
    }

    @Test
    fun `特殊字符转义与换行压平`() {
        val md = AnnotationMarkdown.render(
            bookTitle = "书*名\n换行",
            author = null,
            annotations = listOf(
                textAnn(10, text = "含 *星号* 与 [括号] 与 \\ 反斜杠", note = "笔记里 #标题# 与 - 列表"),
            ),
            chapters = listOf(Chapter(title = "第 1 章\n副标题", charStart = 0, charEnd = 1000)),
            options = AnnotationExportOptions(),
            exportedAtMs = 0L,
        )
        assertTrue(md.startsWith("# 《书\\*名 换行》批注\n"))
        assertTrue(md.contains("## 第 1 章 副标题\n"))
        assertTrue(md.contains("> 含 \\*星号\\* 与 \\[括号\\] 与 \\\\ 反斜杠\n"))
        assertTrue(md.contains("  笔记里 #标题# 与 - 列表\n"))
    }

    @Test
    fun `文件名含书名与日期且清洗非法字符`() {
        assertEquals(
            "三体-批注-1970-01-01.md",
            AnnotationMarkdown.fileName("三体", 0L),
        )
        assertEquals(
            "a_b_c-批注-1970-01-01.md",
            AnnotationMarkdown.fileName("a/b\\c", 0L),
        )
        assertEquals(
            "book-批注-1970-01-01.md",
            AnnotationMarkdown.fileName("   ", 0L),
        )
    }
}

package com.llzx373.foldreader.core.export

import com.llzx373.foldreader.core.data.db.AnnotationEntity
import com.llzx373.foldreader.core.format.Chapter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 批注导出的可选内容开关。 */
data class AnnotationExportOptions(
    val includeNotes: Boolean = true,
    val includeColors: Boolean = true,
)

/**
 * 批注 / 高亮的 Markdown 导出（M27），纯 JVM——不碰 Android、不碰 IO，输出契约由单测锁定。
 *
 * 结构：文本书按章节分组（`## 章名`，章节归属判定与批注列表 UI 同口径：
 * 「最后一个 charStart <= 标注起点的章」，首章之前的落在第 0 章）；
 * 页式（漫画 / PDF）锚点没有字符坐标，降级为「第 N 页 + 区域描述」（区域是归一化 0..1
 * 坐标，导出为百分比）。同一本书两种锚点混存（PDF 文本模式 + 页式模式都划过）时，
 * 分「正文批注」「页面批注」两个顶级分区，组内降一级标题。
 *
 * 原文引用一律进引用块（每行 `> ` 前缀），笔记以列表项缩进续行承载，行内文本做最小
 * Markdown 转义（反斜杠与 `*`、`_`、`` ` ``、`[`、`]`）——笔记是用户原文，转义只为
 * 不让个别字符把整篇文档的结构打乱。
 */
object AnnotationMarkdown {

    /** 划线调色板（feature/reader/AnnotationUi.kt）的 ARGB → 颜色名；未知值落十六进制。 */
    private val COLOR_NAMES: Map<Long, String> = mapOf(
        0xFFFFF176L to "黄色",
        0xFFA5D6A7L to "绿色",
        0xFF90CAF9L to "蓝色",
        0xFFF48FB1L to "粉色",
        0xFFCE93D8L to "紫色",
    )

    private val ILLEGAL_FILE_NAME_CHARS = Regex("[\\\\/:*?\"<>|]")

    /** 导出的默认文件名：书名清洗非法字符 + 日期，如「三体-批注-2026-10-08.md」。 */
    fun fileName(bookTitle: String, exportedAtMs: Long): String {
        val safe = bookTitle.replace(ILLEGAL_FILE_NAME_CHARS, "_").trim().take(80).ifBlank { "book" }
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(exportedAtMs))
        return "$safe-批注-$day.md"
    }

    fun render(
        bookTitle: String,
        author: String?,
        annotations: List<AnnotationEntity>,
        chapters: List<Chapter>,
        options: AnnotationExportOptions,
        exportedAtMs: Long,
        /** 全书总字数（>0 时位置行追加「全书约 x%」）；页式书传 0。 */
        totalChars: Long = 0,
    ): String {
        val out = StringBuilder()
        out.append("# 《").append(escInline(flatten(bookTitle))).append("》批注\n\n")
        if (!author.isNullOrBlank()) {
            out.append("- 作者：").append(escInline(flatten(author))).append('\n')
        }
        out.append("- 导出时间：")
            .append(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(exportedAtMs)))
            .append('\n')
        val noteCount = annotations.count { !it.note.isNullOrBlank() }
        out.append("- 批注数量：").append(annotations.size).append(" 条")
        if (noteCount > 0) out.append("（其中 ").append(noteCount).append(" 条带笔记）")
        out.append("\n")

        if (annotations.isEmpty()) {
            out.append("\n本书暂无批注。\n")
            return out.toString()
        }
        out.append("\n---\n")

        val textAnns = annotations.filter { it.pageIndex == null }
            .sortedBy { it.startCharOffset }
        val pagedAnns = annotations.filter { it.pageIndex != null }
            .sortedWith(compareBy({ it.pageIndex }, { it.regionY ?: 0f }, { it.regionX ?: 0f }))
        val mixed = textAnns.isNotEmpty() && pagedAnns.isNotEmpty()
        // 混合时两组各降一级标题（正文/页面两个顶级分区）；单一种类直接平铺
        val groupLevel = if (mixed) 3 else 2

        if (textAnns.isNotEmpty()) {
            if (mixed) out.append("\n## 正文批注\n")
            var lastChapter = -1
            for (ann in textAnns) {
                // 与 AnnotationListDialog 同一口径：最后一个 charStart <= 起点的章，首章前归第 0 章
                val chapterIndex = chapters
                    .indexOfLast { ann.startCharOffset >= it.charStart }
                    .coerceAtLeast(0)
                if (chapterIndex != lastChapter) {
                    lastChapter = chapterIndex
                    out.append('\n')
                    repeat(groupLevel) { out.append('#') }
                    out.append(' ')
                        .append(escInline(flatten(chapters.getOrNull(chapterIndex)?.title.orEmpty().ifEmpty { "正文" })))
                        .append('\n')
                }
                appendEntry(out, ann, options, totalChars)
            }
        }
        if (pagedAnns.isNotEmpty()) {
            if (mixed) out.append("\n## 页面批注\n")
            // 页式目录（PDF outline）锚点是页序号：能解出章节名就附在页标题后
            val pageChapters = chapters.filter { it.pageIndex != null }
            var lastPage = -1L
            for (ann in pagedAnns) {
                val page = ann.pageIndex ?: continue
                if (page != lastPage) {
                    lastPage = page
                    val chapterTitle = pageChapters.lastOrNull { (it.pageIndex ?: Long.MAX_VALUE) <= page }
                        ?.title.orEmpty()
                    out.append('\n')
                    repeat(groupLevel) { out.append('#') }
                    out.append(" 第 ").append(page + 1).append(" 页")
                    if (chapterTitle.isNotEmpty()) {
                        out.append(" · ").append(escInline(flatten(chapterTitle)))
                    }
                    out.append('\n')
                }
                appendEntry(out, ann, options, totalChars)
            }
        }
        return out.toString()
    }

    private fun appendEntry(
        out: StringBuilder,
        ann: AnnotationEntity,
        options: AnnotationExportOptions,
        totalChars: Long,
    ) {
        out.append('\n')
        val text = ann.selectedText
        if (text.isNotBlank()) {
            // 引用块：逐行加 "> " 前缀，原文里的结构字符（#/ -/> 行首）因此天然失效
            for (line in text.split(Regex("\r\n|\r|\n"))) {
                if (line.isEmpty()) {
                    out.append(">\n")
                } else {
                    out.append("> ").append(escInline(line.trimEnd())).append('\n')
                }
            }
            out.append('\n')
        }
        // 位置说明：文本锚点是字符偏移（可换算全书百分比）；页式锚点是页内归一化区域
        val position = if (ann.pageIndex == null) {
            buildString {
                append("第 ").append(ann.startCharOffset).append(" 字符起")
                val length = (ann.endCharOffset - ann.startCharOffset).coerceAtLeast(0)
                if (length > 0) append("，共 ").append(length).append(" 字")
                if (totalChars > 0) {
                    append("（全书约 ").append(ann.startCharOffset * 100 / totalChars).append("%）")
                }
            }
        } else {
            val x = ann.regionX
            val y = ann.regionY
            val w = ann.regionW
            val h = ann.regionH
            if (x != null && y != null && w != null && h != null) {
                "区域：左上 (${(x * 100).toInt()}%, ${(y * 100).toInt()}%)，" +
                    "宽 ${(w * 100).toInt()}%、高 ${(h * 100).toInt()}%"
            } else {
                "整页批注（未记录区域）"
            }
        }
        out.append("- 位置：").append(position).append('\n')
        if (options.includeColors) {
            out.append("- 标记：").append(colorName(ann.color))
                .append(" · ").append(styleName(ann.style)).append('\n')
        }
        if (options.includeNotes) {
            val note = ann.note
            if (!note.isNullOrBlank()) {
                out.append("- 笔记：\n")
                for (line in note.split(Regex("\r\n|\r|\n"))) {
                    if (line.isBlank()) {
                        out.append('\n')
                    } else {
                        // 两格缩进续行：缩进后行首的 #/-/> 不再改变文档结构
                        out.append("  ").append(escInline(line)).append('\n')
                    }
                }
            }
        }
    }

    private fun colorName(color: Long): String =
        COLOR_NAMES[color and 0xFFFFFFFFL]
            ?: "#%08X".format(color and 0xFFFFFFFFL)

    private fun styleName(style: String): String = when (style) {
        AnnotationEntity.STYLE_UNDERLINE -> "下划线"
        AnnotationEntity.STYLE_HIGHLIGHT -> "底色高亮"
        else -> style
    }

    /** 行内最小转义：反斜杠先行，再转会开启 Markdown 语义的几个字符。 */
    private fun escInline(text: String): String = buildString(text.length) {
        for (c in text) {
            when (c) {
                '\\' -> append("\\\\")
                '`', '*', '_', '[', ']' -> append('\\').append(c)
                else -> append(c)
            }
        }
    }

    /** 标题/元信息不允许换行（会把 H1 或列表项撑成两行），压平成空格。 */
    private fun flatten(text: String): String =
        text.replace('\r', ' ').replace('\n', ' ')
}

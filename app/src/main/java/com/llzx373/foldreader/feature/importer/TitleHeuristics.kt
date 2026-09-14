package com.llzx373.foldreader.feature.importer

object TitleHeuristics {

    private const val MAX_HEAD_LINES = 20
    private val TITLE_LINE = Regex("^\\s*书名\\s*[:：]\\s*(.+?)\\s*$")
    private val AUTHOR_LINE = Regex("^\\s*作者\\s*[:：]\\s*(.+?)\\s*$")
    private val BOOK_TITLE_MARK = Regex("《([^》]{1,40})》")

    fun infer(fileBaseName: String?, headText: String): Pair<String, String?> {
        var title: String? = null
        var author: String? = null
        headText.lineSequence().take(MAX_HEAD_LINES).forEach { line ->
            if (title == null) TITLE_LINE.find(line)?.let { title = it.groupValues[1] }
            if (author == null) AUTHOR_LINE.find(line)?.let { author = it.groupValues[1] }
            if (title == null && line.trim().length <= 42) {
                BOOK_TITLE_MARK.find(line)?.let { title = it.groupValues[1] }
            }
        }
        val fallback = fileBaseName?.takeIf { it.isNotBlank() } ?: "未知书名"
        return (title?.takeIf { it.isNotBlank() } ?: fallback) to author?.takeIf { it.isNotBlank() }
    }
}

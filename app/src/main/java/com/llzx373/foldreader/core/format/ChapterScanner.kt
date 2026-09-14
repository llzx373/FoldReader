package com.llzx373.foldreader.core.format

object ChapterRules {
    private const val CN_NUM = "0-9０-９零一二三四五六七八九十百千万两"
    const val MAX_TITLE_LENGTH = 40

    val DEFAULT: List<Regex> = listOf(
        Regex("^(?:正文|序卷|作品相关|VIP卷)?\\s*第[$CN_NUM]+[章节卷回部篇][^\\n]{0,35}$"),
        Regex("^[Cc][Hh][Aa][Pp][Tt][Ee][Rr]\\s*\\d{1,6}[^\\n]{0,35}$"),
        Regex("^\\d{1,6}[、.．]\\s*\\S[^\\n]{0,34}$"),
        Regex("^(?:楔子|序章|序言|引子|前言|终章|尾声|番外篇?)[^\\n]{0,35}$"),
    )
}

class ChapterScanner(
    private val rules: List<Regex> = ChapterRules.DEFAULT,
) {
    private val pending = StringBuilder()
    private var pendingStart = 0L
    private var totalChars = 0L
    private val titles = mutableListOf<Pair<Long, String>>()

    fun feed(text: String) {
        if (text.isEmpty()) return
        pending.append(text)
        totalChars += text.length
        var consumed = 0
        while (true) {
            val newline = pending.indexOf('\n', consumed)
            if (newline < 0) break
            processLine(pending.substring(consumed, newline + 1), pendingStart + consumed)
            consumed = newline + 1
        }
        if (consumed > 0) {
            pending.delete(0, consumed)
            pendingStart += consumed
        }
    }

    fun finish(): List<Chapter> {
        if (pending.isNotEmpty()) {
            processLine(pending.toString(), pendingStart)
            pending.clear()
        }
        if (titles.isEmpty()) return listOf(Chapter("全文", 0L, totalChars))
        val chapters = mutableListOf<Chapter>()
        if (titles.first().first > 0) chapters += Chapter("卷首", 0L, titles.first().first)
        for (i in titles.indices) {
            val end = if (i + 1 < titles.size) titles[i + 1].first else totalChars
            chapters += Chapter(titles[i].second, titles[i].first, end)
        }
        return chapters
    }

    private fun processLine(rawLine: String, lineStart: Long) {
        val trimmed = rawLine.trim()
        if (trimmed.isEmpty() || trimmed.length > ChapterRules.MAX_TITLE_LENGTH) return
        for (rule in rules) {
            if (rule.matches(trimmed)) {
                titles += lineStart to trimmed
                return
            }
        }
    }
}

package com.llzx373.foldreader.core.export

import com.llzx373.foldreader.core.data.db.WordEntryEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 生词本 CSV 导出（M28）：Anki 兼容三列「单词,释义,例句」，
 * RFC 4180 转义（含逗号/引号/换行的字段整体加引号、引号翻倍）。
 * 纯 JVM 零 android import。
 */
object VocabularyCsv {

    const val HEADER = "单词,释义,例句"

    fun render(entries: List<WordEntryEntity>): String = buildString {
        append(HEADER).append("\r\n")
        entries.forEach { e ->
            append(escape(e.word)).append(',')
            append(escape(e.definition)).append(',')
            append(escape(e.contextSentence)).append("\r\n")
        }
    }

    /** 默认文件名：生词本-yyyyMMdd-HHmmss.csv。 */
    fun fileName(exportedAtMs: Long): String =
        "生词本-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(exportedAtMs)) + ".csv"

    private fun escape(field: String): String {
        val needsQuote = field.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        if (!needsQuote) return field
        return "\"" + field.replace("\"", "\"\"") + "\""
    }
}

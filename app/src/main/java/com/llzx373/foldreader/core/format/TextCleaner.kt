package com.llzx373.foldreader.core.format

import android.content.Context
import java.io.BufferedReader
import java.io.StringReader
import java.io.StringWriter
import java.io.Writer

object TextCleaner {

    data class CleanOptions(
        val removeBlankLines: Boolean = false,
        val adPatterns: List<Regex> = emptyList(),
        val traditionalToSimplified: Boolean = false,
    ) {
        val isNoop: Boolean
            get() = !removeBlankLines && adPatterns.isEmpty() && !traditionalToSimplified
    }

    fun clean(
        text: String,
        options: CleanOptions,
        tsMap: Map<Char, Char> = emptyMap(),
    ): String {
        if (options.isNoop) return text
        val writer = StringWriter(text.length)
        cleanStream(BufferedReader(StringReader(text)), writer, options, tsMap)
        return writer.toString()
    }

    /** 管线顺序：繁简 → 去广告 → 去空行；空行指仅含空白字符的行。 */
    fun cleanStream(
        reader: BufferedReader,
        writer: Writer,
        options: CleanOptions,
        tsMap: Map<Char, Char> = emptyMap(),
    ) {
        if (options.isNoop) {
            reader.copyTo(writer)
            writer.flush()
            return
        }
        val convert = options.traditionalToSimplified && tsMap.isNotEmpty()
        reader.forEachLine { raw ->
            val line = if (convert) toSimplified(raw, tsMap) else raw
            if (options.adPatterns.any { it.containsMatchIn(line) }) return@forEachLine
            if (options.removeBlankLines && line.isBlank()) return@forEachLine
            writer.write(line)
            writer.write("\n")
        }
        writer.flush()
    }

    private fun toSimplified(line: String, tsMap: Map<Char, Char>): String {
        val out = StringBuilder(line.length)
        for (c in line) out.append(tsMap[c] ?: c)
        return out.toString()
    }
}

object TsCharMap {

    const val ASSET_NAME = "ts_map.txt"

    @Volatile
    private var cached: Map<Char, Char>? = null

    fun load(context: Context): Map<Char, Char> =
        cached ?: synchronized(this) {
            cached ?: parse(context.assets.open(ASSET_NAME).use { it.readBytes() }.toString(Charsets.UTF_8))
                .also { cached = it }
        }

    internal fun parse(text: String): Map<Char, Char> {
        val map = HashMap<Char, Char>(text.length / 2)
        var i = 0
        while (i < text.length) {
            val from = Character.codePointAt(text, i)
            i += Character.charCount(from)
            if (i >= text.length) break
            val to = Character.codePointAt(text, i)
            i += Character.charCount(to)
            if (from <= 0xFFFF && to <= 0xFFFF) map[from.toChar()] = to.toChar()
        }
        return map
    }
}

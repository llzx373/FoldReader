package com.llzx373.foldreader.core.format

/**
 * 内存文本的 [BookContent]（M19 译本流）：译本是 assemble 出来的整串字符，
 * 不走文件 / 索引管线，charCount 即字符串长且恒为终值，read 为区间切片（越界 clamp）。
 */
class StringBookContent(private val text: String) : BookContent {

    override val charCount: Long get() = text.length.toLong()

    override suspend fun read(range: LongRange): String {
        val start = range.first.coerceIn(0L, text.length.toLong())
        val endExclusive = (range.last + 1).coerceIn(start, text.length.toLong())
        return text.substring(start.toInt(), endExclusive.toInt())
    }
}

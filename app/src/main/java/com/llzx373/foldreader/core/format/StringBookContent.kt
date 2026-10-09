package com.llzx373.foldreader.core.format

/**
 * 内存文本的 [BookContent]（M19 译本流）：译本是 assemble 出来的整串字符，
 * 不走文件 / 索引管线，charCount 即字符串长且恒为终值，read 为区间切片（越界 clamp）。
 */
class StringBookContent(private val text: String) : BookContent {

    override val charCount: Long get() = text.length.toLong()

    override suspend fun read(range: LongRange): String {
        val length = text.length.toLong()
        val start = range.first.coerceIn(0L, length)
        // range.last 可能是 Long.MAX_VALUE（「读到结尾」惯用法），+1 前必须先钳，否则溢出成负
        val endExclusive = (minOf(range.last, length - 1) + 1).coerceIn(start, length)
        return text.substring(start.toInt(), endExclusive.toInt())
    }
}

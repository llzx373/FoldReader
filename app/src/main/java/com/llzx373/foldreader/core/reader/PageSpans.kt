package com.llzx373.foldreader.core.reader

import com.llzx373.foldreader.core.format.TextSpan
import com.llzx373.foldreader.core.format.TextSpanType

/**
 * 把全局 span 列表按行区间 [lineStart, lineEnd) 切片：行内部分截断、跨行自然拆段。
 * IMAGE span 不参与文本行切片（它是行级结构，由 PageLine.imagePath 表达）。
 */
fun sliceSpansForLine(
    spans: List<TextSpan>,
    lineStart: Long,
    lineEnd: Long,
): List<TextSpan> = spans.mapNotNull { span ->
    if (span.type == TextSpanType.IMAGE) return@mapNotNull null
    val s = maxOf(span.start, lineStart)
    val e = minOf(span.end, lineEnd)
    if (s < e) span.copy(start = s, end = e) else null
}

/** 一段文本上的等样式区间（行内索引，[start, end)）。 */
data class StyleRun(
    val start: Int,
    val end: Int,
    val types: Set<TextSpanType>,
    /** LINK/NOTEREF 的 payload（"#目标偏移" 或 URL）；非链接为 null。 */
    val linkPayload: String? = null,
)

/**
 * 行内样式分段：把落在本行的 span 展开为逐字符样式掩码后压缩为等样式区间。
 * 无 span 时返回单区间（调用方可走无样式快路径）。
 */
fun styleRuns(line: PageLine): List<StyleRun> {
    val n = line.text.length
    if (n == 0) return emptyList()
    if (line.spans.isEmpty()) return listOf(StyleRun(0, n, emptySet()))
    val masks = arrayOfNulls<Pair<Set<TextSpanType>, String?>>(n)
    for (span in line.spans) {
        val s = (span.start - line.charStart).toInt().coerceIn(0, n)
        val e = (span.end - line.charStart).toInt().coerceIn(0, n)
        for (i in s until e) {
            val prev = masks[i]
            masks[i] = ((prev?.first ?: emptySet()) + span.type) to
                (span.payload ?: prev?.second)
        }
    }
    val runs = ArrayList<StyleRun>()
    var i = 0
    while (i < n) {
        var j = i + 1
        while (j < n && masks[j] == masks[i]) j++
        val mask = masks[i]
        runs += StyleRun(i, j, mask?.first ?: emptySet(), mask?.second)
        i = j
    }
    return runs
}

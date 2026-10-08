package com.llzx373.foldreader.core.format.html

import com.llzx373.foldreader.core.format.Chapter
import com.llzx373.foldreader.core.format.TextSpan
import com.llzx373.foldreader.core.format.TextSpanType

/**
 * h 系列标题 → 章节：锚点偏移为 charStart（消毒时收集，已按文档序），
 * 同偏移去重保留先出现标题；无标题时退化为单个「正文」章（depth=0）。
 * DOCX 与 HTML 书共用。
 */
internal fun buildHeadingChapters(
    headings: List<HtmlSanitizer.Heading>,
    anchors: Map<String, Long>,
    documentFile: String,
    totalChars: Long,
): List<Chapter> {
    val points = LinkedHashMap<Long, Pair<String, Int>>()
    for (heading in headings) {
        val start = anchors["$documentFile#${heading.id}"] ?: continue
        if (start < 0L || start >= totalChars) continue
        points.putIfAbsent(start, heading.text to (heading.level - 1).coerceAtLeast(0))
    }
    val starts = points.keys.sorted()
    val chapters = starts.mapIndexed { index, start ->
        val (title, depth) = points.getValue(start)
        Chapter(
            title = title,
            charStart = start,
            charEnd = if (index + 1 < starts.size) starts[index + 1] else totalChars,
            depth = depth,
        )
    }.filter { it.charEnd > it.charStart }
    if (chapters.isNotEmpty()) return chapters
    return listOf(Chapter("正文", 0L, totalChars))
}

/**
 * 链接 span 后处理：内部目标经锚点表解析为 `#目标charOffset`（fragment 未命中回退
 * 文件级锚点，两者皆无则丢弃该 span）；外部 http(s) URL 原样保留。与 EPUB 同规则，
 * DOCX 与 HTML 书共用。
 */
internal fun resolveHtmlLinkSpans(spans: List<TextSpan>, anchors: Map<String, Long>): List<TextSpan> =
    spans.mapNotNull { span ->
        if (span.type != TextSpanType.LINK && span.type != TextSpanType.NOTEREF) return@mapNotNull span
        val payload = span.payload ?: return@mapNotNull null
        if (payload.startsWith("http://") || payload.startsWith("https://")) return@mapNotNull span
        val file = payload.substringBefore('#')
        val fragment = payload.substringAfter('#', "").takeIf { it.isNotEmpty() }
        val target = fragment?.let { anchors["$file#$it"] ?: anchors[file] } ?: anchors[file]
        target?.let { span.copy(payload = "#$it") }
    }

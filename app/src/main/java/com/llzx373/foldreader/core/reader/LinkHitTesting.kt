package com.llzx373.foldreader.core.reader

import com.llzx373.foldreader.core.format.TextSpanType

/** 书内链接点按结果（命中 LINK/NOTEREF span 时由阅读页消费点按，不再翻页）。 */
sealed interface LinkHit {
    /** 内部链接：跳转到目标 charOffset（payload "#offset"）。 */
    data class Internal(val targetOffset: Long) : LinkHit

    /** 外部链接：交给系统浏览器打开（payload 为 http(s) URL）。 */
    data class External(val url: String) : LinkHit

    /** 脚注引用（epub:type="noteref"）：弹注展示目标处内容，目标为 charOffset。 */
    data class Note(val targetOffset: Long) : LinkHit
}

/**
 * 链接命中测试：返回坐标 (x, y) 处被点中的字符所在的 LINK/NOTEREF span。
 *
 * 与 [caretAt] 共用同一套行几何，但判定粒度是「字符」而非「字符间光标位」：
 * x 落在第 i 字的 [boundaryX(i), boundaryX(i+1)) 内即算点中该字，
 * 再以半开区间 [span.start, span.end) 判定归属（行尾空白、行间段距、页边距均为死区）。
 */
fun linkHitAt(boxes: List<LineBox>, x: Float, y: Float): LinkHit? {
    val box = boxes.firstOrNull { y >= it.yTop && y < it.yBottom } ?: return null
    val n = box.textLength
    if (n == 0 || x < box.x0 || x >= box.boundaryX(n)) return null
    var index = n - 1
    for (i in 0 until n) {
        if (x < box.boundaryX(i + 1)) {
            index = i
            break
        }
    }
    val charOffset = box.line.charStart + index
    val span = box.line.spans.firstOrNull {
        (it.type == TextSpanType.LINK || it.type == TextSpanType.NOTEREF) &&
            charOffset >= it.start && charOffset < it.end
    } ?: return null
    return linkHitOf(span.type, span.payload)
}

/** 把 span 的 payload 解码为点按结果；payload 缺失/不可解析时返回 null（不消费点按）。 */
fun linkHitOf(type: TextSpanType, payload: String?): LinkHit? {
    if (payload == null) return null
    if (payload.startsWith("#")) {
        val target = payload.removePrefix("#").toLongOrNull() ?: return null
        return when (type) {
            TextSpanType.LINK -> LinkHit.Internal(target)
            TextSpanType.NOTEREF -> LinkHit.Note(target)
            else -> null
        }
    }
    if (payload.startsWith("http://") || payload.startsWith("https://")) {
        return LinkHit.External(payload)
    }
    return null
}

/** 弹注内容的长度/段落上限。 */
const val NOTE_EXCERPT_MAX_CHARS = 300
const val NOTE_EXCERPT_MAX_PARAGRAPHS = 3

/**
 * 脚注弹注截取：从目标处原文取最多 [maxParagraphs] 个非空段落、累计不超 [maxChars]。
 * 优先在段落边界收束；仅当第一段本身就超长时才硬截断（补省略号）。
 */
fun excerptNote(
    text: String,
    maxChars: Int = NOTE_EXCERPT_MAX_CHARS,
    maxParagraphs: Int = NOTE_EXCERPT_MAX_PARAGRAPHS,
): String {
    val paragraphs = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
    val out = StringBuilder()
    var count = 0
    for (paragraph in paragraphs) {
        if (count >= maxParagraphs) break
        val candidate = if (out.isEmpty()) paragraph else "$out\n$paragraph"
        if (candidate.length > maxChars) {
            // 后续段落放不下就在此收束；第一段即超长则硬截断
            if (out.isEmpty()) return paragraph.take(maxChars).trimEnd() + "…"
            break
        }
        out.clear()
        out.append(candidate)
        count++
    }
    return out.toString()
}

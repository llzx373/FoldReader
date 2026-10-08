package com.llzx373.foldreader.core.dict

/** 句末边界字符（中英文句读 + 换行；分号/省略号也算软边界）。 */
private val SENTENCE_ENDS = setOf('。', '！', '？', '!', '?', '\n', '；', ';', '…')

/** 例句长度上限：超出时以词为中心截取，保证生词本列表可读。 */
private const val MAX_SENTENCE_CHARS = 200

/**
 * 生词本上下文例句抽取（M28）：从 [window]（含选中词的一段正文窗口）里取出
 * 含该词的完整句子——向左找最近的句末边界（不含）、向右找最近的句末边界（含）。
 * 找不到边界或句子超长时以词为中心截取 [MAX_SENTENCE_CHARS] 字符。
 * [wordStart] 是词在 window 内的起始下标。
 */
fun extractContextSentence(window: String, wordStart: Int, wordLength: Int): String {
    if (window.isEmpty()) return ""
    val start = wordStart.coerceIn(0, window.length)
    val end = (wordStart + wordLength).coerceIn(start, window.length)

    var left = start - 1
    while (left >= 0 && window[left] !in SENTENCE_ENDS) left--
    var right = end
    while (right < window.length && window[right] !in SENTENCE_ENDS) right++
    // 右侧命中边界时把标点带上（它在 SENTENCE_ENDS 里）；越界就到窗口尾
    if (right < window.length) right++

    var sentence = window.substring(left + 1, right).trim()
    if (sentence.length > MAX_SENTENCE_CHARS) {
        val center = (start + end) / 2
        val half = MAX_SENTENCE_CHARS / 2
        val s = (center - half).coerceAtLeast(0)
        val e = (s + MAX_SENTENCE_CHARS).coerceAtMost(window.length)
        sentence = window.substring(s, e).trim()
            .let { (if (s > 0) "…" else "") + it + (if (e < window.length) "…" else "") }
    }
    return sentence
}

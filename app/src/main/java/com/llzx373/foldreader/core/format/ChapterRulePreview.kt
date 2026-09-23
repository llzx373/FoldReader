package com.llzx373.foldreader.core.format

/**
 * 规则试切预览:给定一条候选正则,对完整文本真实切分并产出预览报告。
 *
 * 纯 JVM 组件,不依赖 Android;切分口径与 [ChapterScanner] 一致(逐行 trim 后整行匹配),
 * 方便 UI 层在应用规则前先展示效果与异常。
 */
object ChapterRulePreview {

    /** 预览报告最多携带的标题数 */
    const val TITLE_PREVIEW_LIMIT = 20

    /** 超长章的绝对阈值:单章超过该字数必属异常(一般书籍单章在数千字量级) */
    const val LONG_CHAPTER_MIN_CHARS = 40000

    /** 孤儿文本阈值:首个标题前的正文超过该字数说明规则漏切了开头 */
    const val ORPHAN_TEXT_MIN_CHARS = 500

    data class RulePreview(
        val chapterCount: Int,
        val titles: List<String>,
        val anomalies: List<String>,
    )

    /**
     * 用 [pattern] 对 [text] 试切。
     * 正则无法编译时不抛异常,而是返回只有异常信息的报告,便于 UI 直接展示。
     */
    fun preview(text: String, pattern: String): RulePreview {
        val rule = runCatching { Regex(pattern) }.getOrElse {
            return RulePreview(0, emptyList(), listOf("正则无法编译:${it.message ?: pattern}"))
        }
        val scanner = ChapterScanner(listOf(rule))
        scanner.feed(text)
        val raw = scanner.finish()
        // 剔除 ChapterScanner 补出的伪章节(「卷首」「全文」),只统计规则真实切出的章
        val chapters = raw.filterNot {
            (it.title == "卷首" && it.charStart == 0L) || (it.title == "全文" && raw.size == 1)
        }

        val anomalies = mutableListOf<String>()
        if (chapters.size <= 1) {
            anomalies += "仅切出 ${chapters.size} 章,规则可能未生效"
        }
        val first = chapters.firstOrNull()
        if (first != null && first.charStart > ORPHAN_TEXT_MIN_CHARS) {
            anomalies += "孤儿文本:首个标题前有 ${first.charStart} 字正文未被任何章节覆盖"
        }
        chapters.forEachIndexed { i, chapter ->
            val content = text.substring(chapter.charStart.toInt(), chapter.charEnd.toInt())
            val body = content.substringAfter('\n', "")
            if (body.isBlank()) {
                anomalies += "第 ${i + 1} 章「${chapter.title}」内容为空"
            }
            // 超长章:占全文一半以上,或绝对字数超阈值,通常意味着中间漏切
            if (content.length > text.length / 2 || content.length > LONG_CHAPTER_MIN_CHARS) {
                anomalies += "第 ${i + 1} 章「${chapter.title}」超长(${content.length} 字),中间可能漏切"
            }
        }
        return RulePreview(
            chapterCount = chapters.size,
            titles = chapters.take(TITLE_PREVIEW_LIMIT).map { it.title },
            anomalies = anomalies,
        )
    }
}

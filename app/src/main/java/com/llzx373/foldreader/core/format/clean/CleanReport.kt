package com.llzx373.foldreader.core.format.clean

/**
 * 一次清洗的改动报告。
 *
 * 用途有两个：导入/整理完成后给用户一句摘要，以及「清洗预览」对话框里展示 before/after 样例
 * （用户据此判断档位是否会误伤，再决定是否执行）。
 */
data class CleanReport(
    val linesIn: Int = 0,
    val linesOut: Int = 0,
    val charsRemoved: Long = 0,
    val removedNoiseLines: Int = 0,
    val excisedInlineNoise: Int = 0,
    val mergedParagraphs: Int = 0,
    val blankLinesRemoved: Int = 0,
    val chapterTitlesRepaired: Int = 0,
    val chapterTitlesDeduped: Int = 0,
    val punctuationFixed: Int = 0,
    val charFixes: Int = 0,
    val tocLinesRemoved: Int = 0,
    val samples: List<Sample> = emptyList(),
) {
    /** 是否有任何改动。 */
    val changed: Boolean
        get() = linesIn != linesOut ||
            charsRemoved != 0L ||
            removedNoiseLines > 0 ||
            excisedInlineNoise > 0 ||
            mergedParagraphs > 0 ||
            blankLinesRemoved > 0 ||
            chapterTitlesRepaired > 0 ||
            chapterTitlesDeduped > 0 ||
            punctuationFixed > 0 ||
            charFixes > 0 ||
            tocLinesRemoved > 0

    /** 一句话摘要，供 Snackbar / 对话框标题。 */
    fun summary(): String {
        if (!changed) return "没有需要整理的内容"
        val parts = buildList {
            if (removedNoiseLines > 0) add("删除广告/噪音 $removedNoiseLines 行")
            if (tocLinesRemoved > 0) add("删除目录块 $tocLinesRemoved 行")
            if (mergedParagraphs > 0) add("合并段落 $mergedParagraphs 处")
            if (blankLinesRemoved > 0) add("删除空行 $blankLinesRemoved 处")
            if (chapterTitlesRepaired > 0) add("修复章节标题 $chapterTitlesRepaired 个")
            if (chapterTitlesDeduped > 0) add("去重章节标题 $chapterTitlesDeduped 个")
            if (excisedInlineNoise > 0) add("切除行内噪音 $excisedInlineNoise 处")
            if (punctuationFixed > 0) add("规整标点 $punctuationFixed 处")
            if (charFixes > 0) add("归一字符 $charFixes 处")
        }
        return parts.joinToString("、")
    }

    /** 改动样例。`before`/`after` 已截断，仅供展示。 */
    data class Sample(
        val kind: Kind,
        val before: String,
        val after: String,
    ) {
        enum class Kind { REMOVED, MERGED, CHAPTER, CHAR, INLINE }
    }

    companion object {
        const val DEFAULT_MAX_SAMPLES = 20
        const val SAMPLE_MAX_CHARS = 80
    }
}

/** 流式累积报告的可变版本；[build] 时冻结为 [CleanReport]。 */
internal class CleanReportBuilder(private val maxSamples: Int = CleanReport.DEFAULT_MAX_SAMPLES) {
    var linesIn: Int = 0
    var linesOut: Int = 0
    var charsIn: Long = 0L
    var charsOut: Long = 0L
    var removedNoiseLines: Int = 0
    var excisedInlineNoise: Int = 0
    var mergedParagraphs: Int = 0
    var blankLinesRemoved: Int = 0
    var chapterTitlesRepaired: Int = 0
    var chapterTitlesDeduped: Int = 0
    var punctuationFixed: Int = 0
    var charFixes: Int = 0
    var tocLinesRemoved: Int = 0

    private val samples = ArrayList<CleanReport.Sample>()

    fun sample(kind: CleanReport.Sample.Kind, before: String, after: String) {
        if (maxSamples <= 0 || samples.size >= maxSamples) return
        samples += CleanReport.Sample(
            kind = kind,
            before = before.truncate(),
            after = after.truncate(),
        )
    }

    fun build(): CleanReport = CleanReport(
        linesIn = linesIn,
        linesOut = linesOut,
        charsRemoved = (charsIn - charsOut).coerceAtLeast(0L),
        removedNoiseLines = removedNoiseLines,
        excisedInlineNoise = excisedInlineNoise,
        mergedParagraphs = mergedParagraphs,
        blankLinesRemoved = blankLinesRemoved,
        chapterTitlesRepaired = chapterTitlesRepaired,
        chapterTitlesDeduped = chapterTitlesDeduped,
        punctuationFixed = punctuationFixed,
        charFixes = charFixes,
        tocLinesRemoved = tocLinesRemoved,
        samples = samples.toList(),
    )

    private fun String.truncate(): String =
        if (length <= CleanReport.SAMPLE_MAX_CHARS) this
        else take(CleanReport.SAMPLE_MAX_CHARS) + "…"
}

package com.llzx373.foldreader.core.summary

/** 问书上下文（M29）：前序章节摘要链 + 当前章正文（可能已被截头）。 */
data class BookQaContext(
    /** 前序章节的 done 摘要（单位标题 → 摘要正文），按单位号升序，全部保留。 */
    val summaries: List<Pair<String, String>>,
    /** 当前章（标题 → 正文）；超长时被截头（只留尾部）。null = 当前章正文不可用。 */
    val currentChapter: Pair<String, String>?,
)

/** 问书上下文的字符预算：超出部分只从当前章头部砍（摘要全保留）。 */
const val QA_CONTEXT_MAX_CHARS = 24_000

/**
 * 组装问书上下文（M29，纯 JVM 可测）。
 *
 * 截断策略：摘要链全部保留（单条 150~300 字，是压缩过的廉价上下文），
 * 当前章正文超预算时**截头**——只留尾部（越靠后越接近当前阅读位置，
 * 保住最近的情节）；预算被摘要占满时当前章整块舍弃（仍返回非 null）。
 *
 * 返回 null = 无任何可用上下文（无摘要且当前章为空），调用方应提示先阅读 / 先生成摘要。
 */
fun assembleQaContext(
    currentChapterTitle: String,
    currentChapterText: String,
    priorSummaries: List<Pair<String, String>>,
    maxChars: Int = QA_CONTEXT_MAX_CHARS,
): BookQaContext? {
    val summaries = priorSummaries
        .map { (title, summary) -> title to summary.trim() }
        .filter { it.second.isNotEmpty() }
    val summaryChars = summaries.sumOf { it.second.length }
    val chapter = currentChapterText.trim()
    val budget = (maxChars - summaryChars).coerceAtLeast(0)
    val keptChapter = when {
        chapter.isEmpty() -> null
        chapter.length <= budget -> currentChapterTitle to chapter
        budget == 0 -> null
        else -> currentChapterTitle to chapter.takeLast(budget)
    }
    if (summaries.isEmpty() && keptChapter == null) return null
    return BookQaContext(summaries = summaries, currentChapter = keptChapter)
}

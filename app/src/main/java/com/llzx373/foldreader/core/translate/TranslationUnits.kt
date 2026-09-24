package com.llzx373.foldreader.core.translate

import com.llzx373.foldreader.core.format.Chapter
import kotlinx.serialization.Serializable

/** 翻译单位类型（R12）：整章一个单位，或超大章 / 无章书按段落边界切出的块。 */
@Serializable
enum class UnitKind { CHAPTER, BLOCK }

/**
 * 一个翻译 / 进度 / 对齐单位。[charStart] / [charEnd] 是**原文流**的字符偏移，
 * 半开区间 `[charStart, charEnd)`（与 [Chapter.charEnd] 同口径：下一单位起点或文末）。
 */
@Serializable
data class TranslationUnit(
    /** 全书范围内单位的 0 基连续序号（CHAPTER 与 BLOCK 混排统一编号）。 */
    val index: Int,
    val kind: UnitKind,
    val title: String,
    val charStart: Long,
    val charEnd: Long,
)

/** 块的目標字数区间（R12）：按段落边界切块，每块 [MIN_BLOCK_CHARS]–[MAX_BLOCK_CHARS] 字。 */
const val MIN_BLOCK_CHARS = 2000
const val MAX_BLOCK_CHARS = 4000

/** 未译单位在译本流里的占位段。 */
const val UNTRANSLATED_PLACEHOLDER = "（本节未翻译）"

/**
 * 按段落边界把 [text] 切块（R12）。
 *
 * - 段落 = 以 `\n` 结尾的最大连续片段（兼容 `\r\n`，`\r` 留在段内），块边界绝不跨段；
 * - 贪心装块：当前块非空且装入下一段会超 [maxChars] 时合块；
 * - 单段自身超 [maxChars] 时该段独立成块（允许超 max，段不可再切）；
 * - 最后一块可能不足 [minChars]（受段落边界约束时属正常）。
 *
 * 返回的 [LongRange] 是**闭区间**，满足 `下一块.first == 上一块.last + 1`，
 * 各块按 `[first, last + 1)` 取子串拼接后与 [text] 逐字节相等（无遗漏无重叠）；
 * [baseOffset] 把块内偏移换算回原文流偏移。空文本返回空列表。
 */
fun splitIntoBlocks(
    text: String,
    baseOffset: Long,
    minChars: Int = MIN_BLOCK_CHARS,
    maxChars: Int = MAX_BLOCK_CHARS,
): List<LongRange> {
    if (text.isEmpty()) return emptyList()
    // 段落边界：每个 '\n' 之后，外加文末
    val paragraphEnds = ArrayList<Int>()
    text.forEachIndexed { i, ch -> if (ch == '\n') paragraphEnds += i + 1 }
    if (paragraphEnds.isEmpty() || paragraphEnds.last() < text.length) paragraphEnds += text.length

    val blocks = ArrayList<LongRange>()
    var blockStart = 0
    var paragraphStart = 0
    for (paragraphEnd in paragraphEnds) {
        // 当前块非空且装入本段（[paragraphStart, paragraphEnd)）会超 maxChars → 在段界合块。
        // 单段超 maxChars 时本判断会在下一段（或循环结束后）把它独立成块。
        if (blockStart < paragraphStart && paragraphEnd - blockStart > maxChars) {
            blocks += (baseOffset + blockStart) until (baseOffset + paragraphStart)
            blockStart = paragraphStart
        }
        paragraphStart = paragraphEnd
    }
    blocks += (baseOffset + blockStart) until (baseOffset + text.length)
    return blocks
}

/**
 * 把整本书切成翻译单位（R12），保证 `[0, charCount)` 无遗漏无重叠、按偏移升序。
 *
 * - [chapters] 为空（或无有效字符区间）→ 全书按块，标题「第 N 节」全书连续编号；
 * - 章长 ≤ [chapterThreshold] → [UnitKind.CHAPTER] 单位，用章标题；
 * - 章长超阈值 → [read] 出该章文本切块，标题「章名 · 节 k」（k 章内 1 基）；
 * - 章节未覆盖的首尾 / 章间孤儿文本同样切块纳入（标题「第 N 节」，与无章书共用同一计数器）。
 *
 * [read] 取原文 `[first, last]` 闭区间文本（块由 [splitIntoBlocks] 产出，天然是该形状）。
 * 字符区间为空的章（如 PDF 页式目录 charStart/charEnd 全 0）不参与切块。
 */
fun computeUnits(
    chapters: List<Chapter>,
    charCount: Long,
    read: (LongRange) -> String,
    chapterThreshold: Int = MAX_BLOCK_CHARS,
): List<TranslationUnit> {
    if (charCount <= 0) return emptyList()
    val units = ArrayList<TranslationUnit>()
    var blockSection = 0 // 「第 N 节」全书连续编号
    var cursor = 0L

    fun addBlock(title: String, range: LongRange) {
        units += TranslationUnit(
            index = units.size,
            kind = UnitKind.BLOCK,
            title = title,
            charStart = range.first,
            charEnd = range.last + 1,
        )
    }

    fun addOrphanBlocks(start: Long, end: Long) {
        if (end <= start) return
        val ranges = splitIntoBlocks(read(start until end), start)
        for (range in ranges) addBlock("第 ${++blockSection} 节", range)
    }

    val valid = chapters
        .filter { it.charEnd > it.charStart && it.charStart < charCount }
        .sortedBy { it.charStart }
    for (chapter in valid) {
        val start = maxOf(chapter.charStart, cursor)
        val end = minOf(chapter.charEnd, charCount)
        if (start > cursor) addOrphanBlocks(cursor, start)
        if (end <= start) continue
        cursor = end
        if (end - start <= chapterThreshold) {
            units += TranslationUnit(
                index = units.size,
                kind = UnitKind.CHAPTER,
                title = chapter.title,
                charStart = start,
                charEnd = end,
            )
        } else {
            val ranges = splitIntoBlocks(read(start until end), start)
            ranges.forEachIndexed { i, range ->
                addBlock("${chapter.title} · 节 ${i + 1}", range)
            }
        }
    }
    if (cursor < charCount) addOrphanBlocks(cursor, charCount)
    return units
}

/**
 * 把一段正文切成段落列表（翻译引擎的 prompt 输入口径）：按换行切、去首尾空白、
 * 过滤空白段。兼容 `\r\n`。空 / 全空白输入返回空列表。
 */
fun splitIntoParagraphs(text: String): List<String> =
    text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }

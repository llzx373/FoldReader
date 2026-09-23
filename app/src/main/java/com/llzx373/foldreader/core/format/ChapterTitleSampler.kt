package com.llzx373.foldreader.core.format

/**
 * 章节标题采样器:从完整 TXT 文本中粗筛「疑似标题行」,供 AI 归纳章节规则。
 *
 * 纯 JVM 组件,不依赖 Android;输入是 `BookContent.read(...)` 读出的完整文本。
 * 粗筛目标是「漏率低」:宁可误放正文短句,也不漏掉真实标题。
 */
object ChapterTitleSampler {

    /** 粗筛行宽上限,比 [ChapterRules.MAX_TITLE_LENGTH] 略放宽,容忍带前缀/空格的标题 */
    const val MAX_SAMPLE_LINE_LENGTH = 60

    /** 标题常见字样 */
    private val TITLE_KEYWORDS = listOf("章", "卷", "回", "部", "节", "篇", "序", "楔子", "番外")

    /** 以阿拉伯数字 / 全角数字 / 中文数字开头 */
    private val NUMERIC_START = Regex("^[0-9０-９零一二三四五六七八九十百千万两]")

    /** 全大写英文单词(允许空格与数字),如 "PROLOGUE"、"CHAPTER ONE" */
    private val UPPER_ENGLISH = Regex("^[A-Z][A-Z0-9 ]*$")

    /** 罗马数字开头,如 "IV 终局"、"IX. The End" */
    private val ROMAN_START = Regex("^[IVXLCDM]+[.、\\s]")

    /**
     * 粗筛并采样。
     *
     * @param text 完整文本
     * @param maxLines 采样行数上限,超过时按头/中/尾均匀抽取,保证全书各段都有样本
     * @return 采样行(已 trim,保持原文先后顺序)
     */
    fun sample(text: String, maxLines: Int = 300): List<String> {
        if (text.isEmpty() || maxLines <= 0) return emptyList()
        val candidates = text.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.length <= MAX_SAMPLE_LINE_LENGTH && looksLikeTitle(it) }
        if (candidates.size <= maxLines) return candidates
        // 均匀抽取:首行与末行必含,中间等距取样
        val last = candidates.size - 1
        return (0 until maxLines).map { candidates[it * last / (maxLines - 1)] }.distinct()
    }

    private fun looksLikeTitle(line: String): Boolean {
        if (TITLE_KEYWORDS.any { line.contains(it) }) return true
        if (NUMERIC_START.containsMatchIn(line)) return true
        if (line.length >= 2 && UPPER_ENGLISH.matches(line)) return true
        if (ROMAN_START.containsMatchIn(line)) return true
        // 命中内置规则的行必保留,供 AI 参考已有可识别样式
        return ChapterRules.DEFAULT.any { it.matches(line) }
    }
}

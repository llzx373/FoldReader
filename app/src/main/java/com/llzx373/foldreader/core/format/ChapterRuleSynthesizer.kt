package com.llzx373.foldreader.core.format

/**
 * 「选中行生成章节规则」的正则合成器。
 *
 * 从选区字符偏移吸附到源文件按 `\n` 分隔的原始行（[ChapterScanner] 的判定单位，
 * 不是排版折行后的显示行），再从该行合成整行匹配（^...$）的候选正则。
 * 纯 JVM，不依赖 Android。
 */
object ChapterRuleSynthesizer {

    private const val CN_DIGITS = "零一二三四五六七八九十百千万两"
    private const val ARABIC_CLASS = "[0-9０-９]+"
    private const val WS_CLASS = "[\\s　]*"
    /** 中文数字泛化的编号语境：前邻「第」或后邻章节量词（避免「两个」这类普通词被泛化）。 */
    private const val UNIT_CHARS = "章节卷回部篇"

    /**
     * 吸附 [offset] 所在的原始行：向前后找最近的 `\n`，截取后 trim（半角空白 + 全角空格，
     * 口径同 [ChapterScanner.processLine]）。空行 / 越界返回 null。
     */
    fun sourceLineAt(text: String, offset: Long): String? {
        if (text.isEmpty()) return null
        val index = offset.coerceIn(0L, text.length - 1L).toInt()
        var start = index
        while (start > 0 && text[start - 1] != '\n') start--
        var end = index
        while (end < text.length && text[end] != '\n') end++
        return text.substring(start, end)
            .trim { it <= ' ' || it == '　' }
            .takeIf { it.isNotEmpty() }
    }

    /**
     * 从样本行合成候选正则，按推荐度排序（数字泛化在前，精确匹配兜底）。
     * trim 后为空或超过 [ChapterRules.MAX_TITLE_LENGTH] 时返回空表（该行不可能被扫描器命中）。
     */
    fun synthesize(line: String): List<String> {
        val trimmed = line.trim { it <= ' ' || it == '　' }
        if (trimmed.isEmpty() || trimmed.length > ChapterRules.MAX_TITLE_LENGTH) return emptyList()
        val exact = buildPattern(trimmed, generalizeDigits = false)
        val generalized = buildPattern(trimmed, generalizeDigits = true)
        return if (generalized == exact) listOf(exact) else listOf(generalized, exact)
    }

    /**
     * 拼整行匹配正则：空白 run 折叠为 [WS_CLASS]（扫描器只 trim 行首尾的空白，
     * 行内空白原样保留，「第 3 章」这类带空变体要兼容）；generalizeDigits 时
     * 阿拉伯/全角数字 run → [ARABIC_CLASS]，有编号语境的中文数字 run → 中文数字字符类。
     */
    private fun buildPattern(line: String, generalizeDigits: Boolean): String {
        val out = StringBuilder("^")
        var i = 0
        while (i < line.length) {
            when {
                isBlankChar(line[i]) -> {
                    while (i < line.length && isBlankChar(line[i])) i++
                    out.append(WS_CLASS)
                }
                generalizeDigits && isArabicDigit(line[i]) -> {
                    while (i < line.length && isArabicDigit(line[i])) i++
                    out.append(ARABIC_CLASS)
                }
                generalizeDigits && isCnDigit(line[i]) && isNumberContext(line, i) -> {
                    while (i < line.length && isCnDigit(line[i])) i++
                    out.append('[').append(CN_DIGITS).append("]+")
                }
                else -> {
                    val segStart = i
                    i++
                    while (i < line.length && !startsSpecialToken(line, i, generalizeDigits)) i++
                    out.append(Regex.escape(line.substring(segStart, i)))
                }
            }
        }
        return out.append('$').toString()
    }

    private fun startsSpecialToken(line: String, index: Int, generalizeDigits: Boolean): Boolean {
        val c = line[index]
        if (isBlankChar(c)) return true
        if (!generalizeDigits) return false
        return isArabicDigit(c) || (isCnDigit(c) && isNumberContext(line, index))
    }

    private fun isBlankChar(c: Char) = c == '　' || c.isWhitespace()

    private fun isArabicDigit(c: Char) = c in '0'..'9' || c in '０'..'９'

    private fun isCnDigit(c: Char) = c in CN_DIGITS

    private fun isNumberContext(line: String, start: Int): Boolean {
        if (line.getOrNull(start - 1) == '第') return true
        var end = start
        while (end < line.length && isCnDigit(line[end])) end++
        return line.getOrNull(end)?.let { it in UNIT_CHARS } == true
    }
}

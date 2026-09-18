package com.llzx373.foldreader.core.format.clean

/**
 * 行内空白归一（问题 1、2 与「段首缩进不一致」）。
 *
 * 关键约束：**段首空白在这里只折叠成一个空格、不清除**。段落重组（[ParagraphRules]）要靠
 * 「下一行有没有缩进」判断它是不是新段落；段首缩进统一（[canonicalizeIndent]）必须等重组之后再
 * 执行——顺序反了，「续行」与「新段落」就再也分不出来了。
 *
 * 这里把所有空白（半角空格、全角空格 `\u3000`、制表符）一视同仁：`unifyChars` 关闭而
 * `collapseSpaces` 打开时，全角空格也得能折叠，不能只在 [CharRules] 先跑过的前提下成立。
 */
internal object WhitespaceRules {

    /** 段首缩进的标准形态：两个全角空格（视觉上正好两个字宽）。 */
    const val INDENT = "\u3000\u3000"

    private fun isSpace(c: Char): Boolean = c == ' ' || c == '\u3000' || c == '\t'

    /**
     * 行首有没有空白（段首缩进的信号）。
     *
     * 用 [Char::isWhitespace] 而不是只认半角空格：`unifyChars` 关闭、而
     * `reflowParagraphs` 打开时，缩进还是全角空格 `\u3000`，只认 `' '` 会把段落起点
     * 误当成续行，把整段粘成一坨。
     */
    fun hasLeadingWhitespace(line: String): Boolean = line.isNotEmpty() && line[0].isWhitespace()

    /** 去掉行尾空白。 */
    fun trimTrailing(line: String): String {
        var end = line.length
        while (end > 0 && isSpace(line[end - 1])) end--
        return if (end == line.length) line else line.substring(0, end)
    }

    /**
     * 行内连续空白折叠为一个空格；段首连续空白折叠为**一个**空格（保留「有缩进」这个信号）。
     * 全空白行归一为空串。
     *
     * 之后还会把**夹在两个中文字符之间的空格整段删掉**（问题 1 的真实形态）：
     * `他停下脚步， 看着远方` → `他停下脚步，看着远方`。中文字符之间不存在空格，
     * 而拉丁词句之间的空格是有效分隔，必须保留。
     */
    fun collapseRuns(line: String): String {
        if (line.isEmpty()) return ""
        var end = line.length - 1
        while (end >= 0 && isSpace(line[end])) end--
        if (end < 0) return ""

        val sb = StringBuilder(end + 1)
        var prevSpace = false
        for (i in 0..end) {
            if (isSpace(line[i])) {
                if (!prevSpace) sb.append(' ')
                prevSpace = true
            } else {
                sb.append(line[i])
                prevSpace = false
            }
        }
        return removeCjkAdjacentSpaces(sb.toString())
    }

    /** 删掉夹在两个中文字符（汉字/中文标点/全角/中英引号）之间的空格。 */
    fun removeCjkAdjacentSpaces(line: String): String {
        if (line.isEmpty()) return line
        val sb = StringBuilder(line.length)
        var i = 0
        while (i < line.length) {
            if (!isSpace(line[i])) {
                sb.append(line[i])
                i++
                continue
            }
            var j = i
            while (j < line.length && isSpace(line[j])) j++
            val prev = if (sb.isNotEmpty()) sb[sb.length - 1] else null
            val next = if (j < line.length) line[j] else null
            val drop = prev != null && next != null &&
                CharRules.isCjkLike(prev) && CharRules.isCjkLike(next)
            if (!drop) sb.append(' ')
            i = j
        }
        return sb.toString()
    }

    /** 段首缩进统一：有缩进的段落统一成两个全角空格；无缩进的原样（由排版层给标准首行缩进）。 */
    fun canonicalizeIndent(line: String): String {
        if (line.isEmpty() || !isSpace(line[0])) return line
        return INDENT + line.trimStart()
    }
}

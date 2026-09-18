package com.llzx373.foldreader.core.format.clean

/**
 * 段落重组（问题 3、4）。
 *
 * 判据**全部是局部的**——不看全书的句末标点比例、也不看全书缩进占比。这不只是简洁：
 * 任何「按采样算出来的全局开关」都会在清洗前后漂移，让 `clean(clean(x)) != clean(x)`，
 * 而「智能整理」要求幂等。局部判据只看相邻两行，第二次跑时相邻关系没变，结论自然一样。
 *
 * 分两条路：
 *
 * 1. **这一段是用缩进起头的**（[paragraphIndented]）→ 这本书用缩进标记段落，
 *    那么「下一行没有缩进、且中间没有空行」就是**权威**的续行信号，句末标点不作数。
 *    真实反例：`『原来你早就知道了。』` 之后紧跟 `他沉默着没有回答`——
 *    引号闭合了并不代表这段话结束，后面还有正文。
 *    这里**空行必须结束一段**：作者只会在段间留空行，而顶格的分篇标题正是「空行 +
 *    顶格」这种形态（合集类文件常见），并进去就再也出不来。这条同时是幂等的关键——
 *    空行被折成一个之后，判定必须与折叠前一致。
 * 2. 否则（没有缩进可依赖）→ 只用标点与行长判断：上一行不以句末标点结尾，
 *    且（引号没闭合 / 是 16 字以上的长行 / 以 `，、；：` 结尾）才合并；
 *    此时允许跨一个空行并（修「段落中间被插了空行」）。
 *    这一段是为「一段一行」的书准备的——它们行尾都是 `。`，天然不会被误并。
 */
internal object ParagraphRules {

    /** 句末标点：出现在行尾即认为这段话说完了。`，、；：` 不算，它们本身就是续行的强信号。 */
    private val SENTENCE_END = setOf(
        '。', '！', '？', '…', '”', '』', '」', '》', '〉', '】', '〕', '）',
        '!', '?', '．', '.',
    )

    /** 续行标点：行尾是这些，几乎一定还有下半句。 */
    private val CONTINUATION = setOf('，', '、', '；', '：', '—', ',', ';', ':', '·')

    /** 新段落开局：对话以引号起头，通常就是新的一段。 */
    private val PARAGRAPH_OPEN = setOf('“', '「', '『', '‘')

    /** 见类注释：短于此的行不认定为「被硬换行切断」。 */
    private const val WRAP_MIN_CHARS = 16

    /**
     * [next] 是否应并入 [prev]。
     *
     * @param paragraphIndented 当前这一段是**用缩进起头的**——即这本书用缩进标记段落。
     *   为真时「下一行没有缩进、中间没有空行」就是权威的续行信号，直接并；句末标点不作数。
     * @param blankLinesBetween 两行之间夹着的空行数（已由上游折过，0 或 1 之后也可能更大）。
     */
    fun shouldMerge(
        prev: String,
        next: String,
        paragraphIndented: Boolean,
        blankLinesBetween: Int,
    ): Boolean {
        if (prev.isBlank() || next.isBlank()) return false
        if (WhitespaceRules.hasLeadingWhitespace(next)) return false // 有缩进 → 新段落
        if (ChapterRepairRules.looksLikeTitle(prev) || ChapterRepairRules.looksLikeTitle(next)) return false
        // 缩进分段的书：空行一定结束一段（顶格的分篇标题就是「空行 + 顶格」），
        // 而且这条在空行折叠前后都成立 → 幂等。
        if (paragraphIndented) return blankLinesBetween == 0
        if (blankLinesBetween > 1) return false
        val last = lastVisible(prev)
        if (last == null || last in SENTENCE_END) return false
        if (hasUnclosedQuote(prev)) return true
        val first = next.trimStart().firstOrNull() ?: return false
        if (first in PARAGRAPH_OPEN) return false
        if (prev.trim().length >= WRAP_MIN_CHARS) return true
        return last in CONTINUATION
    }

    /** 行内开引号多于闭引号。 */
    fun hasUnclosedQuote(line: String): Boolean {
        var open = 0
        var close = 0
        for (c in line) {
            when (c) {
                '\u201C', '\u300C', '\u300E' -> open++
                '\u201D', '\u300D', '\u300F' -> close++
            }
        }
        return open > close
    }

    /**
     * 合并两行。中文直接相接；只有两侧都是 ASCII 词字符时才补一个空格，
     * 避免把被硬换行切开的英文单词粘成一个。
     */
    fun join(prev: String, next: String): String {
        val a = prev.trimEnd()
        val b = next.trimStart()
        if (b.isEmpty()) return a
        if (a.isEmpty()) return b
        val lastA = a.last()
        val firstB = b.first()
        return if (isAsciiWordChar(lastA) && isAsciiWordChar(firstB)) "$a $b" else a + b
    }

    private fun lastVisible(line: String): Char? {
        var i = line.length - 1
        while (i >= 0) {
            val c = line[i]
            if (c != ' ' && c != '\u3000' && c != '\t') return c
            i--
        }
        return null
    }

    private fun isAsciiWordChar(c: Char): Boolean =
        c in '0'..'9' || c in 'a'..'z' || c in 'A'..'Z'
}

package com.llzx373.foldreader.core.format.clean

/**
 * 段落重组（问题 3、4）。
 *
 * 判据**全部是局部的**——不看全书的句末标点比例、也不看全书缩进占比。这不只是简洁：
 * 任何「按采样算出来的全局开关」都会在清洗前后漂移，让 `clean(clean(x)) != clean(x)`，
 * 而「智能整理」要求幂等。局部判据只看相邻两行，第二次跑时相邻关系没变，结论自然一样。
 *
 * 分三条路，按下面的顺序判：
 *
 * 1. **下一行带缩进** → 看**上一行有没有说完话**（不以句末标点收尾）。
 *    大多数书里缩进就等于新段落，这条立刻结束；但有一类排版版（下载站的精校版：
 *    全篇**每一行**都缩进、行与行之间还留空行），续行同样带缩进——此时缩进不再是段落信号，
 *    唯一可靠的判据只剩「上一行说完了没有」。空行在这里也不作数：它只是双倍行距。
 *    收尾的行仍然按新段落算，所以正常书的行为一个字不变。
 * 2. **这一段是用缩进起头的**（[paragraphIndented]）、而下一行顶格 → 这本书用缩进标记段落，
 *    那么「下一行没有缩进、且中间没有空行」就是**权威**的续行信号，句末标点不作数。
 *    真实反例：`『原来你早就知道了。』` 之后紧跟 `他沉默着没有回答`——
 *    引号闭合了并不代表这段话结束，后面还有正文。
 *    这里**空行必须结束一段**：作者只会在段间留空行，而顶格的分篇标题正是「空行 +
 *    顶格」这种形态（合集类文件常见），并进去就再也出不来。这条同时是幂等的关键——
 *    空行被折成一个之后，判定必须与折叠前一致。
 * 3. 否则（两行都不缩进）→ 只用标点与行长判断：上一行不以句末标点结尾，
 *    且（引号没闭合 / 是 16 字以上的长行 / 以 `，、；：` 结尾）才合并；
 *    此时允许跨一个空行并（修「段落中间被插了空行」）。
 *    这一段是为「一段一行」的书准备的——它们行尾都是 `。`，天然不会被误并。
 *
 * 1 与 2 的次序不能反：先按缩进决定，第 1 条就把「每行都缩进」的排版版钉死在「不合」上了。
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
        if (ChapterRepairRules.looksLikeTitle(prev) || ChapterRepairRules.looksLikeTitle(next)) return false
        // 顶格的分篇标题（`【第二篇】…`）自成一体：既不能被并进上一段，也不能把下一段并进来。
        // 只在标点判据下拦不住的正是这一条——合集里的分篇标题顶格写、正文也顶格写，
        // 两行都不带缩进，光靠标点分不出「标题」与「续行」，标题就被粘进了正文首行。
        if (ChapterRepairRules.looksLikeFlushHeader(prev) || ChapterRepairRules.looksLikeFlushHeader(next)) return false

        val last = lastVisible(prev)

        // 「每一行都带缩进」的排版版（下载站常见的精校版：全篇每行缩进，行与行之间还留空行，
        // 段落被硬换行切成一段多行）。这种文件里缩进**不是**段落信号——续行同样缩进，
        // 于是「有缩进 → 新段落」会让整本一行都合不上（实测一本 2348 非空行的排版版，
        // 旧判据合并 0 次）。此时退回唯一可靠的判据：**上一行有没有说完话**，
        // 且空行也不作数——它只是双倍行距，不是段间分隔。
        if (WhitespaceRules.hasLeadingWhitespace(next)) {
            if (last == null || last in SENTENCE_END) return false // 收尾了 → 缩进照旧按新段落算
            if (hasUnclosedQuote(prev)) return true
            // 下一行以引号起头 = 新对话。网文里「上一段结尾漏个标点」很常见，
            // 少了这一条，那段对话会被整段吞进上一句里。
            val nextFirst = next.trimStart().firstOrNull() ?: return false
            if (nextFirst in PARAGRAPH_OPEN) return false
            // 短行不认定为「被硬换行切断」——与下面标点路径共用同一个阈值
            return prev.trim().length >= WRAP_MIN_CHARS
        }

        // 缩进分段的书：空行一定结束一段（顶格的分篇标题就是「空行 + 顶格」），
        // 而且这条在空行折叠前后都成立 → 幂等。
        if (paragraphIndented) return blankLinesBetween == 0
        if (blankLinesBetween > 1) return false
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

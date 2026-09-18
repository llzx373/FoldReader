package com.llzx373.foldreader.core.format.clean

/**
 * 段落重组（问题 3、4）。
 *
 * 判据**全部是局部的**——不看全书的句末标点比例、也不看全书缩进占比。这不只是简洁：
 * 任何「按采样算出来的全局开关」都会在清洗前后漂移，让 `clean(clean(x)) != clean(x)`，
 * 而「智能整理」要求幂等。局部判据只看相邻两行，第二次跑时相邻关系没变，结论自然一样。
 *
 * 合并条件（全部满足）：
 * 1. 两行都非空；
 * 2. 下一行**没有缩进**（有缩进 = 新段落）；
 * 3. 两行都不是章节标题；
 * 4. 上一行不以句末标点结尾；
 * 5. 下一行不以引号开头（对话换人）；
 * 6. 满足其一：上一行引号没闭合（问题 7）／上一行够长（被硬换行切断）／上一行以 `，、；：` 结尾。
 *
 * 条件 6 的「够长」是关键：中文网文的硬换行普遍 25–50 字，所以 `≥16 字且不以句末标点结尾`
 * 基本只可能是被切断的。反过来，诗行、短句列表都因行短而天然不合并（见 `negatives/05`）。
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

    /** [next] 是否应并入 [prev]。 */
    fun shouldMerge(prev: String, next: String): Boolean {
        if (prev.isBlank() || next.isBlank()) return false
        if (next[0] == ' ') return false
        if (ChapterRepairRules.isTitle(prev) || ChapterRepairRules.isTitle(next)) return false
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

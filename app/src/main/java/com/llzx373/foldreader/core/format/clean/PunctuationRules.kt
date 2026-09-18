package com.llzx373.foldreader.core.format.clean

/**
 * 标点规整（问题 7 与「标点不规范」）。
 *
 * 分两段执行、不是随手为之：
 * - [normalizePre] 在**段落重组之前**跑。省略号归位后，`prev` 尾字符是不是句末标点才判得准
 *   （一个 `......` 结尾的行本来就该算句子结束）。
 * - [normalizeQuotes] 在**段落重组之后**跑。问题 7 的「引号里被换行拆开」要靠重组先拼回一句话，
 *   引号内空白才有意义可谈；引号字形的配对也以整段为界最自然。
 */
internal object PunctuationRules {

    /** 句末标点：这些字符出现在引号内时，行尾空格要一并收掉。 */
    private val QUOTE_OPEN = mapOf(
        '\u201C' to '\u201D', // “ ”
        '\u2018' to '\u2019', // ‘ ’
        '\u300C' to '\u300D', // 「 」
        '\u300E' to '\u300F', // 『 』
    )

    private val CLOSERS = QUOTE_OPEN.values.toSet() + setOf('\u201D', '\u2019', '\u300D', '\u300F')

    private val ELLIPSIS_DOTS = Regex("\\.{3,}")
    private val ELLIPSIS_IDEOGRAPHIC = Regex("\u3002{2,}")
    private val ELLIPSIS_FULLWIDTH_STOP = Regex("\uFF0E{2,}")
    private val ELLIPSIS_MARK = Regex("\u2026{1,}")
    private const val ELLIPSIS = "\u2026\u2026"

    private val DASH_EM = Regex("\u2014{2,}")
    private val DASH_BAR = Regex("\u2015{2,}")
    private val DASH_BOX = Regex("\u2500{2,}")
    private val DASH_HYPHEN = Regex("(?<![0-9A-Za-z])-{2,}(?![0-9A-Za-z])")
    private const val DASH = "\u2014\u2014"

    /** 可折叠的重复标点。`…` 与 `—` 不在其中：它们本来就成对出现，折叠会破坏省略号/破折号。 */
    private val REPEATABLE = Regex("([，。！？；：、,.!?;:])\\1+")
    private val REPEATABLE_CHARS = setOf('，', '。', '！', '？', '；', '：', '、', ',', '.', '!', '?', ';', ':')

    /** 省略号与破折号规整。返回规整后的行（无改动时返回原实例）。 */
    fun normalizePre(line: String): String {
        if (line.isEmpty()) return line
        var out = line
        if (out.contains('\u2026')) out = ELLIPSIS_MARK.replace(out, ELLIPSIS)
        if (out.contains('\u3002')) out = ELLIPSIS_IDEOGRAPHIC.replace(out, ELLIPSIS)
        if (out.contains('\uFF0E')) out = ELLIPSIS_FULLWIDTH_STOP.replace(out, ELLIPSIS)
        if (out.contains('.')) out = ELLIPSIS_DOTS.replace(out, ELLIPSIS)
        if (out.contains('\u2014')) out = DASH_EM.replace(out, DASH)
        if (out.contains('\u2015')) out = DASH_BAR.replace(out, DASH)
        if (out.contains('\u2500')) out = DASH_BOX.replace(out, DASH)
        if (out.contains('-')) out = DASH_HYPHEN.replace(out, DASH)
        return out
    }

    /**
     * 重复标点折叠（激进档）。必须在 [normalizePre] **之后**跑：先让省略号归位成 `……`，
     * 这里才敢把连续标点折成一个。
     */
    fun collapseRepeated(line: String): String {
        if (line.isEmpty()) return line
        if (line.none { it in REPEATABLE_CHARS }) return line
        return REPEATABLE.replace(line, "$1")
    }

    /**
     * 引号规整：去掉引号**内侧**紧贴引号的多余空格，以及引号内夹在两个汉字之间的空格
     * （问题 7 在重组后剩下的那种）。
     *
     * 引号外的空格一律不动——中文里夹拉丁词句时那是有效分隔。
     */
    fun normalizeQuotes(line: String): String {
        if (line.isEmpty()) return line
        val sb = StringBuilder(line.length)
        var closeOf: Char? = null
        var pendingSpace = false

        fun flushPending(next: Char?) {
            if (!pendingSpace) return
            pendingSpace = false
            if (next == null) return // 行尾待定空格丢弃
            val prev = if (sb.isNotEmpty()) sb[sb.length - 1] else return
            if (next in CLOSERS) return
            if (CharRules.isCjkLike(prev) && CharRules.isCjkLike(next)) return
            sb.append(' ')
        }

        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (closeOf != null) {
                if (c == ' ') {
                    pendingSpace = true
                    i++
                    continue
                }
                flushPending(c)
                if (c == closeOf) closeOf = null
                sb.append(c)
                i++
                continue
            }
            if (c == ' ') {
                sb.append(' ')
                i++
                continue
            }
            QUOTE_OPEN[c]?.let { closeOf = it }
            sb.append(c)
            i++
        }
        flushPending(null)

        val out = sb.toString()
        return if (out == line) line else out
    }
}

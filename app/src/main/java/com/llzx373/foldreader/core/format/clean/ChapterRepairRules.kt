package com.llzx373.foldreader.core.format.clean

import com.llzx373.foldreader.core.format.ChapterRules

/**
 * 章节标题修复（问题 5、6 与「章节结构层」补充项）。
 *
 * 三件事：
 * 1. **行中标题提行**：上一章的末行与新章标题被粘成一行时，把标题切出来单独成行
 *    （[ChapterScanner] 是整行匹配，粘在一起就永远认不出来）。
 * 2. **命名规范化**：`第 1 章` → `第1章`、全角数字归一。**不擅自改序号类型**
 *    （`第1章` 不会变成 `第一章`），也补不出缺失的章号。
 * 3. **重复标题与目录块**：紧挨着重复的同一标题删一个；正文开头连续多个标题判为目录块。
 *
 * 只做「把已有标题摆正」，不做「猜一个标题出来」——缺章、乱序只进报告，不改正文。
 */
internal object ChapterRepairRules {

    private const val CN_NUM = "0-9０-９零一二三四五六七八九十百千万两"

    /** 连续多少个标题算目录块。真章节之间必然夹着正文，所以 3 个已经足够保守。 */
    const val MIN_TOC_ENTRIES = 3

    /** 行中标题的正文侧至少要有多长才敢切——短于此多半是「他翻到第一章」这种叙述，不是标题。 */
    private const val MIN_BODY_CHARS = 6

    /**
     * 行尾的标题形态：`...正文...第十章 风起`。
     *
     * 标题尾部**不允许出现句末标点**——`第一章 风起他又走了两步。` 是正文粘上了标题，
     * 不是标题；这条排除了它（内置章节规则的 `[^\n]{0,35}` 容差太大，光靠它分不出来）。
     */
    private val MID_TITLE = Regex("第\\s*[$CN_NUM]{1,10}\\s*[章节卷回部篇]\\s*[^\\n。！？]{0,30}$")

    private val SPACED_HEAD = Regex("^第\\s*([$CN_NUM]{1,10})\\s*([章节卷回部篇])\\s*(.*)$")

    private val TOC_HEADER = Regex("^\\s*目\\s*录\\s*$")

    /** 是否是章节标题行（与 [ChapterScanner] 同规则：先 trim，再整行匹配）。 */
    fun isTitle(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.length > ChapterRules.MAX_TITLE_LENGTH) return false
        return ChapterRules.DEFAULT.any { it.matches(trimmed) }
    }

    /**
     * 行是不是章节标题，**含需要先规范化才认得出的写法**（`第 2 章 风起云涌`——内置规则
     * 不允许 `第` 与数字之间有空格）。
     *
     * 段落重组要用这个：只用 [isTitle] 的话，`第 2 章 …` 会被当成正文并进上一段，
     * 标题就再也提不出来了。
     */
    fun looksLikeTitle(line: String): Boolean = isTitle(canonicalizeTitle(line))

    fun isTocHeader(line: String): Boolean = TOC_HEADER.matches(line)

    /**
     * 把行中的章节标题切出来。返回 `(正文, 标题)`；行本身就是标题或找不到标题时返回 null。
     *
     * 标题侧会先做命名规范化再判定——`第 1 章 风起` 这种带空格的写法内置规则不认，
     * 但它确实是标题（问题 6）。
     */
    fun splitMidLineTitle(line: String): Pair<String, String>? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        if (isTitle(trimmed)) return null
        val match = MID_TITLE.findAll(trimmed).lastOrNull() ?: return null
        // 标题必须在行尾：切出来的应当是「正文 + 标题」，不是「正文 + 标题 + 更多正文」
        if (match.range.last != trimmed.length - 1) return null
        val body = trimmed.substring(0, match.range.first).trim()
        if (body.length < MIN_BODY_CHARS) return null
        val title = canonicalizeTitle(trimmed.substring(match.range.first))
        if (!isTitle(title)) return null
        return body to title
    }

    /** 命名规范化：去 `第 1 章` 里的空格、全角数字转半角；其余样式原样返回。 */
    fun canonicalizeTitle(raw: String): String {
        val trimmed = raw.trim()
        val match = SPACED_HEAD.matchEntire(trimmed) ?: return trimmed
        val number = halfWidthDigits(match.groupValues[1])
        val unit = match.groupValues[2]
        val rest = match.groupValues[3].trim()
        return if (rest.isEmpty()) "第$number$unit" else "第$number$unit $rest"
    }

    private fun halfWidthDigits(text: String): String {
        if (text.none { it in '\uFF10'..'\uFF19' }) return text
        val sb = StringBuilder(text.length)
        for (c in text) sb.append(if (c in '\uFF10'..'\uFF19') c - 0xFEE0 else c)
        return sb.toString()
    }
}

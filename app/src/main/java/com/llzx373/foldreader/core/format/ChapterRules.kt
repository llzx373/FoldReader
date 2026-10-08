package com.llzx373.foldreader.core.format

/**
 * 章节标题的判定规则。
 *
 * **刻意与 [ChapterScanner] 分文件**：这里是纯判据（只有正则），不碰 Android；
 * [ChapterScanner] 所在文件依赖 `BookParser` 的 [Chapter]，那是带 `android.net.Uri` 的。
 * 分开之后，桌面清理工具（`tools/cleaner`）可以直接复用 `core/format/clean/` 整包 + 本文件，
 * 不必复制一份规则。
 */
object ChapterRules {
    private const val CN_NUM = "0-9０-９零一二三四五六七八九十百千万两"
    /** 空白（含全角空格 U+3000）：书源里「第 1 章」「第　3　章」这类编号带空格的变形很常见 */
    private const val WS = "\\s\\u3000"
    const val MAX_TITLE_LENGTH = 40

    val DEFAULT: List<Regex> = listOf(
        Regex("^(?:正文|序卷|作品相关|VIP卷)?[$WS]*第[$WS]*[$CN_NUM]+[$WS]*[章节卷回部篇][^\\n]{0,35}$"),
        Regex("^[Cc][Hh][Aa][Pp][Tt][Ee][Rr]\\s*\\d{1,6}[^\\n]{0,35}$"),
        Regex("^\\d{1,6}[、.．][$WS]*\\S[^\\n]{0,34}$"),
        Regex("^(?:楔子|序章|序言|引子|前言|终章|尾声|特别番外|番外篇?)[^\\n]{0,35}$"),
        // 大写数字直接作编号：「零. 楔子」「壹. 密室」「贰拾叁. 夜袭」，精校/合集 TXT 常见。
        // 只收大写数字：小写「三、……」在正文枚举里常见，容易误伤。
        Regex("^[零壹贰叁肆伍陆柒捌玖拾佰仟]+[、.．][$WS]*\\S[^\\n]{0,34}$"),
    )

    fun merge(customPatterns: List<String>): List<Regex> =
        customPatterns.mapNotNull { runCatching { Regex(it) }.getOrNull() } + DEFAULT
}

package com.llzx373.foldreader.core.format.clean

/**
 * 字符级归一（问题 1 与「字符与编码层」补充项）。
 *
 * 只做**可判定、可幂等**的替换：不可见字符、全角空格/制表符、全角数字字母、HTML 实体与标签。
 * 标点不做全角/半角互转——`，。！？` 是正确的中文标点，把它们转成 `,!?` 是破坏而非修复。
 */
internal object CharRules {

    private val INVISIBLE: Set<Char> = setOf(
        '\uFEFF', // BOM
        '\u200B', // 零宽空格
        '\u200C', // 零宽非连接符
        '\u200D', // 零宽连接符
        '\u2060', // word joiner
        '\u00AD', // 软连字符
    )

    private val NAMED_ENTITIES: Map<String, String> = mapOf(
        "nbsp" to " ",
        "amp" to "&",
        "lt" to "<",
        "gt" to ">",
        "quot" to "\"",
        "apos" to "'",
        "ldquo" to "\u201C",
        "rdquo" to "\u201D",
        "lsquo" to "\u2018",
        "rsquo" to "\u2019",
        "hellip" to "\u2026",
        "mdash" to "\u2014",
        "ndash" to "\u2013",
        "middot" to "\u00B7",
        "bull" to "\u2022",
        "times" to "\u00D7",
        "divide" to "\u00F7",
        "copy" to "\u00A9",
        "reg" to "\u00AE",
        "trade" to "\u2122",
        "deg" to "\u00B0",
        "plusmn" to "\u00B1",
        "laquo" to "\u00AB",
        "raquo" to "\u00BB",
        "lsaquo" to "\u2039",
        "rsaquo" to "\u203A",
        "permil" to "\u2030",
        "prime" to "\u2032",
        "Prime" to "\u2033",
        "sect" to "\u00A7",
        "para" to "\u00B6",
        "yen" to "\u00A5",
        "pound" to "\u00A3",
        "euro" to "\u20AC",
        "cent" to "\u00A2",
        "sup2" to "\u00B2",
        "sup3" to "\u00B3",
        "frac12" to "\u00BD",
        "ensp" to " ",
        "emsp" to " ",
        "thinsp" to " ",
    )

    private val ENTITY = Regex("&(#x?[0-9A-Fa-f]{1,6}|[A-Za-z][A-Za-z0-9]{1,9});")

    /** `<br>` `<p class="x">` `</div>` `<!-- 注释 -->`。要求 `<` 后是字母/`!`，避免误伤 `a<b` 这类正文。 */
    private val HTML_TAG = Regex("<!--.*?-->|</?\\s*[A-Za-z][A-Za-z0-9]*(?:\\s[^<>]{0,160})?\\s*/?>")

    /** UBB / 论坛标签：`[b]` `[/color]` `[url=...]` `[quote]`。 */
    private val UBB_TAG = Regex(
        "\\[/?(?:b|i|u|s|strike|color|size|font|face|url|img|quote|code|align|center|table|tr|td|th|list|hr|spoiler|hide|flash|media|audio|video|indent|fly|move|glow|shadow|email|sup|sub|backcolor)\\b(?:=[^\\[\\]]{0,160})?\\]",
        RegexOption.IGNORE_CASE,
    )

    /** 全角数字与拉丁字母（不动标点与日文假名）。 */
    private fun halfWidthAscii(c: Char): Char = when {
        c in '\uFF10'..'\uFF19' -> c - 0xFEE0
        c in '\uFF21'..'\uFF3A' -> c - 0xFEE0
        c in '\uFF41'..'\uFF5A' -> c - 0xFEE0
        else -> c
    }

    /**
     * 归一一行；没有可归一时返回原实例。
     *
     * 顺序：实体 → 标签 → 不可见字符 → 全角空格/制表符 → 全角数字字母。
     * 实体必须最先解，否则 `&nbsp;` 里的字母会被全角化规则碰到。
     */
    fun normalize(line: String): String {
        if (line.isEmpty()) return ""
        var out = line
        if (out.indexOf('&') >= 0) out = decodeEntities(out)
        if (out.indexOf('<') >= 0) out = HTML_TAG.replace(out, "")
        if (out.indexOf('[') >= 0) out = UBB_TAG.replace(out, "")

        val sb = StringBuilder(out.length)
        for (c in out) {
            when {
                c in INVISIBLE -> Unit
                c == '\u3000' -> sb.append(' ') // 全角空格
                c == '\t' -> sb.append(' ')
                c == '\r' -> Unit
                c < ' ' -> Unit // C0 控制字符（\n 不会出现在行内）
                c == '\u007F' -> Unit
                else -> sb.append(halfWidthAscii(c))
            }
        }
        return sb.toString()
    }

    /**
     * 是否「中文侧」字符：汉字、中文标点、全角字符、中英文引号与省略号/破折号。
     * 用来判断两个字符之间夹的空格是不是中文排版里不存在的噪音（问题 1）。
     */
    fun isCjkLike(c: Char): Boolean {
        val code = c.code
        return code in 0x4E00..0x9FFF ||
            code in 0x3400..0x4DBF ||
            code in 0xF900..0xFAFF ||
            code in 0x3000..0x303F ||
            code in 0xFF00..0xFFEF ||
            code in 0x2014..0x201F || // — – ‘ ’ “ ” † ‡ • …
            code in 0x2026..0x2027
    }

    /** 解 HTML 实体；认不出的实体原样保留（不猜）。 */
    private fun decodeEntities(text: String): String =
        ENTITY.replace(text) { m ->
            val body = m.groupValues[1]
            if (body.startsWith("#")) {
                val hex = body.startsWith("#x") || body.startsWith("#X")
                val digits = body.substring(if (hex) 2 else 1)
                val code = digits.toIntOrNull(if (hex) 16 else 10)
                if (code != null && code in 1..0x10FFFF) String(Character.toChars(code)) else m.value
            } else {
                NAMED_ENTITIES[body] ?: NAMED_ENTITIES[body.lowercase()] ?: m.value
            }
        }
}

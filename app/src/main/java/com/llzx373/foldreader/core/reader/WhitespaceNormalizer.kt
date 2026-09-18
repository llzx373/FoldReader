package com.llzx373.foldreader.core.reader

/** 一律折叠为零宽的不可见字符：BOM、零宽空格/连接符、word joiner、软连字符。 */
private val INVISIBLE_CHARS: Set<Char> = setOf(
    '\uFEFF', '\u200B', '\u200C', '\u200D', '\u2060', '\u00AD',
)

/** 测量掩码用的零宽占位字符，与被折叠字符等长。 */
private const val ZERO_WIDTH_PLACEHOLDER = '\u200B'

/**
 * 空白归一化掩码：`true` = 该字符按原样占宽，`false` = 折叠为零宽。
 *
 * 规则（纯函数；测量与绘制两侧共用同一份结果，保证断行与绘制一致）：
 * 1. 段首连续空白整段折叠 —— 缩进改由标准首行缩进提供；
 * 2. 行尾连续空白整段折叠；
 * 3. 行内连续空白（≥2 个）折叠为一个，保留首个；
 * 4. 不可见字符逐个折叠。
 *
 * 整行都是空白时返回全 `true`：这类段落当空行处理，不该给它加缩进后留下孤零零的缩进。
 */
internal fun normalizedWidthMask(text: CharSequence): BooleanArray {
    val n = text.length
    val mask = BooleanArray(n) { true }
    if (n == 0) return mask
    if (text.all { it.isWhitespace() }) return mask

    // 1. 段首空白
    var head = 0
    while (head < n && text[head].isWhitespace()) head++
    for (i in 0 until head) mask[i] = false

    // 2. 行尾空白
    var tail = n
    while (tail > head && text[tail - 1].isWhitespace()) tail--
    for (i in tail until n) mask[i] = false

    // 3. 行内连续空白：保留首个，其后折叠
    var prevWasSpace = false
    for (i in head until tail) {
        if (text[i].isWhitespace()) {
            if (prevWasSpace) mask[i] = false
            prevWasSpace = true
        } else {
            prevWasSpace = false
        }
    }

    // 4. 不可见字符
    for (i in 0 until n) {
        if (text[i] in INVISIBLE_CHARS) mask[i] = false
    }
    return mask
}

/**
 * 供测量器使用的等长掩码文本：被折叠字符换成零宽占位符。
 * 长度不变 ⇒ `StaticLayout` 的断行下标直接就是原文下标，无需索引映射表；
 * 零宽 ⇒ 断行按折叠后的宽度计算，首行不会短一截。
 * 无需折叠时原样返回，避免无谓分配。
 */
internal fun maskedForMeasure(text: CharSequence): CharSequence {
    val mask = normalizedWidthMask(text)
    if (mask.all { it }) return text
    return buildString(text.length) {
        for (i in text.indices) append(if (mask[i]) text[i] else ZERO_WIDTH_PLACEHOLDER)
    }
}

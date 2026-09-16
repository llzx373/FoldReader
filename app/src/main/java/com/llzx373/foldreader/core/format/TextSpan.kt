package com.llzx373.foldreader.core.format

/** 文本样式/结构 span 类型：span 不影响分页度量，仅渲染期生效（粗斜体/上下标/链接着色/图片行）。 */
enum class TextSpanType {
    BOLD,
    ITALIC,
    SUP,
    SUB,
    /** 内部链接 payload = "#目标charOffset"；外部链接 payload = http(s) URL。 */
    LINK,
    /** epub:type="noteref" 的注释引用；payload 编码同 LINK。 */
    NOTEREF,
    /** 图片占位段落（单 U+FFFC 字符）；payload = zip 内图片路径，width/height 为原始像素尺寸。 */
    IMAGE,
}

/**
 * 压平流绝对 char 偏移区间 [start, end) 上的样式/结构标记。
 * payload/alt 按需使用（见 [TextSpanType] 注释）；width/height 仅 IMAGE 使用（未知为 0）。
 */
data class TextSpan(
    val type: TextSpanType,
    val start: Long,
    val end: Long,
    val payload: String? = null,
    val alt: String? = null,
    val width: Int = 0,
    val height: Int = 0,
)

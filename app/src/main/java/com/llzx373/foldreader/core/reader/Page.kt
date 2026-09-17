package com.llzx373.foldreader.core.reader

import androidx.compose.runtime.Immutable
import com.llzx373.foldreader.core.format.TextSpan

/**
 * Compose 稳定性：全字段 val、集合不做就地修改，声明为不可变后
 * 接收它的 Composable 才可能跳过重组（编译器无法自行证明这一点）。
 */
@Immutable
data class PageLine(
    val charStart: Long,
    val charEnd: Long,
    val text: String,
    val isParagraphStart: Boolean,
    val isParagraphEnd: Boolean,
    /** 行高覆盖（图片行按缩放后实际像素高）；null = 默认 lineHeightPx。 */
    val heightPx: Float? = null,
    /** 图片行：zip 内图片路径（本行 text 为单 U+FFFC 占位字符）；null = 文本行。 */
    val imagePath: String? = null,
    /** 图片行 alt 文本（占位灰框上显示）。 */
    val imageAlt: String? = null,
    /**
     * 本行内的样式 span（绝对偏移，已按行区间截断）；
     * 渲染期挂载（span 不进任何缓存），默认空。
     */
    val spans: List<TextSpan> = emptyList(),
)

@Immutable
data class Page(
    val charStart: Long,
    val charEnd: Long,
    val lines: List<PageLine>,
    val paddingLeft: Float,
    val paddingRight: Float,
) {
    val isEmpty: Boolean get() = charEnd <= charStart
}

package com.llzx373.foldreader.core.reader

data class PageLine(
    val charStart: Long,
    val charEnd: Long,
    val text: String,
    val isParagraphStart: Boolean,
    val isParagraphEnd: Boolean,
)

data class Page(
    val charStart: Long,
    val charEnd: Long,
    val lines: List<PageLine>,
    val paddingLeft: Float,
    val paddingRight: Float,
) {
    val isEmpty: Boolean get() = charEnd <= charStart
}

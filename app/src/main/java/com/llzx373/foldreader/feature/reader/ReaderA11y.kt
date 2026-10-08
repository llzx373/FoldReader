package com.llzx373.foldreader.feature.reader

import com.llzx373.foldreader.core.reader.Page
import com.llzx373.foldreader.core.translate.ComicPageTranslation

/**
 * TalkBack 无障碍（M35）的纯逻辑部分：语义树文本拼装。
 * Compose 接线在各阅读器屏幕层（contentDescription / liveRegion / 自定义动作）。
 */

/**
 * 文本书一页的播报文本：文本行直拼（行内本无换行，分页时已按视觉行切好）；
 * 图片行替换为占位描述——alt 为空也保留占位，不留静默空洞。
 */
fun Page.accessibilityText(): String =
    lines.joinToString("") { line ->
        if (line.imagePath != null) {
            val alt = line.imageAlt?.takeIf { it.isNotBlank() }
            if (alt != null) "［图片：$alt］" else "［图片］"
        } else {
            line.text
        }
    }

/**
 * 页式阅读（漫画 / PDF 页模式）的页面语义：「第 N 页，共 M 页」（页序号 0 基，播报 1 基）。
 * 本页有译文覆盖层时把气泡译文一并播报——读屏用户拿不到画面上的译文覆盖层，
 * 语义树是它唯一的载体。
 */
fun pagedPageDescription(
    pageIndex: Int,
    pageCount: Int,
    translation: ComicPageTranslation? = null,
): String {
    val base = "第 ${pageIndex + 1} 页，共 $pageCount 页"
    val texts = translation?.bubbles
        ?.mapNotNull { it.text?.takeIf(String::isNotBlank) }
        .orEmpty()
    return if (texts.isEmpty()) base else base + "。译文：" + texts.joinToString("；")
}

/** 双页跨页的页面语义：两页区间 + 各自译文（右页缺页=奇数页结尾只报一页）。 */
fun pagedSpreadDescription(
    pages: List<Int>,
    pageCount: Int,
    translationFor: (Int) -> ComicPageTranslation? = { null },
): String {
    if (pages.isEmpty()) return "共 $pageCount 页"
    val range = if (pages.size == 1) {
        "第 ${pages.first() + 1} 页"
    } else {
        "第 ${pages.first() + 1} 至 ${pages.last() + 1} 页"
    }
    val texts = pages.flatMap { page ->
        translationFor(page)?.bubbles?.mapNotNull { it.text?.takeIf(String::isNotBlank) }.orEmpty()
    }
    val base = "$range，共 $pageCount 页"
    return if (texts.isEmpty()) base else base + "。译文：" + texts.joinToString("；")
}

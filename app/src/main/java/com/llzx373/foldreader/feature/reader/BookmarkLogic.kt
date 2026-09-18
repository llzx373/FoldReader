package com.llzx373.foldreader.feature.reader

import com.llzx373.foldreader.core.data.db.AnnotationEntity
import com.llzx373.foldreader.core.data.db.BookmarkEntity

const val BOOKMARK_SNAPSHOT_MAX_LEN = 24

/** 书签快照：去掉首尾/内部多余空白与换行，截断到 [maxLen] 字。 */
fun bookmarkSnapshotOf(excerpt: String, maxLen: Int = BOOKMARK_SNAPSHOT_MAX_LEN): String {
    val cleaned = excerpt.replace(Regex("\\s+"), "")
    return cleaned.take(maxLen)
}

/** toggle 语义：精确字符偏移已有书签则返回该书签（调用方删除），否则返回 null（调用方新增）。 */
fun findBookmarkAt(bookmarks: List<BookmarkEntity>, charOffset: Long): BookmarkEntity? =
    bookmarks.firstOrNull { it.charOffset == charOffset }

/** 书签列表排序：创建时间倒序（新 → 旧）。 */
fun sortBookmarksByRecency(bookmarks: List<BookmarkEntity>): List<BookmarkEntity> =
    bookmarks.sortedByDescending { it.createdAt }

/**
 * 页内锚点的命中容差（归一化单位，约页宽 3%）。
 *
 * 页内坐标来自手指落点，要求浮点完全相等等于永远找不到已有书签，toggle 会退化成一直新增。
 * 容差也不能大：一页上可以有多条书签，容差太大就会把「旁边的另一条」当成同一条删掉。
 */
const val PAGE_ANCHOR_TOLERANCE = 0.03f

/**
 * 页式书签的 toggle 判定：同页且页内锚点在容差内算同一条。
 *
 * `anchorX/anchorY` 为 null 表示「只锚到页」（顶栏书签按钮加的），这种只跟同样没有
 * 页内坐标的那条匹配——否则「页级书签」会把附近任意一条点书签吞掉。
 */
fun findPageBookmarkAt(
    bookmarks: List<BookmarkEntity>,
    pageIndex: Long,
    anchorX: Float?,
    anchorY: Float?,
    tolerance: Float = PAGE_ANCHOR_TOLERANCE,
): BookmarkEntity? {
    val candidates = bookmarks.filter { it.pageIndex == pageIndex }
    if (anchorX == null || anchorY == null) return candidates.firstOrNull { it.anchorX == null }
    return candidates.firstOrNull { b ->
        val bx = b.anchorX ?: return@firstOrNull false
        val by = b.anchorY ?: return@firstOrNull false
        kotlin.math.abs(bx - anchorX) <= tolerance && kotlin.math.abs(by - anchorY) <= tolerance
    }
}

/** 落在该页上的全部书签（页式渲染与页级书签状态查询用）。 */
fun pageBookmarksOf(bookmarks: List<BookmarkEntity>, pageIndex: Long): List<BookmarkEntity> =
    bookmarks.filter { it.pageIndex == pageIndex }

/** 落在该页上的全部标注（页式高亮渲染用）。 */
fun pageAnnotationsOf(
    annotations: List<AnnotationEntity>,
    pageIndex: Long,
): List<AnnotationEntity> = annotations.filter { it.pageIndex == pageIndex }

/** 页式书签列表排序：先按页序号，再按页内纵向位置（翻书顺序）。 */
fun sortPageBookmarks(bookmarks: List<BookmarkEntity>): List<BookmarkEntity> =
    bookmarks.sortedWith(
        compareBy({ it.pageIndex ?: Long.MAX_VALUE }, { it.anchorY ?: 0f }, { it.createdAt }),
    )

/**
 * 页式书签/高亮的展示回退文本。
 *
 * 文本模式的等价物是「所在位置的摘录」，页式没有可摘录的文本，页号就是它指向的东西——
 * 存进 `snapshotText` 后，书签列表与书架总览不用为页式另写一套展示。
 */
fun pageLabelOf(pageIndex: Int): String = "第 ${pageIndex + 1} 页"

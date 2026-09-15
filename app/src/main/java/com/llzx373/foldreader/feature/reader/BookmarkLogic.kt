package com.llzx373.foldreader.feature.reader

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

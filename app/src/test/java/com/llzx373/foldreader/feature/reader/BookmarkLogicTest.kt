package com.llzx373.foldreader.feature.reader

import com.llzx373.foldreader.core.data.db.AnnotationEntity
import com.llzx373.foldreader.core.data.db.BookmarkEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class BookmarkLogicTest {

    @Test
    fun `snapshot strips whitespace and truncates`() {
        assertEquals(
            "纸上得来终觉浅，绝知此事要躬行。",
            bookmarkSnapshotOf("  纸上得来终觉浅，\n绝知此事要躬行。"),
        )
        assertEquals("", bookmarkSnapshotOf(" \n\t "))
        // 手动演算：清理后 30 字，截到 24 字
        val long = "天地玄黄宇宙洪荒日月盈昃辰宿列张寒来暑往秋收冬藏闰余成岁"
        assertEquals(24, bookmarkSnapshotOf(long).length)
        assertEquals(long.take(24), bookmarkSnapshotOf(long))
    }

    @Test
    fun `find bookmark by anchor offset`() {
        val a = bookmark(id = 1, offset = 100)
        val b = bookmark(id = 2, offset = 500)
        assertSame(a, findBookmarkAt(listOf(a, b), 100))
        assertSame(b, findBookmarkAt(listOf(a, b), 500))
        assertNull(findBookmarkAt(listOf(a, b), 101))
        assertNull(findBookmarkAt(emptyList(), 100))
    }

    @Test
    fun `same page different offsets coexist and toggle independently`() {
        // 同页（100~200）两条书签：精确偏移各自匹配，互不干扰
        val a = bookmark(id = 1, offset = 100)
        val b = bookmark(id = 2, offset = 150)
        val bookmarks = listOf(a, b)
        assertSame(a, findBookmarkAt(bookmarks, 100)) // toggle 100 → 删 a
        assertSame(b, findBookmarkAt(bookmarks, 150)) // toggle 150 → 删 b
        assertNull(findBookmarkAt(bookmarks, 120)) // 同页新偏移 → 新增第三条
        assertNull(findBookmarkAt(listOf(a), 150)) // 删 b 后 150 可再加
    }

    @Test
    fun `bookmarks sorted by recency descending`() {
        val old = bookmark(id = 1, offset = 500, createdAt = 1_000L)
        val mid = bookmark(id = 2, offset = 100, createdAt = 2_000L)
        val new = bookmark(id = 3, offset = 900, createdAt = 3_000L)
        assertEquals(
            listOf(new, mid, old),
            sortBookmarksByRecency(listOf(old, mid, new)),
        )
        assertEquals(emptyList<BookmarkEntity>(), sortBookmarksByRecency(emptyList()))
    }

    // ---- 页式锚点（页序号 + 归一化页内坐标） ----

    @Test
    fun `page bookmark matches by point within tolerance`() {
        val a = pageBookmark(id = 1, page = 3, x = 0.20f, y = 0.30f)
        val b = pageBookmark(id = 2, page = 3, x = 0.80f, y = 0.90f)
        val bookmarks = listOf(a, b)

        // 同一点再点一次（含浮点抖动）→ 命中已有书签，toggle 会删掉它
        assertSame(a, findPageBookmarkAt(bookmarks, 3, 0.20f, 0.30f))
        assertSame(a, findPageBookmarkAt(bookmarks, 3, 0.205f, 0.302f))
        // 同页另一处 → 新增第三条
        assertNull(findPageBookmarkAt(bookmarks, 3, 0.50f, 0.50f))
        // 别的页 → 不匹配
        assertNull(findPageBookmarkAt(bookmarks, 4, 0.20f, 0.30f))
    }

    @Test
    fun `page bookmark tolerance keeps nearby marks distinguishable`() {
        // 一页上可以有多条书签。容差 3%：相隔 10% 的两条互不干扰，
        // 各自附近 ±3% 命中自己（容差内点谁都是点它，不会误删旁边那条）
        val a = pageBookmark(id = 1, page = 1, x = 0.10f, y = 0.10f)
        val b = pageBookmark(id = 2, page = 1, x = 0.20f, y = 0.10f)

        assertSame(a, findPageBookmarkAt(listOf(a, b), 1, 0.10f, 0.10f))
        assertSame(a, findPageBookmarkAt(listOf(a, b), 1, 0.12f, 0.10f))
        assertSame(b, findPageBookmarkAt(listOf(a, b), 1, 0.18f, 0.10f))
        assertSame(b, findPageBookmarkAt(listOf(a, b), 1, 0.20f, 0.10f))
        // 正中间（各差 5%，都在容差外）→ 既不命中 a 也不命中 b，算新增
        assertNull(findPageBookmarkAt(listOf(a, b), 1, 0.15f, 0.10f))
    }

    @Test
    fun `page level bookmark only matches another page level bookmark`() {
        // 顶栏按钮加的是「只锚到页」的书签，它不该把附近的点书签吞掉
        val pageLevel = pageBookmark(id = 1, page = 5, x = null, y = null)
        val point = pageBookmark(id = 2, page = 5, x = 0.5f, y = 0.5f)
        val bookmarks = listOf(pageLevel, point)

        assertSame(pageLevel, findPageBookmarkAt(bookmarks, 5, null, null))
        assertSame(point, findPageBookmarkAt(bookmarks, 5, 0.5f, 0.5f))
        // 页级查询不会命中点书签
        assertNull(findPageBookmarkAt(listOf(point), 5, null, null))
    }

    @Test
    fun `page bookmarks and annotations are filtered by page`() {
        val bookmarks = listOf(
            pageBookmark(id = 1, page = 2, x = 0.1f, y = 0.1f),
            pageBookmark(id = 2, page = 7, x = 0.1f, y = 0.1f),
        )
        assertEquals(listOf(1L), pageBookmarksOf(bookmarks, 2).map { it.id })
        assertEquals(emptyList<BookmarkEntity>(), pageBookmarksOf(bookmarks, 3))

        val annotations = listOf(
            pageAnnotation(id = 10, page = 2),
            pageAnnotation(id = 11, page = 2),
            pageAnnotation(id = 12, page = 9),
        )
        assertEquals(listOf(10L, 11L), pageAnnotationsOf(annotations, 2).map { it.id })
        assertEquals(emptyList<AnnotationEntity>(), pageAnnotationsOf(annotations, 1))
    }

    @Test
    fun `page bookmarks sorted by page then vertical position`() {
        val late = pageBookmark(id = 1, page = 9, x = 0f, y = 0.1f, createdAt = 5L)
        val second = pageBookmark(id = 2, page = 2, x = 0f, y = 0.80f, createdAt = 9L)
        val firstOnPage = pageBookmark(id = 3, page = 2, x = 0f, y = 0.20f, createdAt = 9L)

        assertEquals(
            listOf(firstOnPage, second, late),
            sortPageBookmarks(listOf(late, second, firstOnPage)),
        )
    }

    @Test
    fun `page label is one based`() {
        assertEquals("第 1 页", pageLabelOf(0))
        assertEquals("第 12 页", pageLabelOf(11))
    }

    private fun bookmark(id: Long, offset: Long, createdAt: Long = 0L) = BookmarkEntity(
        id = id,
        bookId = 1,
        charOffset = offset,
        chapterIndex = 0,
        snapshotText = "",
        createdAt = createdAt,
    )

    private fun pageBookmark(
        id: Long,
        page: Long,
        x: Float?,
        y: Float?,
        createdAt: Long = 0L,
    ) = BookmarkEntity(
        id = id,
        bookId = 1,
        charOffset = 0L,
        chapterIndex = 0,
        snapshotText = pageLabelOf(page.toInt()),
        createdAt = createdAt,
        pageIndex = page,
        anchorX = x,
        anchorY = y,
    )

    private fun pageAnnotation(id: Long, page: Long) = AnnotationEntity(
        id = id,
        bookId = 1,
        startCharOffset = 0L,
        endCharOffset = 0L,
        selectedText = "",
        color = 0L,
        note = null,
        createdAt = 0L,
        updatedAt = 0L,
        pageIndex = page,
        regionX = 0.1f,
        regionY = 0.1f,
        regionW = 0.2f,
        regionH = 0.05f,
    )
}

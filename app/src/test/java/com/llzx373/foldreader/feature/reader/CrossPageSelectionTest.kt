package com.llzx373.foldreader.feature.reader

import com.llzx373.foldreader.core.reader.Page
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossPageSelectionTest {

    @Test
    fun `selection range intersects visible pages`() {
        // 跨三页选区 [100, 700)，页区间各 300 字符
        assertEquals(100L until 300L, intersectRange(100, 700, 0, 300))
        assertEquals(300L until 600L, intersectRange(100, 700, 300, 600))
        assertEquals(600L until 700L, intersectRange(100, 700, 600, 900))
        assertNull(intersectRange(100, 700, 900, 1200))
    }

    @Test
    fun `selection fully inside one page`() {
        assertEquals(150L until 250L, intersectRange(150, 250, 0, 300))
        assertNull(intersectRange(150, 250, 300, 600))
    }

    @Test
    fun `touching ranges do not intersect`() {
        assertNull(intersectRange(0, 300, 300, 600))
        assertNull(intersectRange(300, 600, 0, 300))
    }

    @Test
    fun `edge detection beyond content bounds`() {
        val margin = 8f
        // 内容区 [100, 100] - [900, 1900]
        assertEquals(SelectionEdge.PREVIOUS,
            selectionEdgeAt(50f, 1000f, 100f, 100f, 900f, 1900f, margin))
        assertEquals(SelectionEdge.NEXT,
            selectionEdgeAt(950f, 1000f, 100f, 100f, 900f, 1900f, margin))
        assertEquals(SelectionEdge.PREVIOUS,
            selectionEdgeAt(500f, 50f, 100f, 100f, 900f, 1900f, margin))
        assertEquals(SelectionEdge.NEXT,
            selectionEdgeAt(500f, 1950f, 100f, 100f, 900f, 1900f, margin))
        assertEquals(SelectionEdge.NONE,
            selectionEdgeAt(500f, 1000f, 100f, 100f, 900f, 1900f, margin))
        // 边缘容差内不算越界
        assertEquals(SelectionEdge.NONE,
            selectionEdgeAt(95f, 1000f, 100f, 100f, 900f, 1900f, margin))
        // 退化区域不翻页
        assertEquals(SelectionEdge.NONE,
            selectionEdgeAt(0f, 0f, 100f, 100f, 100f, 100f, margin))
    }

    @Test
    fun `cross page snapshot range clamps and reports truncation`() {
        val (range, truncated) = annotationSnapshotRange(100L, 500L, 10_000L)!!
        assertEquals(100L until 500L, range)
        assertEquals(false, truncated)
    }

    @Test
    fun `snapshot range truncates at max chars`() {
        val (range, truncated) = annotationSnapshotRange(0L, 100_000L, 200_000L)!!
        assertEquals(0L until ANNOTATION_SNAPSHOT_MAX_CHARS.toLong(), range)
        assertEquals(true, truncated)
    }

    @Test
    fun `snapshot range clamps to book end`() {
        val (range, truncated) = annotationSnapshotRange(900L, 2000L, 1000L)!!
        assertEquals(900L until 1000L, range)
        assertEquals(false, truncated)
        assertNull(annotationSnapshotRange(500L, 400L, 1000L))
        assertNull(annotationSnapshotRange(1000L, 2000L, 1000L))
    }

    @Test
    fun `snapshot verify range follows snapshot length prefix`() {
        // 未截断：比对长度 = 选区长度
        assertEquals(100L until 500L, snapshotVerifyRange(100, 500, 400, 10_000L))
        // 截断快照：只比对快照长度的前缀
        assertEquals(
            0L until ANNOTATION_SNAPSHOT_MAX_CHARS.toLong(),
            snapshotVerifyRange(0, 100_000, ANNOTATION_SNAPSHOT_MAX_CHARS, 200_000L),
        )
        assertNull(snapshotVerifyRange(500, 400, 10, 1000L))
    }

    @Test
    fun `hinge clamp picks nearer page`() {
        // 中缝 [500, 540]：靠左归左页，靠右归右页，正中归左页
        assertTrue(hingeClampToLeft(505f, 500f, 540f))
        assertFalse(hingeClampToLeft(535f, 500f, 540f))
        assertTrue(hingeClampToLeft(520f, 500f, 540f))
    }

    @Test
    fun `scroll handle slot single column`() {
        val pages = pagesOf(0L, 300L, 600L, 900L)
        // 单栏：项 key = 页 charStart，页左缘恒 0（页铺满内容宽，列宽参数不参与）
        assertEquals(
            ScrollHandleSlot(pageCharStart = 300L, itemKey = 300L, pageLeftPx = 0f),
            scrollHandleSlot(pages, false, 450L, 500f, 40f, 500f, 460f),
        )
        // 页边界（== 下一页 charStart）归下一页
        assertEquals(
            300L,
            scrollHandleSlot(pages, false, 300L, 0f, 0f, 0f, 0f)!!.pageCharStart,
        )
        // 越过末页 charEnd 钳到末页；越过首页 charStart 钳到首页
        assertEquals(
            600L,
            scrollHandleSlot(pages, false, 900L, 0f, 0f, 0f, 0f)!!.pageCharStart,
        )
        assertEquals(
            0L,
            scrollHandleSlot(pages, false, -5L, 0f, 0f, 0f, 0f)!!.pageCharStart,
        )
        // 空页流不落位
        assertNull(scrollHandleSlot(emptyList(), false, 0L, 0f, 0f, 0f, 0f))
    }

    @Test
    fun `scroll handle slot dual columns`() {
        // 4 页两行；列宽 500 + 中缝 40 + 列宽 500，页宽 460 → 列内 inset 20
        val pages = pagesOf(0L, 100L, 200L, 300L, 400L)
        // 左列（index 2）：项 key = 行首页 charStart，页左缘 = 列内居中 inset
        assertEquals(
            ScrollHandleSlot(pageCharStart = 200L, itemKey = 200L, pageLeftPx = 20f),
            scrollHandleSlot(pages, true, 250L, 500f, 40f, 500f, 460f),
        )
        // 右列（index 3）：项 key 同行首页，页左缘 = 左列 + 中缝 + 右列 inset
        assertEquals(
            ScrollHandleSlot(pageCharStart = 300L, itemKey = 200L, pageLeftPx = 560f),
            scrollHandleSlot(pages, true, 350L, 500f, 40f, 500f, 460f),
        )
    }

    private fun pagesOf(vararg bounds: Long): List<Page> =
        bounds.asIterable().zipWithNext().map { (start, end) ->
            Page(charStart = start, charEnd = end, lines = emptyList(), paddingLeft = 0f, paddingRight = 0f)
        }
}

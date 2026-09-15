package com.llzx373.foldreader.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}

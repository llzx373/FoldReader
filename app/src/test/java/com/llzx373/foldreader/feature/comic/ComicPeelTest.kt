package com.llzx373.foldreader.feature.comic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ComicPeelTest {

    @Test
    fun `单页或不成对时整块内容区就是一叶`() {
        val (left, right) = comicPeelLeaves(
            dualLeaves = false,
            contentWidth = 1080f,
            contentHeight = 2340f,
            splitLeft = 540f,
            splitRight = 540f,
        )
        assertEquals(1080f, left.width, 0f)
        assertEquals(2340f, left.height, 0f)
        assertEquals(0f, left.originX, 0f)
        assertNull(right)
    }

    @Test
    fun `成对双页的叶矩形跟左右栏对齐`() {
        val (left, rightLeaf) = comicPeelLeaves(
            dualLeaves = true,
            contentWidth = 2000f,
            contentHeight = 1400f,
            splitLeft = 980f,
            splitRight = 1020f,
        )
        assertEquals(980f, left.width, 0f)
        assertEquals(0f, left.originX, 0f)
        val right = requireNotNull(rightLeaf)
        assertEquals(1020f, right.originX, 0f)
        assertEquals(980f, right.width, 0f)
        assertEquals(1400f, right.height, 0f)
    }
}

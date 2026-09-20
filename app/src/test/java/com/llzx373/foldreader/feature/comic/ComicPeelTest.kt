package com.llzx373.foldreader.feature.comic

import com.llzx373.foldreader.core.data.settings.ComicFitMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun `空页可以跳过拷贝`() {
        assertTrue(canReusePagedStill(null))
        assertNull(pagedStillBitmap(null))
    }

    @Test
    fun `FIT_PAGE 在叶内居中 letterbox`() {
        val fit = comicPeelLetterboxFit(
            imageWidth = 100,
            imageHeight = 200,
            destLeft = 0f,
            destTop = 0f,
            destRight = 400f,
            destBottom = 400f,
            fitMode = ComicFitMode.FIT_PAGE,
        )
        assertEquals(100f, fit.left, 0.01f)
        assertEquals(0f, fit.top, 0.01f)
        assertEquals(300f, fit.right, 0.01f)
        assertEquals(400f, fit.bottom, 0.01f)
    }

    @Test
    fun `落单封面 LTR 占左栏`() {
        val fit = comicPeelSpreadPageFit(
            image = null,
            pages = listOf(0),
            dual = true,
            rtl = false,
            wideSpan = false,
            leafWidth = 2000f,
            leafHeight = 1400f,
            splitLeft = 980f,
            splitRight = 1020f,
            fitMode = ComicFitMode.FIT_PAGE,
        )
        assertEquals(0f, fit.left, 0.01f)
        assertEquals(980f, fit.right, 0.01f)
    }

    @Test
    fun `落单封面 RTL 占右栏`() {
        val fit = comicPeelSpreadPageFit(
            image = null,
            pages = listOf(0),
            dual = true,
            rtl = true,
            wideSpan = false,
            leafWidth = 2000f,
            leafHeight = 1400f,
            splitLeft = 980f,
            splitRight = 1020f,
            fitMode = ComicFitMode.FIT_PAGE,
        )
        assertEquals(1020f, fit.left, 0f)
        assertEquals(2000f, fit.right, 0f)
    }
}

package com.llzx373.foldreader.feature.reader

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageCurlShaderTest {

    private val pageSize = Size(1000f, 2000f)

    @Test
    fun `drag direction follows drag vector`() {
        val forward = curlParamsFromPointer(
            start = Offset(900f, 1000f),
            current = Offset(600f, 1020f),
            pageSize = pageSize,
            forward = true,
        )
        assertTrue(forward.direction.x < 0f)
        assertEquals(1f, forward.direction.getDistance(), 0.001f)
        assertEquals(Offset(600f, 1020f), forward.pointer)

        val backward = curlParamsFromPointer(
            start = Offset(100f, 1000f),
            current = Offset(400f, 980f),
            pageSize = pageSize,
            forward = false,
        )
        assertTrue(backward.direction.x > 0f)
        assertEquals(1f, backward.direction.getDistance(), 0.001f)
    }

    @Test
    fun `tiny drag falls back to horizontal direction`() {
        val forward = curlParamsFromPointer(Offset(500f, 500f), Offset(500f, 500f), pageSize, true)
        assertEquals(Offset(-1f, 0f), forward.direction)
        val backward = curlParamsFromPointer(Offset(500f, 500f), Offset(500.5f, 500f), pageSize, false)
        assertEquals(Offset(1f, 0f), backward.direction)
    }

    @Test
    fun `radius scales with page width`() {
        assertEquals(60f, curlRadiusPx(pageSize), 0.001f)
        assertEquals(60f, curlParamsFromPointer(Offset.Zero, Offset.Zero, pageSize, true).radiusPx, 0.001f)
    }

    @Test
    fun `virtual pointer walks edge to edge`() {
        val startF = pointerForProgress(0f, forward = true, pageSize = pageSize)
        val endF = pointerForProgress(1f, forward = true, pageSize = pageSize)
        assertEquals(pageSize.width, startF.x, 0.001f)
        assertTrue(endF.x < 0f)

        val startB = pointerForProgress(0f, forward = false, pageSize = pageSize)
        val endB = pointerForProgress(1f, forward = false, pageSize = pageSize)
        assertTrue(startB.x < 0f)
        assertTrue(endB.x > pageSize.width)

        // 纵向始终落在页内，带弧度但端点在中线
        for (p in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            val y = pointerForProgress(p, forward = true, pageSize = pageSize).y
            assertTrue(y in 0f..pageSize.height)
        }
        assertEquals(pageSize.height * 0.5f, startF.y, 0.001f)
        assertEquals(pageSize.height * 0.5f, endF.y, 0.001f)

        // 单调推进
        var prev = Float.MAX_VALUE
        for (i in 0..10) {
            val x = pointerForProgress(i / 10f, forward = true, pageSize = pageSize).x
            assertTrue(x <= prev)
            prev = x
        }
    }

    @Test
    fun `progress clamps outside zero one`() {
        assertEquals(pointerForProgress(0f, true, pageSize), pointerForProgress(-1f, true, pageSize))
        assertEquals(pointerForProgress(1f, false, pageSize), pointerForProgress(2f, false, pageSize))
    }

    @Test
    fun `settling pointer starts at release point and ends on virtual path`() {
        val from = Offset(700f, 1200f)
        val atStart = settlingPointer(
            from = from, progress = 0.4f, settleStartProgress = 0.4f,
            completing = true, forward = true, pageSize = pageSize,
        )
        assertEquals(from.x, atStart.x, 0.001f)
        assertEquals(from.y, atStart.y, 0.001f)
        val atEnd = settlingPointer(
            from = from, progress = 1f, settleStartProgress = 0.4f,
            completing = true, forward = true, pageSize = pageSize,
        )
        val expectedEnd = pointerForProgress(1f, true, pageSize)
        assertEquals(expectedEnd.x, atEnd.x, 0.001f)
        assertEquals(expectedEnd.y, atEnd.y, 0.001f)
        val cancelEnd = settlingPointer(
            from = from, progress = 0f, settleStartProgress = 0.4f,
            completing = false, forward = true, pageSize = pageSize,
        )
        val expectedCancel = pointerForProgress(0f, true, pageSize)
        assertEquals(expectedCancel.x, cancelEnd.x, 0.001f)
        assertEquals(expectedCancel.y, cancelEnd.y, 0.001f)
    }
}

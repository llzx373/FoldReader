package com.llzx373.foldreader.feature.comic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PagedAnchorUiTest {

    @Test
    fun `两点围成的矩形自动排序并夹进页内`() {
        val rect = PageRect.between(0.8f, 0.9f, 0.2f, 0.1f)

        assertEquals(0.2f, rect.left, 0.0001f)
        assertEquals(0.1f, rect.top, 0.0001f)
        assertEquals(0.8f, rect.right, 0.0001f)
        assertEquals(0.9f, rect.bottom, 0.0001f)

        // 拖出页外要被夹回来：锚点存进库以后不能再有越界值
        val clamped = PageRect.between(-0.5f, -2f, 1.7f, 3f)
        assertEquals(0f, clamped.left, 0.0001f)
        assertEquals(0f, clamped.top, 0.0001f)
        assertEquals(1f, clamped.right, 0.0001f)
        assertEquals(1f, clamped.bottom, 0.0001f)
    }

    /** 长按时手指指腹的抖动必须被判成「点」，否则每次长按都会顺手建出一个框选书签。 */
    @Test
    fun `极小的选区算作点`() {
        assertTrue(isPointLikeSelection(PageRect.between(0.5f, 0.5f, 0.505f, 0.51f)))
        assertTrue(isPointLikeSelection(PageRect.between(0.5f, 0.5f, 0.505f, 0.9f)))
        assertTrue(isPointLikeSelection(PageRect.between(0.5f, 0.5f, 0.9f, 0.505f)))
        assertFalse(isPointLikeSelection(PageRect.between(0.2f, 0.2f, 0.5f, 0.3f)))
    }
}

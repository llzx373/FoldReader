package com.llzx373.foldreader.feature.reader

import com.llzx373.foldreader.core.reader.LayoutConfig
import com.llzx373.foldreader.core.reader.SpreadGeom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpreadBitmapCacheTest {

    private fun geom(dual: Boolean = false) = SpreadGeom(
        dual = dual,
        splitLeftPx = 500f,
        splitRightPx = 520f,
        leftInsetPx = 10f,
        rightInsetPx = 10f,
        innerPadPx = 24f,
        pageWidthPx = 480f,
    )

    private fun key(
        leftStart: Long = 0L,
        rightStart: Long? = null,
        backgroundArgb: Int = 0xFFFFFFFF.toInt(),
        textArgb: Int = 0xFF000000.toInt(),
        widthPx: Int = 1000,
        heightPx: Int = 2000,
        dual: Boolean = false,
        config: LayoutConfig = LayoutConfig(),
    ) = SpreadBitmapKey(
        leftStart = leftStart,
        rightStart = rightStart,
        widthPx = widthPx,
        heightPx = heightPx,
        geom = geom(dual),
        config = config,
        backgroundArgb = backgroundArgb,
        textArgb = textArgb,
        accentArgb = 0xFF616161.toInt(),
        density = 3f,
        scaledDensity = 3f,
    )

    @Test
    fun `key invalidates on spread theme size geometry and config change`() {
        val base = key()
        assertEquals(base, key())
        assertNotEquals(base, key(leftStart = 500L))
        assertNotEquals(base, key(rightStart = 500L))
        assertNotEquals(base, key(backgroundArgb = 0xFF000000.toInt()))
        assertNotEquals(base, key(textArgb = 0xFFFFFFFF.toInt()))
        assertNotEquals(base, key(widthPx = 500))
        assertNotEquals(base, key(heightPx = 1000))
        assertNotEquals(base, key(dual = true))
        assertNotEquals(base, key(config = LayoutConfig(fontSizeSp = 20f)))
    }

    @Test
    fun `lru evicts eldest beyond capacity`() {
        val map = LruMap<Int, String>(2)
        map.put(1, "a")
        map.put(2, "b")
        map.put(3, "c")
        assertNull(map.get(1))
        assertEquals(2, map.size())
        // 访问后刷新热度，最老的 2 被逐出
        map.get(2)
        map.put(4, "d")
        assertEquals("b", map.get(2))
        assertNull(map.get(3))
        assertEquals("d", map.get(4))
    }
}

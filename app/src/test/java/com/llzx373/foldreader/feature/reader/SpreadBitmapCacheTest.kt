package com.llzx373.foldreader.feature.reader

import com.llzx373.foldreader.core.reader.LayoutConfig
import com.llzx373.foldreader.core.reader.SpreadGeom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun `key invalidates on content version change`() {
        val v1 = SpreadBitmapCache.entryBytes(key()) // 键尺寸记账 smoke
        assertTrue(v1 > 0)
        val base = key()
        val bumped = base.copy(contentVersion = base.contentVersion + 1)
        assertNotEquals(base, bumped)
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

    @Test
    fun `lru put reports replaced and evicted keys`() {
        val map = LruMap<Int, String>(2)
        assertEquals(emptyList<Int>(), map.put(1, "a"))
        assertEquals(emptyList<Int>(), map.put(2, "b"))
        assertEquals(listOf(1), map.put(3, "c"))
        assertEquals(listOf(3), map.put(3, "c2"))
        assertEquals("c2", map.get(3))
    }

    @Test
    fun `cache evicts eldest when byte budget exceeded`() {
        // 每个条目记账 4MB；预算 9MB 最多容纳 2 张
        val cache = SpreadBitmapCache<String>(byteBudget = 9L * 1024 * 1024, maxEntries = 10) { _, _ ->
            4L * 1024 * 1024
        }
        val keys = (0L..3L).map { key(leftStart = it * 1000) }
        keys.forEachIndexed { i, k -> cache.put(k, "v$i") }
        assertNull(cache.get(keys[0]))
        assertNull(cache.get(keys[1]))
        assertEquals("v2", cache.get(keys[2]))
        assertEquals("v3", cache.get(keys[3]))
        assertTrue(cache.totalBytesSnapshot <= 9L * 1024 * 1024)
    }

    @Test
    fun `cache keeps at most max entries even under byte budget`() {
        val cache = SpreadBitmapCache<String>(byteBudget = Long.MAX_VALUE, maxEntries = 2)
        val keys = (0L..2L).map { key(leftStart = it * 1000) }
        keys.forEach { cache.put(it, "v$it") }
        assertNull(cache.get(keys[0]))
        assertEquals(2, cache.size())
    }

    @Test
    fun `clear resets byte accounting`() {
        val cache = SpreadBitmapCache<String>()
        cache.put(key(), "v")
        assertTrue(cache.totalBytesSnapshot > 0)
        cache.clear()
        assertEquals(0L, cache.totalBytesSnapshot)
        assertNull(cache.get(key()))
    }

    @Test
    fun `re-put same key does not double count bytes`() {
        val cache = SpreadBitmapCache<String>()
        val k = key()
        cache.put(k, "a")
        val once = cache.totalBytesSnapshot
        cache.put(k, "b")
        assertEquals(once, cache.totalBytesSnapshot)
        assertEquals(1, cache.size())
    }
}

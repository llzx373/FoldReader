package com.llzx373.foldreader.core.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PageCacheTest {

    @Test
    fun `least recently used entry is evicted first`() {
        val cache = PageCache<String, Int>(2)
        cache.put("a", 1)
        cache.put("b", 2)
        assertEquals(1, cache.get("a"))
        cache.put("c", 3)
        assertNull(cache.get("b"))
        cache.put("d", 4)
        assertNull(cache.get("a"))
        assertNotNull(cache.get("c"))
        assertNotNull(cache.get("d"))
    }

    @Test
    fun `size never exceeds max`() {
        val cache = PageCache<Int, Int>(3)
        for (i in 0 until 10) cache.put(i, i)
        assertEquals(3, cache.size)
    }

    @Test
    fun `layout config equality is field based`() {
        val a = LayoutConfig()
        val b = LayoutConfig()
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        org.junit.Assert.assertNotEquals(a, a.copy(fontSizeSp = 22f))
    }
}

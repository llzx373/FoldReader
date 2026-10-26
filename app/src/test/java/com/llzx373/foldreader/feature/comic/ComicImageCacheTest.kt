package com.llzx373.foldreader.feature.comic

import org.junit.Assert.assertEquals
import org.junit.Test

class ComicImageCacheTest {

    @Test
    fun `少于八张超过预算也会持续淘汰`() {
        val images = mutableMapOf(0 to 40, 1 to 40, 2 to 40)

        val bytes = trimImageCacheByBytes(
            images,
            byteCount = { it },
            visiblePages = setOf(0),
            currentPage = 0,
            maxBytes = 64,
        )

        assertEquals(mapOf(0 to 40), images)
        assertEquals(40, bytes)
    }

    @Test
    fun `当前双页受保护并优先淘汰最远页`() {
        val images = mutableMapOf(0 to 30, 1 to 30, 2 to 30, 8 to 30)

        trimImageCacheByBytes(
            images,
            byteCount = { it },
            visiblePages = setOf(0, 1),
            currentPage = 0,
            maxBytes = 64,
        )

        assertEquals(setOf(0, 1), images.keys)
    }

    @Test
    fun `覆盖同页后按当前内容重新计量`() {
        val images = mutableMapOf(0 to 40, 1 to 40)
        images[1] = 10

        val bytes = trimImageCacheByBytes(
            images,
            byteCount = { it },
            visiblePages = setOf(1),
            currentPage = 1,
            maxBytes = 64,
        )

        assertEquals(50, bytes)
        assertEquals(mapOf(0 to 40, 1 to 10), images)
    }

    @Test
    fun `所有页均可见时不为凑预算移除当前内容`() {
        val images = mutableMapOf(0 to 50, 1 to 50)

        val bytes = trimImageCacheByBytes(
            images,
            byteCount = { it },
            visiblePages = setOf(0, 1),
            currentPage = 0,
            maxBytes = 64,
        )

        assertEquals(100, bytes)
        assertEquals(setOf(0, 1), images.keys)
    }
}

package com.llzx373.foldreader.core.comic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ComicImageSizingTest {

    @Test
    fun `png 尺寸`() {
        val size = ComicImageSizing.probe(ComicTestImages.png(40, 60))
        assertEquals(listOf(40, 60), size?.toList())
    }

    @Test
    fun `gif 尺寸`() {
        val size = ComicImageSizing.probe(ComicTestImages.gif(24, 32))
        assertEquals(listOf(24, 32), size?.toList())
    }

    @Test
    fun `jpeg 尺寸`() {
        val size = ComicImageSizing.probe(ComicTestImages.jpeg(48, 12))
        assertEquals(listOf(48, 12), size?.toList())
    }

    @Test
    fun `bmp 尺寸`() {
        val size = ComicImageSizing.probe(ComicTestImages.bmp(70, 90))
        assertEquals(listOf(70, 90), size?.toList())
    }

    @Test
    fun `webp 扩展头尺寸`() {
        val size = ComicImageSizing.probe(ComicTestImages.webpVp8x(1024, 768))
        assertEquals(listOf(1024, 768), size?.toList())
    }

    @Test
    fun `认不出的数据返回 null`() {
        assertNull(ComicImageSizing.probe(ByteArray(64)))
        assertNull(ComicImageSizing.probe("not an image at all, really".toByteArray()))
    }

    @Test
    fun `webp vp8x 动画标志`() {
        assertEquals(
            true,
            ComicImageDecoder.isAnimated(ComicTestImages.webpVp8x(100, 100, animated = true)),
        )
        assertEquals(
            false,
            ComicImageDecoder.isAnimated(ComicTestImages.webpVp8x(100, 100, animated = false)),
        )
    }

    @Test
    fun `gif 一律按动画候选处理，静态图不算`() {
        assertEquals(true, ComicImageDecoder.isAnimated(ComicTestImages.gif(8, 8)))
        assertEquals(false, ComicImageDecoder.isAnimated(ComicTestImages.png(8, 8)))
        assertEquals(false, ComicImageDecoder.isAnimated(ComicTestImages.jpeg(8, 8)))
    }
}

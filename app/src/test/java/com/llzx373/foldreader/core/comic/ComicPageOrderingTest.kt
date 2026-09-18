package com.llzx373.foldreader.core.comic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComicPageOrderingTest {

    @Test
    fun `数字段按数值比较而不是字典序`() {
        val ordered = ComicPageOrdering.orderPaths(
            listOf("10.jpg", "2.jpg", "1.jpg", "21.jpg"),
        )

        assertEquals(listOf("1.jpg", "2.jpg", "10.jpg", "21.jpg"), ordered)
    }

    @Test
    fun `零填充与不同长度前缀`() {
        val ordered = ComicPageOrdering.orderPaths(
            listOf("f_0010.png", "f_0002.png", "f_0100.png", "f_0021.png"),
        )

        assertEquals(
            listOf("f_0002.png", "f_0010.png", "f_0021.png", "f_0100.png"),
            ordered,
        )
    }

    @Test
    fun `子目录按段比较以保持卷序`() {
        val ordered = ComicPageOrdering.orderPaths(
            listOf("vol10/1.jpg", "vol2/1.jpg", "vol2/10.jpg", "vol2/2.jpg"),
        )

        assertEquals(
            listOf("vol2/1.jpg", "vol2/2.jpg", "vol2/10.jpg", "vol10/1.jpg"),
            ordered,
        )
    }

    @Test
    fun `非图片与噪音条目被过滤`() {
        val ordered = ComicPageOrdering.orderPaths(
            listOf(
                "1.jpg",
                "2.PNG",
                "readme.txt",
                "Thumbs.db",
                "comic.xml",
                "__MACOSX/._1.jpg",
                "__MACOSX/cover.jpg",
                "._3.jpg",
                ".hidden.jpg",
                "sub/.DS_Store",
                "3.webp",
            ),
        )

        assertEquals(listOf("1.jpg", "2.PNG", "3.webp"), ordered)
    }

    @Test
    fun `大小写不影响比较`() {
        assertTrue(ComicPageOrdering.compareNatural("a.jpg", "A.jpg") == 0)
        assertTrue(ComicPageOrdering.compareNatural("b.jpg", "A.jpg") > 0)
    }

    @Test
    fun `前缀相同时短路径靠前`() {
        assertTrue(ComicPageOrdering.compareNatural("vol1/1.jpg", "vol1/1.jpg.bak") < 0)
    }

    @Test
    fun `图片扩展名判定`() {
        assertTrue(ComicPageOrdering.isImageName("a.JPG"))
        assertTrue(ComicPageOrdering.isImageName("a.avif"))
        assertFalse(ComicPageOrdering.isImageName("a.txt"))
        assertFalse(ComicPageOrdering.isImageName("noext"))
    }
}

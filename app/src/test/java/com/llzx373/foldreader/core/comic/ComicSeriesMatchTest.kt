package com.llzx373.foldreader.core.comic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComicSeriesMatchTest {

    @Test
    fun `主干抹掉卷号后相同`() {
        assertEquals(comicSeriesStem("Foo 第01卷.cbz"), comicSeriesStem("Foo 第2巻.cbz"))
        assertEquals(comicSeriesStem("[汉化组] Foo v01.zip"), comicSeriesStem("Foo v02.zip"))
        assertEquals(comicSeriesStem("Ｆｏｏ　０１"), comicSeriesStem("foo 02"))
    }

    @Test
    fun `主干去掉扩展名与括号标签`() {
        assertEquals("foo", comicSeriesStem("[组] Foo (全彩) 第3话.cbz"))
        assertEquals("foo", comicSeriesStem("【限定版】Foo.rar"))
    }

    @Test
    fun `同一系列按主干判等`() {
        assertTrue(isSameComicSeries("Foo 第01卷.cbz", "Foo 第02卷.cbz"))
        assertTrue(isSameComicSeries("Foo v1.zip", "Foo v2.zip"))
        assertTrue(isSameComicSeries("Foo - 03.cbz", "Foo - 04.cbz"))

        // 外传/番外主干不同：宁可漏，也不能跳到一本不相干的书
        assertFalse(isSameComicSeries("Foo 第01卷.cbz", "Foo 外传.cbz"))
        assertFalse(isSameComicSeries("Foo 第01卷.cbz", "Bar 第01卷.cbz"))
        // 空名不参与匹配（避免两个都归一化成空的时候判成同系列）
        assertFalse(isSameComicSeries("---", "（）"))
    }

    @Test
    fun `卷号识别覆盖中英文与尾部编号`() {
        assertEquals(1, comicSeriesVolume("Foo 第01卷.cbz"))
        assertEquals(12, comicSeriesVolume("Foo 第12话.cbz"))
        assertEquals(3, comicSeriesVolume("Foo 第三巻.cbz"))
        assertEquals(2, comicSeriesVolume("[组] Foo v02.zip"))
        assertEquals(2, comicSeriesVolume("Foo Vol.2.zip"))
        assertEquals(7, comicSeriesVolume("Foo volume 7.zip"))
        assertEquals(5, comicSeriesVolume("Foo ch.5.cbz"))
        assertEquals(9, comicSeriesVolume("Foo - 09.cbz"))
        assertEquals(11, comicSeriesVolume("Foo (11).cbz"))
        assertEquals(4, comicSeriesVolume("Foo_04.cbz"))
    }

    @Test
    fun `没有卷号返回 null`() {
        assertNull(comicSeriesVolume("Foo 番外.cbz"))
        assertNull(comicSeriesVolume("Foo SP.cbz"))
        assertNull(comicSeriesVolume("Foo.cbz"))
    }

    @Test
    fun `中文数字解析`() {
        assertEquals(3, parseVolumeNumber("三"))
        assertEquals(10, parseVolumeNumber("十"))
        assertEquals(11, parseVolumeNumber("十一"))
        assertEquals(20, parseVolumeNumber("二十"))
        assertEquals(25, parseVolumeNumber("二十五"))
        assertEquals(105, parseVolumeNumber("一百零五"))
        assertNull(parseVolumeNumber("番外"))
    }

    @Test
    fun `排序按卷号，没有卷号的靠自然序`() {
        val sorted = listOf("Foo 第10卷.cbz", "Foo 第2卷.cbz", "Foo 第1卷.cbz")
            .sortedWith(::compareComicSeries)
        assertEquals(listOf("Foo 第1卷.cbz", "Foo 第2卷.cbz", "Foo 第10卷.cbz"), sorted)

        // 数字排在字母前：有序卷在前，SP / 番外在后
        val mixed = listOf("Foo 番外.cbz", "Foo 第2卷.cbz").sortedWith(::compareComicSeries)
        assertEquals(listOf("Foo 第2卷.cbz", "Foo 番外.cbz"), mixed)
    }

    @Test
    fun `合并两个来源且库内优先`() {
        val current = ComicSeriesCandidate("Foo 第02卷.cbz", "uri://v2", bookId = 2L)
        val directory = listOf(
            ComicSeriesCandidate("Foo 第01卷.cbz", "uri://v1"),
            ComicSeriesCandidate("Foo 第02卷.cbz", "uri://v2"),
            ComicSeriesCandidate("Foo 第03卷.cbz", "uri://v3"),
            ComicSeriesCandidate("Bar 第01卷.cbz", "uri://bar"),
        )
        val library = listOf(
            ComicSeriesCandidate("Foo 第02卷", "uri://v2", bookId = 2L),
            ComicSeriesCandidate("Foo 第03卷", "uri://v3", bookId = 3L),
        )

        val merged = mergeComicSeries(current, directory, library)

        // Bar 被滤掉；未导入的第 1 卷保留（bookId 为空），第 2、3 卷带上 bookId
        assertEquals(listOf("uri://v1", "uri://v2", "uri://v3"), merged.map { it.uri })
        assertNull(merged[0].bookId)
        assertEquals(2L, merged[1].bookId)
        assertEquals(3L, merged[2].bookId)
    }

    @Test
    fun `库内书不在同目录时也能进系列`() {
        // 只导入了第 3 卷、且当前目录里没有它：它仍应出现在系列里（分组兜底的意义）
        val current = ComicSeriesCandidate("Foo 第03卷.cbz", "uri://v3", bookId = 3L)
        val directory = emptyList<ComicSeriesCandidate>()
        val library = listOf(
            ComicSeriesCandidate("Foo 第01卷", "uri://v1", bookId = 1L),
            ComicSeriesCandidate("Foo 第03卷", "uri://v3", bookId = 3L),
        )

        val merged = mergeComicSeries(current, directory, library)

        assertEquals(listOf("uri://v1", "uri://v3"), merged.map { it.uri })
        assertEquals(1L, merged.first().bookId)
    }

    @Test
    fun `只有自己时系列只有一个成员`() {
        val current = ComicSeriesCandidate("Foo.cbz", "uri://v1", bookId = 1L)
        val merged = mergeComicSeries(
            current,
            listOf(ComicSeriesCandidate("Bar.cbz", "uri://bar")),
            emptyList(),
        )

        assertEquals(listOf("uri://v1"), merged.map { it.uri })
    }

    @Test
    fun `ComicInfo 系列名归一化`() {
        assertEquals("foo bar", normalizeComicSeriesName("  Foo　Bar  "))
        assertEquals("foo", normalizeComicSeriesName("Ｆｏｏ"))
        assertNull(normalizeComicSeriesName(null))
        assertNull(normalizeComicSeriesName("   "))
    }

    @Test
    fun `ComicInfo 系列名优先于文件名主干`() {
        // 文件名主干不同（汉化组噪声把主干弄歪），但 ComicInfo Series 一致 → 同系列
        val a = ComicSeriesCandidate("[A组] 某漫画 第01卷.cbz", "uri://a", seriesName = "某漫画")
        val b = ComicSeriesCandidate("[B组] 某漫画 完全版 02.cbz", "uri://b", seriesName = "某漫画")
        assertTrue(isSameSeriesCandidate(a, b))
        // 一边没有 Series：退回文件名主干
        val c = ComicSeriesCandidate("Bar 01.cbz", "uri://c")
        assertFalse(isSameSeriesCandidate(a, c))
        // Series 不同：文件名主干相同也不算同系列（作者声明优先）
        val d = ComicSeriesCandidate("Baz 01.cbz", "uri://d", seriesName = "别的系列")
        val e = ComicSeriesCandidate("Baz 02.cbz", "uri://e", seriesName = "某漫画")
        assertFalse(isSameSeriesCandidate(d, e))
    }

    @Test
    fun `系列匹配合并用 ComicInfo 系列名`() {
        // 当前卷有 Series；库内一卷文件名主干不同但 Series 相同 → 并入系列
        val current = ComicSeriesCandidate("Foo 第02卷.cbz", "uri://v2", bookId = 2L, seriesName = "Foo")
        val library = listOf(
            ComicSeriesCandidate("[汉化] Foo v01", "uri://v1", bookId = 1L, seriesName = "foo"),
            ComicSeriesCandidate("完全无关", "uri://x", bookId = 9L, seriesName = "Bar"),
        )
        val merged = mergeComicSeries(current, emptyList(), library)
        assertEquals(listOf("uri://v1", "uri://v2"), merged.map { it.uri })
    }
}

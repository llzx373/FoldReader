package com.llzx373.foldreader.core.series

import com.llzx373.foldreader.core.data.db.BookEntity
import com.llzx373.foldreader.core.data.db.BookFormat
import com.llzx373.foldreader.core.data.db.BookWithProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** M34 系列一等公民：统一聚合键 / 卷号 / 组内排序 / 聚合分组的纯逻辑钉版。 */
class BookSeriesTest {

    private fun book(
        id: Long,
        title: String,
        format: BookFormat = BookFormat.TXT,
        seriesName: String? = null,
        seriesIndex: String? = null,
        totalChars: Long = 1000,
    ) = BookEntity(
        id = id,
        title = title,
        author = null,
        fileUri = "file:///x/$id.txt",
        contentHash = "h$id",
        format = format,
        totalChars = totalChars,
        encoding = "UTF-8",
        importedAt = id,
        lastReadAt = null,
        seriesName = seriesName,
        seriesIndex = seriesIndex,
    )

    private fun wrap(book: BookEntity, charOffset: Long? = null, comicPage: Int? = null) =
        BookWithProgress(book = book, charOffset = charOffset, comicPage = comicPage)

    // ---- unifiedSeriesKey ----

    @Test
    fun `EPUB 的 seriesName 直接成系列键（归一化大小写与空白）`() {
        val a = book(1, "三体", BookFormat.EPUB, seriesName = "地球往事")
        val b = book(2, "黑暗森林", BookFormat.EPUB, seriesName = "  地球往事  ")
        assertEquals(unifiedSeriesKey(a), unifiedSeriesKey(b))
        assertEquals("地球往事", unifiedSeriesKey(a))
    }

    @Test
    fun `漫画无 seriesName 时退回书名主干，上下卷同键`() {
        val a = book(1, "灌篮高手 第01卷", BookFormat.COMIC)
        val b = book(2, "灌篮高手 第02卷", BookFormat.COMIC)
        assertEquals(unifiedSeriesKey(a), unifiedSeriesKey(b))
    }

    @Test
    fun `漫画有 ComicInfo 系列名时按声明名归一（全角折半角）`() {
        val a = book(1, "SLAM DUNK 1", BookFormat.COMIC, seriesName = "ＳＬＡＭ ＤＵＮＫ")
        val b = book(2, "SLAM DUNK 2", BookFormat.COMIC, seriesName = "slam dunk")
        assertEquals(unifiedSeriesKey(a), unifiedSeriesKey(b))
    }

    @Test
    fun `普通 TXT 无系列字段不参与聚合`() {
        assertNull(unifiedSeriesKey(book(1, "平凡的世界 第一部")))
        assertNull(unifiedSeriesKey(book(2, "平凡的世界 第二部")))
    }

    // ---- seriesVolumeOf ----

    @Test
    fun `EPUB seriesIndex 数字与小数都可解析`() {
        assertEquals(3.0, seriesVolumeOf(book(1, "x", BookFormat.EPUB, seriesIndex = "3"))!!, 0.0)
        assertEquals(2.5, seriesVolumeOf(book(2, "x", BookFormat.EPUB, seriesIndex = "2.5"))!!, 0.0)
        assertNull(seriesVolumeOf(book(3, "x", BookFormat.EPUB, seriesIndex = "番外")))
    }

    @Test
    fun `漫画卷号退回文件名识别`() {
        assertEquals(5.0, seriesVolumeOf(book(1, "火影忍者 第5卷", BookFormat.COMIC))!!, 0.0)
        assertNull(seriesVolumeOf(book(2, "火影忍者 番外篇", BookFormat.COMIC)))
    }

    // ---- compareSeriesMembers ----

    @Test
    fun `组内按卷号升序，无卷号沉底，同卷号按书名自然序`() {
        val members = listOf(
            SeriesMember(wrap(book(1, "X 番外", BookFormat.COMIC)), null),
            SeriesMember(wrap(book(2, "X 第10卷", BookFormat.COMIC)), 10.0),
            SeriesMember(wrap(book(3, "X 第2卷", BookFormat.COMIC)), 2.0),
        )
        val sorted = members.sortedWith(::compareSeriesMembers)
        assertEquals(listOf(2.0, 10.0, null), sorted.map { it.volume })
    }

    // ---- groupBooksIntoSeries ----

    @Test
    fun `漫画与 EPUB 聚成统一系列组，单本不成组`() {
        val books = listOf(
            wrap(book(1, "三体", BookFormat.EPUB, seriesName = "地球往事", seriesIndex = "1")),
            wrap(book(2, "黑暗森林", BookFormat.EPUB, seriesName = "地球往事", seriesIndex = "2")),
            wrap(book(3, "死神永生", BookFormat.EPUB, seriesName = "地球往事", seriesIndex = "3")),
            wrap(book(4, "灌篮高手 第02卷", BookFormat.COMIC)),
            wrap(book(5, "灌篮高手 第01卷", BookFormat.COMIC)),
            wrap(book(6, "孤独的单行本")),
        )
        val groups = groupBooksIntoSeries(books)
        assertEquals(2, groups.size)
        val santi = groups.first { it.displayName == "地球往事" }
        assertEquals(3, santi.size)
        assertEquals(listOf("三体", "黑暗森林", "死神永生"), santi.members.map { it.item.book.title })
        val slam = groups.first { it.key == "灌篮高手" }
        assertEquals(
            listOf("灌篮高手 第01卷", "灌篮高手 第02卷"),
            slam.members.map { it.item.book.title },
        )
    }

    @Test
    fun `展示名取声明的系列名，漫画无声明名时用书名主干`() {
        val epub = groupBooksIntoSeries(
            listOf(
                wrap(book(1, "三体", BookFormat.EPUB, seriesName = "地球往事", seriesIndex = "1")),
                wrap(book(2, "黑暗森林", BookFormat.EPUB, seriesName = "地球往事", seriesIndex = "2")),
            ),
        ).single()
        assertEquals("地球往事", epub.displayName)

        val comic = groupBooksIntoSeries(
            listOf(
                wrap(book(1, "灌篮高手 第01卷", BookFormat.COMIC)),
                wrap(book(2, "灌篮高手 第02卷", BookFormat.COMIC)),
            ),
        ).single()
        assertEquals("灌篮高手", comic.displayName)
    }

    @Test
    fun `系列已读计数按各格式进度口径`() {
        val groups = groupBooksIntoSeries(
            listOf(
                wrap(book(1, "三体", BookFormat.EPUB, seriesName = "地球往事", seriesIndex = "1"), charOffset = 500),
                wrap(book(2, "黑暗森林", BookFormat.EPUB, seriesName = "地球往事", seriesIndex = "2"), charOffset = 0),
                wrap(book(3, "死神永生", BookFormat.EPUB, seriesName = "地球往事", seriesIndex = "3")),
            ),
        )
        assertEquals(1, groups.single().startedCount)
    }
}

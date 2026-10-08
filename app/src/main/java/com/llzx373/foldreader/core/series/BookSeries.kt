package com.llzx373.foldreader.core.series

import com.llzx373.foldreader.core.comic.ComicPageOrdering
import com.llzx373.foldreader.core.comic.comicSeriesStem
import com.llzx373.foldreader.core.comic.comicSeriesVolume
import com.llzx373.foldreader.core.comic.normalizeComicSeriesName
import com.llzx373.foldreader.core.data.db.BookEntity
import com.llzx373.foldreader.core.data.db.BookFormat
import com.llzx373.foldreader.core.data.db.BookWithProgress

/**
 * M34「系列一等公民」：把漫画的系列匹配（文件名主干 / ComicInfo 的 Series 字段）与
 * EPUB 等富元数据书的 `seriesName`/`seriesIndex` 统一成同一套聚合与排序逻辑。
 *
 * 刻意的取舍（与 ComicSeriesMatch 同一口径）：
 * - 只有**元数据声明了系列**（seriesName 非空）或**漫画**（文件名主干可推断）的书才参与
 *   系列聚合——普通 TXT 小说的书名主干撞名只会把不相干的书并到一起，宁可漏；
 * - 聚合键是归一化后的系列名/主干（全角折半角、小写、空白压缩），展示名取成员里
 *   出现的那一份原始系列名（漫画没有声明名时退化为第一本的书名主干）。
 */

/** 一本书在「系列视图」里的成员描述。 */
data class SeriesMember(
    val item: BookWithProgress,
    /** 卷号：EPUB 取 seriesIndex，漫画取文件名卷号；识别不出为 null（排在有卷号的后面）。 */
    val volume: Double?,
)

/** 一个系列聚合组；[members] 已按卷号升序排好（卷内排序见 [compareSeriesMembers]）。 */
data class SeriesGroup(
    /** 归一化聚合键（筛选/去重用它，不用于展示）。 */
    val key: String,
    /** 展示名：成员声明的系列名，漫画退化为书名主干。 */
    val displayName: String,
    val members: List<SeriesMember>,
) {
    val size: Int get() = members.size

    /** 系列整体进度：已读完卷数 / 总卷数的粗略口径——有进度（>1%）即算「在读/读过」。 */
    val startedCount: Int get() = members.count { member ->
        val book = member.item.book
        if (isPagedSeriesFormat(book.format)) {
            val page = member.item.comicPage ?: return@count false
            book.comicPageCount?.let { it > 0 && page > 0 } == true
        } else {
            val offset = member.item.charOffset ?: return@count false
            book.totalChars > 0 && offset > 0
        }
    }
}

/** 这本书的系列聚合键；不属于任何系列（返回 null）的书不进系列视图。 */
fun unifiedSeriesKey(book: BookEntity): String? {
    normalizeComicSeriesName(book.seriesName)?.let { return it }
    // 漫画没有 ComicInfo 系列名时退回书名主干（ComicSeriesMatch 的同系列判据）
    if (book.format == BookFormat.COMIC) {
        return comicSeriesStem(book.title).takeIf { it.isNotEmpty() }
    }
    return null
}

/** 系列内卷号：元数据 seriesIndex 优先（EPUB 的 "3"、"2.5" 都可解析），漫画退回文件名卷号。 */
fun seriesVolumeOf(book: BookEntity): Double? {
    book.seriesIndex?.trim()?.toDoubleOrNull()?.let { return it }
    if (book.format == BookFormat.COMIC) {
        return comicSeriesVolume(book.title)?.toDouble()
    }
    return null
}

/**
 * 系列内排序：都有卷号按卷号；一边没卷号时**有卷号的排前**（番外/无号卷沉底）；
 * 同卷号按书名自然序（与漫画页序同一比较器，数字感知）。
 */
fun compareSeriesMembers(a: SeriesMember, b: SeriesMember): Int {
    val va = a.volume
    val vb = b.volume
    if (va != null && vb == null) return -1
    if (va == null && vb != null) return 1
    if (va != null && vb != null && va != vb) return va.compareTo(vb)
    return ComicPageOrdering.compareNatural(a.item.book.title, b.item.book.title)
}

/** 系列的展示名：成员声明的系列名优先；漫画没有声明名时用第一本的书名主干。 */
fun seriesDisplayName(key: String, members: List<SeriesMember>): String {
    members.forEach { member ->
        member.item.book.seriesName?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    }
    return members.firstOrNull()?.item?.book?.let { book ->
        comicSeriesStem(book.title).replaceFirstChar { it.uppercaseChar() }.ifBlank { book.title }
    } ?: key
}

/**
 * 书架聚合成系列：按 [unifiedSeriesKey] 分组，**只保留 ≥2 本**的组（单本"系列"没有
 * 聚合价值，书架照常平铺）。返回的组按展示名自然序排列，组内按卷号升序。
 */
fun groupBooksIntoSeries(books: List<BookWithProgress>): List<SeriesGroup> =
    books
        .mapNotNull { item ->
            unifiedSeriesKey(item.book)?.let { key ->
                key to SeriesMember(item, seriesVolumeOf(item.book))
            }
        }
        .groupBy({ it.first }, { it.second })
        .filterValues { it.size >= 2 }
        .map { (key, members) ->
            val sorted = members.sortedWith(::compareSeriesMembers)
            SeriesGroup(key = key, displayName = seriesDisplayName(key, sorted), members = sorted)
        }
        .sortedWith { a, b -> ComicPageOrdering.compareNatural(a.displayName, b.displayName) }

private fun isPagedSeriesFormat(format: BookFormat): Boolean =
    format == BookFormat.COMIC || format == BookFormat.PDF

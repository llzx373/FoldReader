package com.llzx373.foldreader.core.comic.archive

import com.llzx373.foldreader.core.comic.ComicArchive
import com.llzx373.foldreader.core.comic.ComicContainer
import com.llzx373.foldreader.core.comic.ComicPage
import com.llzx373.foldreader.core.comic.ComicPageOrdering
import com.llzx373.foldreader.core.comic.ComicPageSource
import java.io.IOException
import java.nio.channels.SeekableByteChannel
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile

/**
 * 打开 zip/cbz 漫画：只读中央目录 + 按需解压单个条目，**不整包复制、不全部解压**。
 * 这是唯一支持真随机访问的容器（7z 的条目流只对 `getNextEntry()` 的当前条目有效，
 * 所以 7z 与 tar/rar 一样走一次性解压，见 [ComicArchiveExtractor]）。
 */
internal fun openZipComicArchive(channel: SeekableByteChannel): ComicArchive {
    val zip = try {
        ZipFile.Builder().setSeekableByteChannel(channel).get()
    } catch (t: Throwable) {
        runCatching { channel.close() }
        throw t
    }
    val entries = LinkedHashMap<String, ZipArchiveEntry>()
    for (entry in zip.entries) {
        if (entry.isDirectory) continue
        entries[entry.name] = entry
    }
    val ordered = ComicPageOrdering.orderPaths(entries.keys)
    if (ordered.isEmpty()) {
        runCatching { zip.close() }
        runCatching { channel.close() }
        throw IOException("压缩包内没有可显示的图片")
    }
    val pages = ordered.mapIndexed { index, key ->
        ComicPage(index, key.substringAfterLast('/'), ComicPageSource.Entry(key))
    }
    return StreamComicArchive(
        container = ComicContainer.ZIP,
        pages = pages,
        openStream = { source ->
            val key = (source as ComicPageSource.Entry).key
            val entry = entries[key] ?: throw IOException("压缩包条目缺失: $key")
            zip.getInputStream(entry)
        },
        onClose = {
            runCatching { zip.close() }
            runCatching { channel.close() }
        },
    )
}

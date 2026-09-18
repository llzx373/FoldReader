package com.llzx373.foldreader.core.comic.archive

import com.llzx373.foldreader.core.comic.ComicArchive
import com.llzx373.foldreader.core.comic.ComicContainer
import com.llzx373.foldreader.core.comic.ComicPage
import com.llzx373.foldreader.core.comic.ComicPageSource
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream

private val PAGE_INDEX_PREFIX = Regex("^\\d{6}_")

/** 解压产物名 `000001_原名` → 展示用原名。 */
internal fun comicPageDisplayName(fileName: String): String =
    fileName.replaceFirst(PAGE_INDEX_PREFIX, "")

/**
 * 打开「已经落在本地目录里的一页一个文件」的漫画：
 * tar/7z/rar 解压缓存、用户选择的复制到本地，都是这个形状。
 */
internal fun openLocalComicArchive(container: ComicContainer, pageFiles: List<File>): ComicArchive {
    if (pageFiles.isEmpty()) throw IOException("本地页目录为空")
    val pages = pageFiles.mapIndexed { index, file ->
        ComicPage(index, comicPageDisplayName(file.name), ComicPageSource.Local(file))
    }
    return StreamComicArchive(
        container = container,
        pages = pages,
        openStream = { source -> FileInputStream((source as ComicPageSource.Local).file) },
    )
}

/**
 * 打开「外部 SAF 目录」漫画：每页是一个文档 Uri，按需打开，不落盘。
 * 目录漫画天然没有压缩，翻页就是一次 `openInputStream`。
 */
internal fun openDocumentComicArchive(
    container: ComicContainer,
    pages: List<ComicPage>,
    openDocument: (String) -> InputStream?,
): ComicArchive {
    if (pages.isEmpty()) throw IOException("目录内没有可显示的图片")
    return StreamComicArchive(
        container = container,
        pages = pages,
        openStream = { source ->
            val uri = (source as ComicPageSource.Document).uri
            openDocument(uri) ?: throw IOException("无法读取图片: $uri")
        },
    )
}

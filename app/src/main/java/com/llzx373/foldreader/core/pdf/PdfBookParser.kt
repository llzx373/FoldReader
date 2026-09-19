package com.llzx373.foldreader.core.pdf

import android.content.Context
import android.net.Uri
import com.llzx373.foldreader.core.format.BookContent
import com.llzx373.foldreader.core.format.BookMeta
import com.llzx373.foldreader.core.format.BookParser
import com.llzx373.foldreader.core.format.Chapter
import com.llzx373.foldreader.core.format.ConvertedBookStore
import com.llzx373.foldreader.core.format.FlattenContent
import com.llzx373.foldreader.core.format.FlattenedBook
import com.llzx373.foldreader.core.format.PageLabel
import java.io.File
import java.io.IOException
import java.nio.channels.SeekableByteChannel
import java.nio.charset.Charset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 文本型 PDF 的「当电子书读」解析器：把 PDF 正文抽出来压平成
 * `convertedDir/<contentHash>.txt`（UTF-8 纯文本），之后由 [openFlattenedContent]（TXT 管线）
 * 提供分页、全文搜索、书签、划线、阅读统计——这些能力一行都不用重写。
 *
 * 三点刻意的取舍：
 * - **扫描件不产出压平文件**：文本密度不够时（[PdfBoxReader] 判定）返回 null 而不是写一个空文件，
 *   否则书架上会多一份"能读但全是空白"的假电子书；
 * - **目录两套锚点并存**：压平时我们知道每页正文的起始字符偏移，所以同一个目录项可以同时写
 *   `pageIndex`（页式阅读用）与 `charStart/charEnd`（文本阅读用）——这是实测出来的对应关系，
 *   不是把页序号当字符偏移用；
 * - **纸书页码直接写成"第 N 页"**：文本模式下页边界是明确的，读的人也需要知道自己在第几页。
 */
class PdfBookParser(
    convertedDir: File,
    private val appContext: Context,
    private val openFlattenedContent: suspend (File) -> BookContent,
    private val openChannel: (Uri) -> SeekableByteChannel,
    private val displayNameOf: (Uri) -> String?,
    private val bookIdResolver: suspend (Uri, Long?) -> Long? = { _, _ -> null },
    private val onChaptersIndexed: suspend (bookId: Long, chapters: List<Chapter>) -> Unit = { _, _ -> },
) : BookParser {

    private val store = ConvertedBookStore(convertedDir)

    override suspend fun parseMeta(uri: Uri): BookMeta = withContext(Dispatchers.IO) {
        val byteSize = openChannel(uri).use { it.size() }
        val info = PdfBoxReader.read(appContext, uri.toString())
        BookMeta(
            title = info?.title?.takeIf { it.isNotBlank() } ?: fallbackTitle(uri),
            author = info?.author,
            encoding = Charsets.UTF_8.name(),
            byteSize = byteSize,
        )
    }

    override suspend fun parseChapters(uri: Uri, charsetOverride: Charset?): List<Chapter> =
        withContext(Dispatchers.IO) { ensureFlattened(uri)?.chapters.orEmpty() }

    override suspend fun openContent(uri: Uri, charsetOverride: Charset?): BookContent =
        openContent(uri, charsetOverride, null)

    override suspend fun openContent(uri: Uri, charsetOverride: Charset?, bookId: Long?): BookContent =
        withContext(Dispatchers.IO) {
            // charsetOverride 忽略：压平产物固定 UTF-8
            val flattened = ensureFlattenedAndIndexChapters(uri, bookId)
                ?: throw IOException(SCANNED_MESSAGE)
            openFlattenedContent(flattened.file)
        }

    /** 预热：只做压平，不取内容。扫描件在这里安静返回（不是失败）。 */
    override suspend fun prewarm(uri: Uri, bookId: Long?) {
        withContext(Dispatchers.IO) { ensureFlattenedAndIndexChapters(uri, bookId) }
    }

    /**
     * 预热并返回压平后的**字符数**（书架与阅读器要用它算进度）。
     * 扫描件返回 null。
     *
     * 字符数是流式数出来的，不是文件字节数：中文 PDF 一个字符 3 字节，
     * 用字节数当字符数会让进度条永远走不满。
     */
    suspend fun prewarmAndCharCount(uri: Uri, bookId: Long? = null): Long? = withContext(Dispatchers.IO) {
        val flattened = ensureFlattenedAndIndexChapters(uri, bookId) ?: return@withContext null
        var chars = 0L
        flattened.file.bufferedReader(Charsets.UTF_8).use { reader ->
            val buffer = CharArray(64 * 1024)
            while (true) {
                val read = reader.read(buffer)
                if (read < 0) break
                chars += read
            }
        }
        chars
    }

    override suspend fun pageLabels(uri: Uri): List<PageLabel>? =
        withContext(Dispatchers.IO) {
            ensureFlattened(uri)?.pageLabels?.takeIf { it.isNotEmpty() }
        }

    /** 压平（命中缓存零成本）；本次确实新压平时把目录回填进章节表（两种锚点一起写）。 */
    private suspend fun ensureFlattenedAndIndexChapters(uri: Uri, bookId: Long?): FlattenedBook? {
        val flattened = ensureFlattened(uri) ?: return null
        if (flattened.fresh) {
            bookIdResolver(uri, bookId)?.let { id ->
                runCatching { onChaptersIndexed(id, flattened.chapters) }
            }
        }
        return flattened
    }

    private suspend fun ensureFlattened(uri: Uri): FlattenedBook? = withContext(Dispatchers.IO) {
        val hash = openChannel(uri).use { store.contentHash(it) }
        store.cached(hash)?.let { return@withContext it }
        // 与后台预热串行：两边可能同时发现缓存缺失，别把同一本书抽两遍
        store.withFlattenLock(hash) {
            store.cached(hash) ?: runCatching {
                store.store(hash) { out -> flatten(uri, out) ?: throw NoExtractableText() }
            }.getOrElse { cause ->
                if (cause is NoExtractableText) null else throw cause
            }
        }
    }

    /** 扫描件：抽出结果不像文本。用异常从 `store.store` 的回调里脱身，产物与 sidecar 都不会落盘。 */
    private class NoExtractableText : Exception()

    /**
     * 抽文本 + 生成目录与纸书页码。
     *
     * 返回 null = 扫描件（密度判定没过），调用方据此放弃压平。
     */
    private fun flatten(uri: Uri, out: File): FlattenContent? {
        val info = PdfBoxReader.read(appContext, uri.toString(), textTarget = out) ?: return null
        val text = info.text ?: return null
        val pageCount = info.pageCount
        val starts = text.pageStartOffsets

        // 目录项 → 字符锚点：页锚点能解析就用页首偏移，解析不出来就跟着上一项，
        // 这样文本模式下每个条目都跳得到地方（坏目标不会变成"点了没反应"）。
        var lastStart = 0L
        val entries = info.outline.map { entry ->
            val page = entry.pageIndex?.takeIf { it in 0 until pageCount }
            val start = page?.let { starts[it] } ?: lastStart
            lastStart = start
            Chapter(
                title = entry.title,
                charStart = start,
                charEnd = 0L,
                depth = entry.depth,
                pageIndex = page?.toLong(),
            )
        }
        // charEnd 用下一章的起点补齐、最后一章到文末：章节进度条要靠它
        val chapters = entries.mapIndexed { index, chapter ->
            chapter.copy(charEnd = entries.getOrNull(index + 1)?.charStart ?: text.charCount)
        }
        val pageLabels = (0 until pageCount).map { page ->
            PageLabel(label = "第 ${page + 1} 页", charOffset = starts[page])
        }
        return FlattenContent(chapters = chapters, pageLabels = pageLabels)
    }

    private fun fallbackTitle(uri: Uri): String =
        displayNameOf(uri)
            ?.substringBeforeLast('.')
            ?.takeIf { it.isNotBlank() }
            ?: "未命名文档"

    companion object {
        /** 扫描件的统一说法：解析器抛它，界面照它显示，别让用户以为"取字"这个按钮坏了。 */
        const val SCANNED_MESSAGE = "这是扫描件，没有可提取的文字"
    }
}

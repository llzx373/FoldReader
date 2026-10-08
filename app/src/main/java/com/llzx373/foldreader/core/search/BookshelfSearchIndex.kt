package com.llzx373.foldreader.core.search

import com.llzx373.foldreader.core.data.db.BookEntity
import com.llzx373.foldreader.core.data.db.BookFormat
import com.llzx373.foldreader.core.format.SearchHit
import com.llzx373.foldreader.core.format.StringBookContent
import com.llzx373.foldreader.core.format.searchContent
import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * M34 书架级全文搜索：文件副本索引 + 复用阅读器的分块扫描器。
 *
 * 刻意的取舍：
 * - 索引是**纯文本副本**（filesDir/search_index/），不是 DB 表——正文本来就是大字段，
 *   文件副本天然支持流式读、按书增删，且不占 Room 备份/迁移的复杂度；
 * - 索引内容**不做空白归一**：扫描命中偏移必须能直接当阅读器的 charOffset 用
 *   （清洗副本/压平产物就是阅读器实际读的那一份；TXT 原文件按同一编码解码）；
 * - 只索引文本可解析的书：TXT/Markdown 原文件 + 有清洗副本/压平产物的格式；
 *   漫画与无文本层扫描件 PDF 没有正文可索引，跳过；
 * - 文件名带签名（内容哈希 + 是否有副本）：重洗/回填压平产物后旧索引自然成孤儿被清掉。
 */
class BookshelfSearchIndex(private val dir: File) {

    /** 一本书当前应有的索引文件名（不含目录）；null = 这本书不参与全文索引。 */
    fun signatureOf(book: BookEntity): String? {
        val source = when {
            book.cleanedFilePath != null -> "copy"
            book.format == BookFormat.TXT || book.format == BookFormat.MARKDOWN -> "raw"
            else -> return null
        }
        val sig = (book.contentHash + ":" + source).hashCode().toUInt().toString(16)
        return "${book.id}.$sig.txt"
    }

    /** 这本书当前已落盘的索引文件（不管签名新旧）；没有为 null。 */
    fun currentFile(bookId: Long): File? =
        dir.listFiles()?.firstOrNull { it.name.startsWith("$bookId.") && it.extension == "txt" }

    fun indexedBookIds(): Set<Long> =
        dir.listFiles()
            ?.mapNotNull { it.name.substringBefore('.').toLongOrNull() }
            ?.toSet()
            .orEmpty()

    fun read(bookId: Long): String? =
        runCatching { currentFile(bookId)?.takeIf { it.isFile }?.readText() }.getOrNull()

    /** 先写临时文件再改名：搜索中途读到写了一半的索引比没有索引更难排查。 */
    fun write(bookId: Long, signatureFileName: String, text: String) {
        dir.mkdirs()
        deleteBook(bookId)
        val tmp = File(dir, "$signatureFileName.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(File(dir, signatureFileName))) {
            File(dir, signatureFileName).writeText(text)
            tmp.delete()
        }
    }

    fun deleteBook(bookId: Long) {
        dir.listFiles()
            ?.filter { it.name.startsWith("$bookId.") }
            ?.forEach { it.delete() }
    }

    fun clear() {
        dir.deleteRecursively()
    }

    fun totalBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    companion object {
        /** 单书索引来源的大小上限：超过就跳过（副本全量进内存扫描，30MB 是内存与体验的折中）。 */
        const val MAX_SOURCE_BYTES = 30L * 1024 * 1024
    }
}

/**
 * 索引文本解码（TXT/Markdown 原文件）：显式编码（书籍详情里用户选定的）优先，
 * 空串走自动探测（只采样前 64KB，大文件不整扫）；剥掉 BOM——阅读器的字符坐标
 * 不含 BOM，索引不脱壳的话命中偏移会整体右偏一位。
 */
fun decodeIndexText(bytes: ByteArray, encodingName: String): String {
    val charset = com.llzx373.foldreader.core.format.EncodingDetector.forNameOrNull(encodingName)
        ?: com.llzx373.foldreader.core.format.EncodingDetector.detect(
            bytes,
            minOf(bytes.size, com.llzx373.foldreader.core.format.EncodingDetector.SAMPLE_SIZE),
        ).charset
    val bom = com.llzx373.foldreader.core.format.EncodingDetector.bomLengthOf(bytes)
    return String(bytes, bom, bytes.size - bom, charset)
}

/** 一本书的全文搜索结果：命中总数 + 前几处上下文预览。 */data class ShelfSearchResult(
    val bookId: Long,
    val title: String,
    val totalHits: Int,
    val previews: List<SearchHit>,
)

/**
 * 对已建索引的书跑全文搜索（复用阅读器的 [searchContent]，命中口径与书内搜索一致）。
 * 每书最多保留 [previewsPerBook] 条上下文，命中数照样计全；可取消。
 */
suspend fun searchShelfIndex(
    index: BookshelfSearchIndex,
    books: List<BookEntity>,
    query: String,
    previewsPerBook: Int = 3,
): List<ShelfSearchResult> {
    if (query.isBlank()) return emptyList()
    val results = mutableListOf<ShelfSearchResult>()
    for (book in books) {
        currentCoroutineContext().ensureActive()
        val text = index.read(book.id) ?: continue
        var hits = 0
        val previews = mutableListOf<SearchHit>()
        searchContent(StringBookContent(text), query, onHit = { hit ->
            hits++
            if (previews.size < previewsPerBook) previews += hit
        })
        if (hits > 0) {
            results += ShelfSearchResult(book.id, book.title, hits, previews)
        }
    }
    // 命中多的书排前面，同数按标题稳定
    return results.sortedWith(compareByDescending<ShelfSearchResult> { it.totalHits }.thenBy { it.title })
}

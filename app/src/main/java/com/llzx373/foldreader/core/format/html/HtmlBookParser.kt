package com.llzx373.foldreader.core.format.html

import android.net.Uri
import android.util.Xml
import com.llzx373.foldreader.core.format.BookContent
import com.llzx373.foldreader.core.format.BookMeta
import com.llzx373.foldreader.core.format.BookParser
import com.llzx373.foldreader.core.format.Chapter
import com.llzx373.foldreader.core.format.ConvertedBookStore
import com.llzx373.foldreader.core.format.EncodingDetector
import com.llzx373.foldreader.core.format.FlattenContent
import com.llzx373.foldreader.core.format.FlattenedBook
import com.llzx373.foldreader.core.format.TextSpan
import com.llzx373.foldreader.core.format.epub.FlattenSink
import com.llzx373.foldreader.core.format.epub.HtmlTextFlattener
import com.llzx373.foldreader.core.format.newPullParser
import java.io.File
import java.nio.channels.Channels
import java.nio.channels.SeekableByteChannel
import java.nio.charset.Charset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.xmlpull.v1.XmlPullParser

/**
 * HTML（.html/.htm）解析器：复用 EPUB/DOCX 的压平管线——首开时按 TXT 同款
 * [EncodingDetector] 探测编码并解码，经 [HtmlSanitizer] 消毒（顺带注入标题锚点），
 * 由 [HtmlTextFlattener] 压平为 `convertedDir/<contentHash>.txt` + sidecar，
 * 之后由 [openFlattenedContent]（TXT 管线）提供内容与偏移索引。
 *
 * 目录只收 h1-h6（用户明确要求，其它标签一律不进目录），depth = 标题级别 - 1；
 * 无标题退化为单个「正文」章。图片 v1 不落地（消毒仅留 data:image/，flattener 不处理 data: URI）。
 *
 * Uri 壳方法只做 读字节/哈希/委托；核心逻辑走 File/ByteArray 参数的内部方法以便 JVM 单测。
 */
class HtmlBookParser(
    convertedDir: File,
    private val openFlattenedContent: suspend (File) -> BookContent,
    private val openChannel: (Uri) -> SeekableByteChannel,
    private val displayNameOf: (Uri) -> String?,
    private val bookIdResolver: suspend (Uri, Long?) -> Long? = { _, _ -> null },
    private val onChaptersIndexed: suspend (bookId: Long, chapters: List<Chapter>) -> Unit = { _, _ -> },
    private val newParser: () -> XmlPullParser = { Xml.newPullParser() },
) : BookParser {

    private val store = ConvertedBookStore(convertedDir)

    /** 元数据只需 head：标题/作者/简介都在前部，采样 [META_SAMPLE_BYTES] 足够（正文可能极大）。 */
    override suspend fun parseMeta(uri: Uri): BookMeta = withContext(Dispatchers.IO) {
        openChannel(uri).use { channel ->
            val byteSize = channel.size()
            val bytes = store.readAt(channel, 0, minOf(byteSize, META_SAMPLE_BYTES).toInt())
            val meta = metaOf(decode(bytes))
            BookMeta(
                title = meta.title?.takeIf { it.isNotBlank() } ?: fallbackTitle(uri),
                author = meta.author,
                encoding = Charsets.UTF_8.name(),
                byteSize = byteSize,
                description = meta.description,
            )
        }
    }

    internal data class HtmlMeta(
        val title: String? = null,
        val author: String? = null,
        val description: String? = null,
    )

    /**
     * `<title>` / `<meta name="author|description">` 提取。必须在消毒之前从原始文档取——
     * 消毒会删掉 meta 标签。单独一次 jsoup 解析，不影响压平那份。
     */
    internal fun metaOf(htmlText: String): HtmlMeta {
        val doc = runCatching { Jsoup.parse(htmlText) }.getOrNull() ?: return HtmlMeta()
        return HtmlMeta(
            title = doc.title().trim().takeIf { it.isNotEmpty() },
            author = doc.selectFirst("meta[name=author]")?.attr("content")?.trim()
                ?.takeIf { it.isNotEmpty() },
            description = doc.selectFirst("meta[name=description]")?.attr("content")?.trim()
                ?.takeIf { it.isNotEmpty() },
        )
    }

    override suspend fun openContent(uri: Uri, charsetOverride: Charset?): BookContent =
        openContent(uri, charsetOverride, null)

    override suspend fun openContent(uri: Uri, charsetOverride: Charset?, bookId: Long?): BookContent =
        withContext(Dispatchers.IO) {
            // charsetOverride 忽略：压平产物固定 UTF-8
            openFlattenedContent(ensureFlattenedAndIndexChapters(uri, bookId).file)
        }

    /** 预热：只做压平，不取内容。导入后由后台队列调用，好让首次打开直接命中缓存。 */
    override suspend fun prewarm(uri: Uri, bookId: Long?) {
        withContext(Dispatchers.IO) { ensureFlattenedAndIndexChapters(uri, bookId) }
    }

    /** 压平（命中缓存则零成本）；本次确实新压平时顺带把标题章节回填进章节表。 */
    private suspend fun ensureFlattenedAndIndexChapters(uri: Uri, bookId: Long?): FlattenedBook {
        val flattened = ensureFlattened(uri)
        if (flattened.fresh) {
            bookIdResolver(uri, bookId)?.let { id ->
                runCatching { onChaptersIndexed(id, flattened.chapters) }
            }
        }
        return flattened
    }

    override suspend fun parseChapters(uri: Uri, charsetOverride: Charset?): List<Chapter> =
        withContext(Dispatchers.IO) { ensureFlattened(uri).chapters }

    private suspend fun ensureFlattened(uri: Uri): FlattenedBook {
        openChannel(uri).use { channel ->
            val hash = store.contentHash(channel)
            store.cached(hash)?.let { return it }
            // 按 hash 串行：后台预热与阅读器可能同时发现缓存缺失，别把同一本书压两遍
            return store.withFlattenLock(hash) {
                store.cached(hash)?.let { return@withFlattenLock it }
                flattenLocal(readAllBytes(channel), hash)
            }
        }
    }

    internal fun ensureFlattenedFile(html: File): FlattenedBook {
        val hash = store.contentHash(html)
        store.cached(hash)?.let { return it }
        return flattenLocal(html.readBytes(), hash)
    }

    internal suspend fun openContentFile(html: File): BookContent {
        val flattened = ensureFlattenedFile(html)
        return openFlattenedContent(flattened.file)
    }

    internal fun parseChaptersFile(html: File): List<Chapter> = ensureFlattenedFile(html).chapters

    private fun flattenLocal(bytes: ByteArray, hash: String): FlattenedBook =
        store.store(hash) { out -> flattenTo(bytes, out) }

    /**
     * 解码 → 消毒（注入标题锚点）→ 整篇一次压平。
     * 锚点表以虚拟文件名 [DOCUMENT_FILE] 为前缀（`book.html#id` → 元素首个文本的压平偏移），
     * 链接 span 在压平后由锚点表解析为目标偏移（同 DOCX/EPUB 的后处理）。
     */
    private fun flattenTo(bytes: ByteArray, out: File): FlattenContent {
        val sanitized = HtmlSanitizer.sanitize(decode(bytes), isFragment = false)

        val flattener = HtmlTextFlattener(newParser)
        val anchors = LinkedHashMap<String, Long>()
        val totalChars: Long
        val sink: FlattenSink
        out.bufferedWriter(Charsets.UTF_8).use { writer ->
            sink = FlattenSink(writer)
            // 文件级隐式锚点：无 fragment 的目标解析用
            anchors[DOCUMENT_FILE] = sink.nextTextOffset
            sanitized.html.byteInputStream(Charsets.UTF_8).use { input ->
                flattener.flatten(
                    input,
                    sink,
                    onAnchor = { id ->
                        anchors.putIfAbsent("$DOCUMENT_FILE#$id", sink.anchorOffset())
                    },
                    currentFile = DOCUMENT_FILE,
                )
            }
            sink.finish()
            totalChars = sink.charCount
        }
        return FlattenContent(
            chapters = buildHeadingChapters(sanitized.headings, anchors, DOCUMENT_FILE, totalChars),
            anchors = anchors,
            spans = resolveHtmlLinkSpans(sink.recordedSpans(), anchors),
        )
    }

    /** 编码探测（同 TXT 管线）：BOM 优先，探测失败按 UTF-8；BOM 长度显式跳过（UTF-16LE/BE 具名解码器不剥 BOM）。 */
    internal fun decode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val detection = EncodingDetector.detect(bytes)
        val bom = EncodingDetector.bomLengthOf(bytes)
        return String(bytes, bom, bytes.size - bom, detection.charset)
    }

    private fun readAllBytes(channel: SeekableByteChannel): ByteArray {
        channel.position(0)
        return Channels.newInputStream(channel).use { it.readBytes() }
    }

    override suspend fun textSpans(uri: Uri): List<TextSpan>? = withContext(Dispatchers.IO) {
        ensureFlattened(uri).spans.takeIf { it.isNotEmpty() }
    }

    internal fun textSpansFile(html: File): List<TextSpan> = ensureFlattenedFile(html).spans

    private fun fallbackTitle(uri: Uri): String =
        displayNameOf(uri)
            ?.substringBeforeLast('.')
            ?.takeIf { it.isNotBlank() }
            ?: "未知书名"

    companion object {
        /** 压平时的虚拟当前文件：HTML 书单文件，链接目标都相对它解析。 */
        const val DOCUMENT_FILE = "book.html"

        /** parseMeta 的头部采样上限：head 里的 title/meta 不会离文件头太远。 */
        private const val META_SAMPLE_BYTES = 512L * 1024L
    }
}

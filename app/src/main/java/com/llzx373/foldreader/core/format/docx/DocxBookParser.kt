package com.llzx373.foldreader.core.format.docx

import android.net.Uri
import android.util.Xml
import com.llzx373.foldreader.core.format.BookContent
import com.llzx373.foldreader.core.format.BookMeta
import com.llzx373.foldreader.core.format.BookParser
import com.llzx373.foldreader.core.format.Chapter
import com.llzx373.foldreader.core.format.ConvertedBookStore
import com.llzx373.foldreader.core.format.CoverImage
import com.llzx373.foldreader.core.format.FlattenContent
import com.llzx373.foldreader.core.format.FlattenedBook
import com.llzx373.foldreader.core.format.TextSpan
import com.llzx373.foldreader.core.format.epub.FlattenSink
import com.llzx373.foldreader.core.format.epub.HtmlTextFlattener
import com.llzx373.foldreader.core.format.epub.sniffImageExtension
import com.llzx373.foldreader.core.format.html.HtmlSanitizer
import com.llzx373.foldreader.core.format.html.buildHeadingChapters
import com.llzx373.foldreader.core.format.html.resolveHtmlLinkSpans
import com.llzx373.foldreader.core.format.newPullParser
import java.io.File
import java.nio.channels.SeekableByteChannel
import java.nio.charset.Charset
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.zwobble.mammoth.DocumentConverter
import org.zwobble.mammoth.images.ImageConverter

/**
 * DOCX 解析器：复用 EPUB 的压平管线——首开时先做 [DocxSecurity] 安全校验，
 * 再经 mammoth 转 HTML、[HtmlSanitizer] 消毒（顺带注入标题锚点），
 * 最后由 [HtmlTextFlattener] 压平为 `convertedDir/<contentHash>.txt` + sidecar，
 * 之后由 [openFlattenedContent]（TXT 管线）提供内容与偏移索引。
 *
 * 目录 = 消毒时收集的 h1-h6（[HtmlSanitizer.Heading]），边界 = 标题锚点在压平流中的偏移，
 * depth = 标题级别 - 1。无标题的书退化为单个「正文」章。
 *
 * 图片：mammoth 的 [ImageConverter.ImgElement] 把图片字节收集为 `word/media/image-N.<ext>`
 * 稳定路径并写进 `<img src>`，压平期经 flattener 的 onImage 抽取到 `<hash>.images/`（同 EPUB）。
 *
 * Uri 壳方法只做 复制/哈希/委托；核心逻辑走 File 参数的内部方法以便 JVM 单测。
 */
class DocxBookParser(
    convertedDir: File,
    private val openFlattenedContent: suspend (File) -> BookContent,
    private val openChannel: (Uri) -> SeekableByteChannel,
    private val displayNameOf: (Uri) -> String?,
    private val bookIdResolver: suspend (Uri, Long?) -> Long? = { _, _ -> null },
    private val onChaptersIndexed: suspend (bookId: Long, chapters: List<Chapter>) -> Unit = { _, _ -> },
    private val newParser: () -> XmlPullParser = { Xml.newPullParser() },
    /** 图片原始尺寸探测（默认 BitmapFactory 只读边界；JVM 测试注入假实现）。返回 null = 不可解码。 */
    private val imageSizer: (ByteArray) -> IntArray? = { bytes ->
        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        if (opts.outWidth > 0 && opts.outHeight > 0) intArrayOf(opts.outWidth, opts.outHeight) else null
    },
) : BookParser {

    private val store = ConvertedBookStore(convertedDir)

    override suspend fun parseMeta(uri: Uri): BookMeta = withContext(Dispatchers.IO) {
        openChannel(uri).use { channel ->
            val byteSize = channel.size()
            val tmp = store.newTempFile(".docx")
            try {
                store.copyChannel(channel, tmp)
                val meta = ZipFile(tmp).use { zip -> readCoreProperties(zip) }
                BookMeta(
                    title = meta.title?.takeIf { it.isNotBlank() } ?: fallbackTitle(uri),
                    author = meta.creators.joinToString(", ").takeIf { it.isNotBlank() },
                    encoding = Charsets.UTF_8.name(),
                    byteSize = byteSize,
                    description = meta.description,
                    publisher = meta.publisher,
                    language = meta.language,
                    pubDate = meta.date,
                    subjects = meta.subjects,
                )
            } finally {
                tmp.delete()
            }
        }
    }

    /** docProps/core.xml 的 Dublin Core 元数据；条目缺失/解析失败时各字段留空（导入回退文件名）。 */
    internal data class CoreMeta(
        val title: String? = null,
        val creators: List<String> = emptyList(),
        val description: String? = null,
        val publisher: String? = null,
        val language: String? = null,
        val date: String? = null,
        val subjects: List<String> = emptyList(),
    )

    internal fun readCoreProperties(zip: ZipFile): CoreMeta {
        val entry = zip.getEntry(CORE_PROPS_PATH) ?: return CoreMeta()
        if (entry.isDirectory) return CoreMeta()
        return runCatching {
            zip.getInputStream(entry).use { input ->
                val parser = newPullParser(newParser)
                parser.setInput(input, Charsets.UTF_8.name())
                var title: String? = null
                val creators = ArrayList<String>()
                var description: String? = null
                var publisher: String? = null
                var language: String? = null
                var date: String? = null
                val subjects = ArrayList<String>()
                var event = parser.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    if (event == XmlPullParser.START_TAG) {
                        // 命名空间处理已统一关闭（newPullParser），标签名按字面量读取（dc:title 等）
                        when (parser.name) {
                            "dc:title" -> title = parser.nextText().trim()
                            "dc:creator" -> creators += parser.nextText().trim()
                            "dc:description" -> description = parser.nextText().trim()
                            "dc:publisher" -> publisher = parser.nextText().trim()
                            "dc:language" -> language = parser.nextText().trim()
                            "dcterms:created", "dc:date" -> if (date == null) date = parser.nextText().trim()
                            "dc:subject" -> subjects += parser.nextText().trim()
                        }
                    }
                    event = parser.next()
                }
                CoreMeta(
                    title = title,
                    creators = creators.filter { it.isNotEmpty() },
                    description = description?.takeIf { it.isNotEmpty() },
                    publisher = publisher?.takeIf { it.isNotEmpty() },
                    language = language?.takeIf { it.isNotEmpty() },
                    date = date?.takeIf { it.isNotEmpty() },
                    subjects = subjects.filter { it.isNotEmpty() },
                )
            }
        }.getOrDefault(CoreMeta())
    }

    /** 封面：`docProps/thumbnail.jpeg`（Word 缩略图）→ `word/media/` 第一张图；都没有返回 null。 */
    override suspend fun extractCover(uri: Uri): CoverImage? = withContext(Dispatchers.IO) {
        openChannel(uri).use { channel ->
            val tmp = store.newTempFile(".docx")
            try {
                store.copyChannel(channel, tmp)
                extractCoverFile(tmp)
            } finally {
                tmp.delete()
            }
        }
    }

    internal fun extractCoverFile(docx: File): CoverImage? =
        ZipFile(docx).use { zip ->
            coverBytes(zip, THUMBNAIL_PATH)
                ?: zip.entries().asSequence()
                    .filter { !it.isDirectory && it.name.startsWith(IMAGE_PATH_PREFIX) }
                    .mapNotNull { coverBytes(zip, it.name) }
                    .firstOrNull()
        }

    /** 读条目共字节并嗅探图片类型（魔数优先于扩展名）；非图片返回 null。 */
    private fun coverBytes(zip: ZipFile, path: String): CoverImage? {
        val entry = zip.getEntry(path) ?: return null
        if (entry.isDirectory) return null
        val bytes = zip.getInputStream(entry).use { it.readBytes() }
        val extension = sniffImageExtension(path, bytes) ?: return null
        return CoverImage(bytes, extension)
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
                val tmpDocx = store.newTempFile(".docx")
                try {
                    store.copyChannel(channel, tmpDocx)
                    flattenLocal(tmpDocx, hash)
                } finally {
                    tmpDocx.delete()
                }
            }
        }
    }

    internal fun ensureFlattenedFile(docx: File): FlattenedBook {
        val hash = store.contentHash(docx)
        store.cached(hash)?.let { return it }
        return flattenLocal(docx, hash)
    }

    internal suspend fun openContentFile(docx: File): BookContent {
        val flattened = ensureFlattenedFile(docx)
        return openFlattenedContent(flattened.file)
    }

    internal fun parseChaptersFile(docx: File): List<Chapter> = ensureFlattenedFile(docx).chapters

    private fun flattenLocal(docx: File, hash: String): FlattenedBook =
        store.store(hash) { out -> flattenTo(docx, out, store.imagesDir(hash)) }

    /**
     * 单文件管线：DOCX → mammoth HTML → 消毒（注入标题锚点）→ 整篇一次压平。
     * 锚点表以虚拟文件名 [DOCUMENT_FILE] 为前缀（`document.html#id` → 元素首个文本的压平偏移），
     * 链接 span 在压平后由锚点表解析为目标偏移（同 EPUB 的后处理）。
     */
    private fun flattenTo(docx: File, out: File, imagesDir: File): FlattenContent {
        docx.inputStream().use { DocxSecurity.validate(it) }
        val (html, images) = convertToHtml(docx)
        val sanitized = HtmlSanitizer.sanitize(html, isFragment = true, imagePathPrefix = IMAGE_PATH_PREFIX)

        val flattener = HtmlTextFlattener(newParser)
        val anchors = LinkedHashMap<String, Long>()
        val totalChars: Long
        val sink: FlattenSink
        out.bufferedWriter(Charsets.UTF_8).use { writer ->
            sink = FlattenSink(writer)
            // 文件级隐式锚点：无 fragment 的目标（外部目录跳转等）解析用
            anchors[DOCUMENT_FILE] = sink.nextTextOffset
            sanitized.html.byteInputStream(Charsets.UTF_8).use { input ->
                flattener.flatten(
                    input,
                    sink,
                    onAnchor = { id ->
                        anchors.putIfAbsent("$DOCUMENT_FILE#$id", sink.anchorOffset())
                    },
                    currentFile = DOCUMENT_FILE,
                    onImage = { zipPath -> extractImage(zipPath, images, imagesDir) },
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

    /**
     * mammoth DOCX → HTML；自定义图片转换器把图片字节收集进返回的 map
     * （键 = 稳定路径 `word/media/image-N.<ext>`，同时写进 `<img src>`），
     * 压平期 onImage 据此把图落地。返回的属性 map 必须可变（mammoth 会补写 alt）。
     */
    private fun convertToHtml(docx: File): Pair<String, Map<String, ByteArray>> {
        val images = LinkedHashMap<String, ByteArray>()
        var imageIndex = 0
        val converter = DocumentConverter()
            .imageConverter(
                ImageConverter.ImgElement { image ->
                    val bytes = image.inputStream.use { it.readBytes() }
                    val ext = imageExtensionOf(image.contentType)
                    val path = "${IMAGE_PATH_PREFIX}image-${imageIndex++}.$ext"
                    images[path] = bytes
                    hashMapOf("src" to path)
                },
            )
        return converter.convertToHtml(docx).value to images
    }

    /** 图片落盘：收集表里有这条路径且可解码 → 写 imagesDir（路径打平为文件名）并返回原始尺寸；否则 null（跳过）。 */
    private fun extractImage(zipPath: String, images: Map<String, ByteArray>, imagesDir: File): IntArray? {
        val bytes = images[zipPath] ?: return null
        val size = imageSizer(bytes) ?: return null
        imagesDir.mkdirs()
        File(imagesDir, store.imageFileName(zipPath)).writeBytes(bytes)
        return size
    }

    /**
     * 图片文件在压平期就已抽取到 `converted/<hash>.images/`，这里只需算出 contentHash 定位它。
     * 采样哈希直接读 channel 即可，不要整本复制——每张图都会调用这里。
     */
    override suspend fun imageFile(uri: Uri, imagePath: String): File? = withContext(Dispatchers.IO) {
        openChannel(uri).use { channel ->
            store.imageFile(store.contentHash(channel), imagePath).takeIf { it.isFile }
        }
    }

    /** 图片本地文件（需已压平抽取；未压平时返回 null——正常流程打开书必先压平）。 */
    internal fun imageFileOf(docx: File, imagePath: String): File? =
        store.imageFile(store.contentHash(docx), imagePath).takeIf { it.isFile }

    override suspend fun textSpans(uri: Uri): List<TextSpan>? = withContext(Dispatchers.IO) {
        ensureFlattened(uri).spans.takeIf { it.isNotEmpty() }
    }

    internal fun textSpansFile(docx: File): List<TextSpan> = ensureFlattenedFile(docx).spans

    private fun fallbackTitle(uri: Uri): String =
        displayNameOf(uri)
            ?.substringBeforeLast('.')
            ?.takeIf { it.isNotBlank() }
            ?: "未知书名"

    companion object {
        /**
         * 压平时的虚拟当前文件：DOCX 全书压成一篇 HTML，链接/图片目标都相对它解析。
         * 必须在 zip 根级（无目录前缀），否则 mammoth 注入的 `word/media/…` 图片路径
         * 会被 flattener 当成相对目录再拼一层。
         */
        const val DOCUMENT_FILE = "document.html"

        /** mammoth 图片转换器注入的 `<img src>` 路径前缀；消毒白名单与封面兜底共用。 */
        const val IMAGE_PATH_PREFIX = "word/media/"

        private const val CORE_PROPS_PATH = "docProps/core.xml"
        private const val THUMBNAIL_PATH = "docProps/thumbnail.jpeg"

        /** 图片 contentType → 扩展名（写进收集表键，onImage 只认收集表，扩展名仅参与封面嗅探兜底）。 */
        private fun imageExtensionOf(contentType: String): String = when (contentType.lowercase()) {
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            "image/bmp" -> "bmp"
            "image/svg+xml" -> "svg"
            else -> contentType.substringAfter('/', "png").lowercase()
        }
    }
}

package com.llzx373.foldreader.core.format.epub

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
import com.llzx373.foldreader.core.format.PageLabel
import com.llzx373.foldreader.core.format.TextSpan
import com.llzx373.foldreader.core.format.TextSpanType
import java.io.File
import java.nio.channels.SeekableByteChannel
import java.nio.charset.Charset
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser

/**
 * EPUB 解析器：首开时把整书按 spine 顺序经 [HtmlTextFlattener] 压平为
 * `convertedDir/<contentHash>.txt`（UTF-8 纯文本，contentHash 为原书采样哈希，与导入去重一致），
 * 之后由 [openFlattenedContent]（TXT 管线）提供内容与偏移索引。
 * 章节取真实 TOC（无 TOC 退化为按 spine 项分章），边界 = 各 spine 项文本在压平流中的起点；
 * 章节随压平产物缓存于 `<contentHash>.toc` sidecar，并在压平完成时经 [onChaptersIndexed] 回填，
 * 使 TXT 启发式章节扫描不参与 EPUB。
 *
 * Uri 壳方法只做 复制/哈希/委托；核心逻辑走 File 参数的内部方法以便 JVM 单测。
 */
class EpubBookParser(
    convertedDir: File,
    private val openFlattenedContent: suspend (File) -> BookContent,
    private val openChannel: (Uri) -> SeekableByteChannel,
    private val displayNameOf: (Uri) -> String?,
    private val bookIdResolver: suspend (Uri) -> Long? = { null },
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
            val tmp = store.newTempFile(".epub")
            try {
                store.copyChannel(channel, tmp)
                val structure = ZipFile(tmp).use { EpubStructure.parse(it, newParser) }
                val meta = structure.meta
                BookMeta(
                    title = meta.title?.takeIf { it.isNotBlank() } ?: fallbackTitle(uri),
                    author = formatCreators(meta.creators),
                    encoding = Charsets.UTF_8.name(),
                    byteSize = byteSize,
                    description = meta.description,
                    publisher = meta.publisher,
                    language = meta.language,
                    pubDate = meta.date,
                    subjects = meta.subjects,
                    identifier = meta.identifier,
                    seriesName = meta.seriesName,
                    seriesIndex = meta.seriesIndex,
                )
            } finally {
                tmp.delete()
            }
        }
    }

    override suspend fun extractCover(uri: Uri): CoverImage? = withContext(Dispatchers.IO) {
        openChannel(uri).use { channel ->
            val tmp = store.newTempFile(".epub")
            try {
                store.copyChannel(channel, tmp)
                extractCoverFile(tmp)
            } finally {
                tmp.delete()
            }
        }
    }

    /** 读取封面条目字节；魔数优先于扩展名判定图片类型，非图片返回 null。 */
    internal fun extractCoverFile(epub: File): CoverImage? =
        ZipFile(epub).use { zip ->
            val structure = EpubStructure.parse(zip, newParser)
            val coverPath = structure.coverFile ?: return null
            val entry = zip.getEntry(coverPath) ?: return null
            if (entry.isDirectory) return null
            val bytes = zip.getInputStream(entry).use { it.readBytes() }
            val extension = sniffImageExtension(coverPath, bytes) ?: return null
            CoverImage(bytes, extension)
        }

    override suspend fun openContent(uri: Uri, charsetOverride: Charset?): BookContent =
        withContext(Dispatchers.IO) {
            // charsetOverride 忽略：压平产物固定 UTF-8
            openFlattenedContent(ensureFlattenedAndIndexChapters(uri).file)
        }

    /** 预热：只做压平，不取内容。导入后由后台队列调用，好让首次打开直接命中缓存。 */
    override suspend fun prewarm(uri: Uri) {
        withContext(Dispatchers.IO) { ensureFlattenedAndIndexChapters(uri) }
    }

    /** 压平（命中缓存则零成本）；本次确实新压平时顺带把真实 TOC 回填进章节表。 */
    private suspend fun ensureFlattenedAndIndexChapters(uri: Uri): FlattenedBook {
        val flattened = ensureFlattened(uri)
        if (flattened.fresh) {
            bookIdResolver(uri)?.let { bookId ->
                runCatching { onChaptersIndexed(bookId, flattened.chapters) }
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
                val tmpEpub = store.newTempFile(".epub")
                try {
                    store.copyChannel(channel, tmpEpub)
                    flattenLocal(tmpEpub, hash)
                } finally {
                    tmpEpub.delete()
                }
            }
        }
    }

    internal fun ensureFlattenedFile(epub: File): FlattenedBook {
        val hash = store.contentHash(epub)
        store.cached(hash)?.let { return it }
        return flattenLocal(epub, hash)
    }

    internal suspend fun openContentFile(epub: File): BookContent {
        val flattened = ensureFlattenedFile(epub)
        return openFlattenedContent(flattened.file)
    }

    internal fun parseChaptersFile(epub: File): List<Chapter> = ensureFlattenedFile(epub).chapters

    private fun flattenLocal(epub: File, hash: String): FlattenedBook =
        store.store(hash) { out -> flattenTo(epub, out, store.imagesDir(hash)) }

    /**
     * 按 spine 顺序逐 XHTML 压平（linear="no" 的项跳过；全部 no 的畸形书回退全部压平），
     * 记录各项文本起点与元素锚点（`文件#id` → 元素首个文本的压平偏移），
     * 最后由 TOC（fragment 经锚点表解析）/边界生成章节，page-list 生成纸书页码。
     * 样式/链接/图片 span 随压平记录：链接 payload 在全书压平后由锚点表解析为目标偏移
     * （无法解析的内部链接丢弃）；图片字节同时抽取到 [imagesDir]（zip 路径打平为文件名）。
     */
    private fun flattenTo(epub: File, out: File, imagesDir: File): FlattenContent {
        ZipFile(epub).use { zip ->
            val structure = EpubStructure.parse(zip, newParser)
            val flattener = HtmlTextFlattener(newParser)
            val itemStarts = LinkedHashMap<String, Long>()
            val anchors = LinkedHashMap<String, Long>()
            val totalChars: Long
            val spineItems = structure.spine.filter { it.linear }.ifEmpty { structure.spine }
            val sink: FlattenSink
            out.bufferedWriter(Charsets.UTF_8).use { writer ->
                sink = FlattenSink(writer)
                for (item in spineItems) {
                    if (item.mediaType?.contains("html", ignoreCase = true) == false) continue
                    val entry = zip.getEntry(item.file) ?: continue
                    sink.blockBoundary()
                    val start = sink.nextTextOffset
                    itemStarts.putIfAbsent(item.file, start)
                    // 文件级隐式锚点：fragment 为空的目标（guide/preferredStart）解析用
                    anchors.putIfAbsent(item.file, start)
                    zip.getInputStream(entry).use { input ->
                        flattener.flatten(
                            input,
                            sink,
                            onAnchor = { id ->
                                anchors.putIfAbsent("${item.file}#$id", sink.anchorOffset())
                            },
                            currentFile = item.file,
                            onImage = { zipPath -> extractImage(zip, zipPath, imagesDir) },
                        )
                    }
                }
                sink.finish()
                totalChars = sink.charCount
            }
            return FlattenContent(
                chapters = buildChapters(structure, itemStarts, anchors, totalChars),
                anchors = anchors,
                pageLabels = resolvePageLabels(structure.pageList, itemStarts, anchors),
                spans = resolveLinkSpans(sink.recordedSpans(), anchors),
            )
        }
    }

    /** 图片条目确认 + 抽取：zip 内存在且为图片扩展名 → 写 imagesDir（zip 路径打平为文件名）并返回原始尺寸；否则 null（跳过）。 */
    private fun extractImage(zip: ZipFile, zipPath: String, imagesDir: File): IntArray? {
        if (zipPath.substringAfterLast('.', "").lowercase() !in IMAGE_EXTENSIONS) return null
        val entry = zip.getEntry(zipPath) ?: return null
        if (entry.isDirectory) return null
        val bytes = zip.getInputStream(entry).use { it.readBytes() }
        val size = imageSizer(bytes) ?: return null
        imagesDir.mkdirs()
        File(imagesDir, store.imageFileName(zipPath)).writeBytes(bytes)
        return size
    }

    /**
     * 链接 span 后处理（全书压平完成后）：内部目标经锚点表解析为 `#目标charOffset`
     * （fragment 未命中回退文件级锚点，两者皆无则丢弃该 span）；外部 http(s) URL 原样保留。
     */
    private fun resolveLinkSpans(spans: List<TextSpan>, anchors: Map<String, Long>): List<TextSpan> =
        spans.mapNotNull { span ->
            if (span.type != TextSpanType.LINK && span.type != TextSpanType.NOTEREF) return@mapNotNull span
            val payload = span.payload ?: return@mapNotNull null
            if (payload.startsWith("http://") || payload.startsWith("https://")) return@mapNotNull span
            val file = payload.substringBefore('#')
            val fragment = payload.substringAfter('#', "").takeIf { it.isNotEmpty() }
            val target = fragment?.let { anchors["$file#$it"] ?: anchors[file] } ?: anchors[file]
            target?.let { span.copy(payload = "#$it") }
        }

    internal fun buildChapters(
        structure: EpubStructure,
        itemStarts: Map<String, Long>,
        anchors: Map<String, Long> = emptyMap(),
        totalChars: Long,
    ): List<Chapter> {
        // start → title；TOC 按文档序拍平，同一偏移（锚点重复/同文件多点）保留先出现的标题
        val points = LinkedHashMap<Long, String>()
        val toc = structure.toc
        if (!toc.isNullOrEmpty()) {
            for (entry in toc) {
                val start = resolveTargetOffset(entry, itemStarts, anchors) ?: continue
                if (start < 0L || start >= totalChars) continue
                points.putIfAbsent(start, entry.label)
            }
        } else {
            for ((file, start) in itemStarts) {
                val name = file.substringAfterLast('/').substringBeforeLast('.')
                points.putIfAbsent(start, name.ifBlank { file })
            }
        }
        val starts = points.keys.sorted()
        val chapters = starts.mapIndexed { index, start ->
            Chapter(
                title = points.getValue(start),
                charStart = start,
                charEnd = if (index + 1 < starts.size) starts[index + 1] else totalChars,
            )
        }.filter { it.charEnd > it.charStart }
        if (chapters.isNotEmpty()) return chapters
        return listOf(
            Chapter(structure.title?.takeIf { it.isNotBlank() } ?: "正文", 0L, totalChars),
        )
    }

    /** TOC/page-list 目标解析：fragment 命中锚点表用之，未命中回退文件起点；文件未知（被跳过的 linear=no 项）返回 null。 */
    private fun resolveTargetOffset(
        entry: EpubStructure.TocEntry,
        itemStarts: Map<String, Long>,
        anchors: Map<String, Long>,
    ): Long? =
        entry.fragment?.let { anchors["${entry.targetFile}#$it"] }
            ?: itemStarts[entry.targetFile]

    /** page-list 序列 → 纸书页码：偏移升序，同偏移保留先出现 label。 */
    internal fun resolvePageLabels(
        pageList: List<EpubStructure.TocEntry>,
        itemStarts: Map<String, Long>,
        anchors: Map<String, Long>,
    ): List<PageLabel> {
        if (pageList.isEmpty()) return emptyList()
        val byOffset = LinkedHashMap<Long, String>()
        for (entry in pageList) {
            val offset = resolveTargetOffset(entry, itemStarts, anchors) ?: continue
            byOffset.putIfAbsent(offset, entry.label)
        }
        return byOffset.entries
            .sortedBy { it.key }
            .map { (offset, label) -> PageLabel(label, offset) }
    }

    override suspend fun preferredStartOffset(uri: Uri): Long? = withContext(Dispatchers.IO) {
        openChannel(uri).use { channel ->
            val tmp = store.newTempFile(".epub")
            try {
                store.copyChannel(channel, tmp)
                preferredStartOffsetFile(tmp)
            } finally {
                tmp.delete()
            }
        }
    }

    /** landmarks/guide 正文起点 → 压平偏移；无目标或锚点未命中返回 null。 */
    internal fun preferredStartOffsetFile(epub: File): Long? {
        val structure = ZipFile(epub).use { EpubStructure.parse(it, newParser) }
        val target = structure.preferredStartTarget() ?: return null
        val flattened = ensureFlattenedFile(epub)
        return target.fragment?.let { flattened.anchors["${target.targetFile}#$it"] }
            ?: flattened.anchors[target.targetFile]
    }

    /**
     * 纸书页码来自压平 sidecar：直接走 [ensureFlattened]（channel 采样哈希，命中缓存时不复制文件），
     * 不要先把整本 EPUB 复制成临时文件再取——打开路径上每次都会调用这里。
     */
    override suspend fun pageLabels(uri: Uri): List<PageLabel>? = withContext(Dispatchers.IO) {
        ensureFlattened(uri).pageLabels.takeIf { it.isNotEmpty() }
    }

    internal fun pageLabelsFile(epub: File): List<PageLabel>? =
        ensureFlattenedFile(epub).pageLabels.takeIf { it.isNotEmpty() }

    override suspend fun textSpans(uri: Uri): List<TextSpan>? = withContext(Dispatchers.IO) {
        ensureFlattened(uri).spans.takeIf { it.isNotEmpty() }
    }

    internal fun textSpansFile(epub: File): List<TextSpan> = ensureFlattenedFile(epub).spans

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
    internal fun imageFileOf(epub: File, imagePath: String): File? =
        store.imageFile(store.contentHash(epub), imagePath).takeIf { it.isFile }

    private fun fallbackTitle(uri: Uri): String =
        displayNameOf(uri)
            ?.substringBeforeLast('.')
            ?.takeIf { it.isNotBlank() }
            ?: "未知书名"

    private companion object {
        /** 内嵌图片扩展名白名单（svg 无法由 BitmapFactory 解码，尺寸探测失败自然跳过）。 */
        val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "svg")
    }
}

/**
 * 图片嗅探：魔数优先（防错误扩展名），否则按文件名扩展名兜底；
 * 都不认识返回 null（调用方跳过该封面条目）。
 */
internal fun sniffImageExtension(name: String, bytes: ByteArray): String? {
    fun at(i: Int) = bytes.getOrNull(i)
    val byMagic = when {
        at(0) == 0xFF.toByte() && at(1) == 0xD8.toByte() && at(2) == 0xFF.toByte() -> "jpg"
        at(0) == 0x89.toByte() && at(1) == 'P'.code.toByte() &&
            at(2) == 'N'.code.toByte() && at(3) == 'G'.code.toByte() -> "png"
        at(0) == 'G'.code.toByte() && at(1) == 'I'.code.toByte() &&
            at(2) == 'F'.code.toByte() && at(3) == '8'.code.toByte() -> "gif"
        at(0) == 'R'.code.toByte() && at(1) == 'I'.code.toByte() &&
            at(2) == 'F'.code.toByte() && at(3) == 'F'.code.toByte() &&
            at(8) == 'W'.code.toByte() && at(9) == 'E'.code.toByte() &&
            at(10) == 'B'.code.toByte() && at(11) == 'P'.code.toByte() -> "webp"
        else -> null
    }
    if (byMagic != null) return byMagic
    val ext = name.substringAfterLast('.', "").lowercase()
    return ext.takeIf { it in setOf("jpg", "jpeg", "png", "gif", "webp") }
}

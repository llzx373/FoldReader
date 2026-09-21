package com.llzx373.foldreader.feature.importer

import android.content.Context
import android.net.Uri
import com.llzx373.foldreader.core.comic.ComicContainer
import com.llzx373.foldreader.core.comic.ComicContainers
import com.llzx373.foldreader.core.data.db.BookEntity
import com.llzx373.foldreader.core.data.db.BookFormat
import com.llzx373.foldreader.core.data.db.BookSource
import com.llzx373.foldreader.core.data.repository.BookshelfRepository
import com.llzx373.foldreader.core.format.ContentHasher
import com.llzx373.foldreader.core.format.CoverImage
import com.llzx373.foldreader.core.format.EncodingDetection
import com.llzx373.foldreader.core.format.EncodingDetector
import com.llzx373.foldreader.core.format.BookMeta
import com.llzx373.foldreader.core.format.BookParser
import com.llzx373.foldreader.core.format.FormatDetector
import com.llzx373.foldreader.core.format.android.load
import com.llzx373.foldreader.core.format.clean.CleanProfile
import com.llzx373.foldreader.core.format.clean.CleanReport
import com.llzx373.foldreader.core.format.clean.NovelCleaner
import com.llzx373.foldreader.core.format.clean.TsCharMap
import com.llzx373.foldreader.core.format.epub.DrmProtectedException
import com.llzx373.foldreader.core.format.txt.UriChannels
import java.io.File
import java.io.FilterInputStream
import java.io.RandomAccessFile
import java.nio.channels.Channels
import java.nio.channels.SeekableByteChannel
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ImportBookUseCase(
    private val bookshelfRepository: BookshelfRepository,
    private val cleanedDir: File,
    /** 源文件副本目录：导入时把原文原样复制一份进来，正文从此不依赖外部授权。 */
    private val sourceDir: File,
    private val openChannel: (String) -> SeekableByteChannel,
    private val displayNameOf: (String) -> String?,
    private val traditionalMap: () -> Map<Char, Char>,
    /** 非 TXT 格式（EPUB/FB2…）的解析器：导入时仅用于 parseMeta 取元数据。 */
    private val convertedParsers: Map<BookFormat, BookParser> = emptyMap(),
    /** 漫画登记器；null 时漫画会被当作 TXT 处理（JVM 单测可省）。 */
    private val comicImport: ComicImportUseCase? = null,
    /** PDF 登记器；null 时 PDF 报"尚未接入"。 */
    private val pdfImport: PdfImportUseCase? = null,
    /** 封面落盘目录（filesDir/covers）；null 时跳过封面提取。 */
    private val coversDir: File? = null,
    /**
     * 非 TXT 导入成功后的后台预热入队。默认空实现（JVM 单测无需真实队列）。
     * 导入本身不等它——压平放到后台做，用户此刻不预期等待。
     */
    private val enqueuePrewarm: (bookId: Long, uriKey: String, format: BookFormat) -> Unit =
        { _, _, _ -> },
) {

    constructor(
        context: Context,
        bookshelfRepository: BookshelfRepository,
        convertedParsers: Map<BookFormat, BookParser> = emptyMap(),
        coversDir: File? = null,
        enqueuePrewarm: (bookId: Long, uriKey: String, format: BookFormat) -> Unit = { _, _, _ -> },
        comicImport: ComicImportUseCase? = null,
        pdfImport: PdfImportUseCase? = null,
    ) : this(
        bookshelfRepository = bookshelfRepository,
        cleanedDir = File(context.filesDir, "cleaned"),
        sourceDir = File(context.filesDir, "source"),
        openChannel = { key -> UriChannels.open(context, Uri.parse(key)) },
        displayNameOf = { key -> UriChannels.displayName(context, Uri.parse(key)) },
        traditionalMap = { TsCharMap.load(context) },
        convertedParsers = convertedParsers,
        comicImport = comicImport,
        pdfImport = pdfImport,
        coversDir = coversDir,
        enqueuePrewarm = enqueuePrewarm,
    )

    sealed interface Result {
        data class Imported(
            val bookId: Long,
            val title: String,
            val encodingConfidence: Float,
            /** 本次清洗的改动报告；未清洗（noop）时为 null。 */
            val cleanReport: CleanReport? = null,
            /**
             * 选清理导入时**同时**入库的原版那一行；noop 导入为 null。
             * 批量导入按它把两行一起归组，否则同一次导入的原版会漏在分组外。
             */
            val originalBookId: Long? = null,
        ) : Result

        data class DuplicateSameUri(val bookId: Long, val title: String) : Result
        data class DuplicateSameHash(val bookId: Long, val title: String) : Result
        data class Failure(val message: String?) : Result
    }

    suspend fun import(
        uri: Uri,
        profile: CleanProfile = CleanProfile.NONE,
        source: BookSource = BookSource.IMPORT,
        onProgress: (Float) -> Unit = {},
    ): Result = import(uri.toString(), profile, source, onProgress)

    suspend fun import(
        uriKey: String,
        profile: CleanProfile = CleanProfile.NONE,
        source: BookSource = BookSource.IMPORT,
        onProgress: (Float) -> Unit = {},
    ): Result = withContext(Dispatchers.IO) {
        runCatching { doImport(uriKey, profile, source, onProgress) }.getOrElse { Result.Failure(it.message) }
    }

    /**
     * 只跑清洗、不落盘也不写库，回一份改动报告（导入对话框的「预览」用）。
     *
     * 采样前 [PREVIEW_BYTES] 字节即可：报告的用途是判断「这个档位会不会误伤」，
     * 前几十万字足够暴露问题，也不必为了预览把整本读一遍。
     */
    suspend fun preview(
        uriKey: String,
        profile: CleanProfile,
        maxBytes: Int = PREVIEW_BYTES,
    ): CleanReport = withContext(Dispatchers.IO) {
        if (profile.isNoop) return@withContext CleanReport()
        runCatching {
            openChannel(uriKey).use { channel ->
                val head = UriChannels.readHead(channel, EncodingDetector.SAMPLE_SIZE)
                val detection = EncodingDetector.detect(head)
                val bom = EncodingDetector.bomLengthOf(head)
                val bytes = UriChannels.readAt(channel, bom.toLong(), maxBytes)
                NovelCleaner.preview(
                    sample = String(bytes, detection.charset),
                    profile = profile,
                    tsMap = if (profile.toggles.traditionalToSimplified) traditionalMap() else emptyMap(),
                )
            }
        }.getOrDefault(CleanReport())
    }

    suspend fun preview(
        uri: Uri,
        profile: CleanProfile,
        maxBytes: Int = PREVIEW_BYTES,
    ): CleanReport = preview(uri.toString(), profile, maxBytes)

    private suspend fun doImport(
        uriKey: String,
        profile: CleanProfile,
        source: BookSource,
        onProgress: (Float) -> Unit,
    ): Result {
        openChannel(uriKey).use { channel ->
            val displayName = displayNameOf(uriKey)
            val head = UriChannels.readHead(channel, EncodingDetector.SAMPLE_SIZE)
            val format = FormatDetector.detect(displayName, mimeType = null, head = head)
            if (format == BookFormat.PDF && pdfImport != null) {
                // PDF 走独立登记路径（复制源文件 + 写库）：页数/元数据/封面留给打开时回填与后台预热
                return pdfImport.register(Uri.parse(uriKey), source).toImportResult()
            }
            if (format == BookFormat.COMIC && comicImport != null) {
                // 漫画走独立登记路径（只记源位置 + 页数 + 封面，不复制内容、不做文本清洗）。
                // 容器类型在这里重新判定一次：detect() 只回答「是不是漫画」。
                val container = ComicContainers.detect(displayName, null, head) ?: ComicContainer.ZIP
                val outcome = comicImport.register(Uri.parse(uriKey), container, source)
                // rar/tar/7z 要解压过才知道页数：入队预热，等用户点开时缓存已就位
                if (outcome is ComicImportUseCase.Outcome.Registered && outcome.needsPreparation) {
                    runCatching { enqueuePrewarm(outcome.bookId, uriKey, BookFormat.COMIC) }
                }
                return outcome.toImportResult()
            }
            if (format != null && format != BookFormat.TXT) {
                // 非 TXT 不做文本清洗：正文由首开/后台预热压平而来（源文件同样先复制一份进来）
                return importConverted(format, uriKey, channel, source, onProgress)
            }
            if (format == null && FormatDetector.isPdf(head)) {
                return Result.Failure("暂不支持 PDF 格式")
            }
            if (format == BookFormat.PDF && pdfImport == null) {
                return Result.Failure("PDF 尚未接入")
            }
            val detection = EncodingDetector.detect(head)
            val bom = EncodingDetector.bomLengthOf(head)
            val headText = String(
                bytes = head,
                offset = bom,
                length = minOf(head.size - bom, 4096).coerceAtLeast(0),
                charset = detection.charset,
            )

            if (profile.isNoop) {
                val contentHash = hashOf(channel)
                bookshelfRepository.findByContentHash(contentHash)
                    ?.let { return Result.DuplicateSameHash(it.id, it.title) }
                return insert(
                    uriKey = uriKey,
                    contentFileUri = sourceCopyUri(channel, contentHash, BookFormat.TXT),
                    headText = headText,
                    contentHash = contentHash,
                    detection = detection,
                    cleanedFilePath = null,
                    source = source,
                )
            }

            // 源副本与它的哈希必须在**清洗之前**取：清洗会顺着输入流把 channel 读到关闭
            // （`Channels.newInputStream` 的流一关，底下的 channel 也跟着关），之后再想回头
            // 复制原文就只剩 ClosedChannelException。副本按原文哈希命名，所以重复导入时
            // 这一步直接命中已有文件，不产生额外写入。
            val sourceHash = hashOf(channel)
            val contentFileUri = sourceCopyUri(channel, sourceHash, BookFormat.TXT)

            val tmp = File(cleanedDir, ".tmp-${UUID.randomUUID()}.txt")
            try {
                val cleanReport = writeCleanedCopy(channel, bom, detection, profile, tmp, onProgress)
                val cleanedHash = hashOf(tmp)
                val target = File(cleanedDir, "$cleanedHash.txt")
                val existing = bookshelfRepository.findByContentHash(cleanedHash)
                if (existing != null) {
                    // 同一套规则洗出来的产物已经在架上：不重复插一行，直接把它打开。
                    // 原版那一行可能还没有（早期版本只落了清洗版），顺手补齐。
                    val originalId =
                        ensureOriginalRow(uriKey, contentFileUri, headText, sourceHash, detection, source)
                    return Result.Imported(
                        existing.id,
                        existing.title,
                        detection.confidence,
                        cleanReport,
                        originalId,
                    )
                }
                if (!target.exists() && !tmp.renameTo(target)) {
                    throw java.io.IOException("清洗副本写入失败: ${target.absolutePath}")
                }
                // 原版单独占一行，与清洗版并存：书架上是两个条目，用户自己挑看「原文」还是
                // 「清洗后」。两行的 `fileUri` 指向**同一份**源副本，不额外占空间；阅读时按
                // bookId 取 `cleanedFilePath`，所以点哪本读哪份。
                val originalId =
                    ensureOriginalRow(uriKey, contentFileUri, headText, sourceHash, detection, source)
                return insert(
                    uriKey = uriKey,
                    contentFileUri = contentFileUri,
                    headText = headText,
                    contentHash = cleanedHash,
                    detection = detection,
                    cleanedFilePath = target.absolutePath,
                    source = source,
                    cleanReport = cleanReport,
                    originalBookId = originalId,
                )
            } finally {
                tmp.delete()
            }
        }
    }

    /**
     * 确保**原版**在架上有一行，返回它的 bookId。
     *
     * 选了清理导入时，原版与清洗版各占一行：清洗是可逆的用户选择，「洗过之后还想看原文」
     * 不该逼用户重新导入一次。两行的 `fileUri` 指向同一份源副本，重复导入时这一步是空操作。
     */
    private suspend fun ensureOriginalRow(
        uriKey: String,
        contentFileUri: String,
        headText: String,
        sourceHash: String,
        detection: EncodingDetection,
        source: BookSource,
    ): Long {
        bookshelfRepository.findByContentHash(sourceHash)?.let { return it.id }
        return insertRow(
            uriKey = uriKey,
            contentFileUri = contentFileUri,
            headText = headText,
            contentHash = sourceHash,
            detection = detection,
            cleanedFilePath = null,
            source = source,
        ).first
    }

    private fun writeCleanedCopy(
        channel: SeekableByteChannel,
        bomLength: Int,
        detection: EncodingDetection,
        profile: CleanProfile,
        target: File,
        onProgress: (Float) -> Unit,
    ): CleanReport {
        cleanedDir.mkdirs()
        val total = channel.size()
        channel.position(bomLength.toLong())
        val report = target.outputStream().buffered().writer(Charsets.UTF_8).buffered().use { writer ->
            val counting = object : FilterInputStream(Channels.newInputStream(channel)) {
                private var bytes = 0L

                override fun read(): Int = super.read().also { if (it >= 0) report(++bytes) }

                override fun read(b: ByteArray, off: Int, len: Int): Int =
                    super.read(b, off, len).also { if (it > 0) report(bytes + it) }

                private fun report(read: Long) {
                    bytes = read
                    onProgress(if (total > 0) (read.toFloat() / total).coerceIn(0f, 1f) else 1f)
                }
            }
            NovelCleaner.cleanStream(
                reader = counting.reader(detection.charset).buffered(),
                writer = writer,
                profile = profile,
                tsMap = if (profile.toggles.traditionalToSimplified) traditionalMap() else emptyMap(),
            )
        }
        onProgress(1f)
        return report
    }

    private fun hashOf(channel: SeekableByteChannel): String =
        ContentHasher.hash(channel.size()) { offset, length ->
            UriChannels.readAt(channel, offset, length)
        }

    private fun hashOf(file: File): String =
        RandomAccessFile(file, "r").use { hashOf(it.channel) }

    /** 复制实现与 PDF 登记共用，见 [copySourceToPrivateDir]。 */
    private fun sourceCopyUri(channel: SeekableByteChannel, sourceHash: String, format: BookFormat): String =
        copySourceToPrivateDir(sourceDir, channel, sourceHash, format)

    /**
     * 非 TXT（EPUB/FB2…）导入：跳过编码检测/文本清洗/标题启发；
     * 元数据由对应格式 parser 的 parseMeta 提供（失败回退文件名）。
     *
     * 源文件同样先复制一份进来：压平产物虽然也在私有目录，但压平时仍要按源文件算内容哈希去
     * 定位缓存，直接引用外部授权的话，授权一失效连已经压平过的书也打不开。
     */
    private suspend fun importConverted(
        format: BookFormat,
        uriKey: String,
        channel: SeekableByteChannel,
        source: BookSource,
        onProgress: (Float) -> Unit,
    ): Result {
        val contentHash = hashOf(channel)
        bookshelfRepository.findByContentHash(contentHash)
            ?.let { return Result.DuplicateSameHash(it.id, it.title) }
        // 之后的元数据/封面/压平都读这份副本：导入时授权还在，正好把该取的都取掉
        val contentFileUri = sourceCopyUri(channel, contentHash, format)
        val meta = try {
            convertedParsers[format]?.parseMeta(Uri.parse(contentFileUri))
        } catch (e: DrmProtectedException) {
            return Result.Failure(e.message ?: "受 DRM 保护，无法导入")
        } catch (t: Throwable) {
            null
        }
        val title = meta?.title?.takeIf { it.isNotBlank() }
            ?: displayNameOf(uriKey)
                ?.removeSuffix(".zip")
                ?.substringBeforeLast('.')
                ?.takeIf { it.isNotBlank() }
            ?: "未知书名"
        val coverPath = coversDir?.let { dir ->
            runCatching {
                convertedParsers[format]?.extractCover(Uri.parse(contentFileUri))
                    ?.let { writeCover(dir, contentHash, it) }
            }.getOrNull()
        }
        val bookId = bookshelfRepository.upsertBook(
            convertedEntity(title, meta, contentFileUri, contentHash, format, source, coverPath),
        )
        onProgress(1f)
        // 压平交给后台队列：导入即刻返回，等用户真去点开时通常已经命中缓存。
        // 这里只是入队（非阻塞），失败也不影响导入结果。
        runCatching { enqueuePrewarm(bookId, contentFileUri, format) }
        return Result.Imported(bookId, title, encodingConfidence = 1f)
    }

    /** 非 TXT 入库实体：EPUB 扩展元数据全字段映射（meta 为 null 时全部留空）。 */
    internal fun convertedEntity(
        title: String,
        meta: BookMeta?,
        contentFileUri: String,
        contentHash: String,
        format: BookFormat,
        source: BookSource,
        coverPath: String?,
    ): BookEntity = BookEntity(
        title = title,
        author = meta?.author,
        fileUri = contentFileUri,
        contentHash = contentHash,
        format = format,
        totalChars = 0,
        encoding = Charsets.UTF_8.name(),
        importedAt = System.currentTimeMillis(),
        lastReadAt = null,
        cleanedFilePath = null,
        source = source,
        description = meta?.description,
        publisher = meta?.publisher,
        language = meta?.language,
        pubDate = meta?.pubDate,
        subjects = meta?.subjects?.joinToString("\n")?.takeIf { it.isNotBlank() },
        identifier = meta?.identifier,
        seriesName = meta?.seriesName,
        seriesIndex = meta?.seriesIndex,
        coverPath = coverPath,
    )

    /** 封面落盘；同哈希旧封面（扩展名可能不同）先清掉再写。返回绝对路径。 */
    internal fun writeCover(dir: File, contentHash: String, cover: CoverImage): String =
        writeCoverFile(dir, contentHash, cover)

    /**
     * @param uriKey 只用来推断书名（显示名取自源文件）。
     * @param contentFileUri 库里存的**正文来源**——私有目录里的源文件副本，与 [uriKey] 刻意分开：
     *   两者一旦混用，书名会变成内容哈希。
     * @param originalBookId 同一次导入里一并入库的原版那一行（选了清理才有），
     *   由 [Result.Imported] 带出去，供批量导入把两行一起归组。
     */
    private suspend fun insert(
        uriKey: String,
        contentFileUri: String,
        headText: String,
        contentHash: String,
        detection: EncodingDetection,
        cleanedFilePath: String?,
        source: BookSource,
        cleanReport: CleanReport? = null,
        originalBookId: Long? = null,
    ): Result {
        val (bookId, title) = insertRow(
            uriKey = uriKey,
            contentFileUri = contentFileUri,
            headText = headText,
            contentHash = contentHash,
            detection = detection,
            cleanedFilePath = cleanedFilePath,
            source = source,
        )
        return Result.Imported(bookId, title, detection.confidence, cleanReport, originalBookId)
    }

    /** 落一行，返回 `(bookId, title)`。 */
    private suspend fun insertRow(
        uriKey: String,
        contentFileUri: String,
        headText: String,
        contentHash: String,
        detection: EncodingDetection,
        cleanedFilePath: String?,
        source: BookSource,
    ): Pair<Long, String> {
        val baseName = displayNameOf(uriKey)
            ?.substringBeforeLast('.')
            ?.takeIf { it.isNotBlank() }
        val (title, author) = TitleHeuristics.infer(baseName, headText)
        val bookId = bookshelfRepository.upsertBook(
            BookEntity(
                title = title,
                author = author,
                fileUri = contentFileUri,
                contentHash = contentHash,
                format = BookFormat.TXT,
                totalChars = 0,
                encoding = if (cleanedFilePath != null) Charsets.UTF_8.name() else detection.charset.name(),
                importedAt = System.currentTimeMillis(),
                lastReadAt = null,
                cleanedFilePath = cleanedFilePath,
                source = source,
            ),
        )
        return bookId to title
    }

    companion object {
        /** 「预览」采样的字节数：够暴露问题，又不必为了预览把整本读一遍。 */
        const val PREVIEW_BYTES = 256 * 1024
    }
}

/** PDF 登记结果 → 统一的导入结果。 */
internal fun PdfImportUseCase.Outcome.toImportResult(): ImportBookUseCase.Result = when (this) {
    is PdfImportUseCase.Outcome.Registered ->
        ImportBookUseCase.Result.Imported(bookId, title, encodingConfidence = 1f)
    is PdfImportUseCase.Outcome.Duplicate ->
        if (sameUri) ImportBookUseCase.Result.DuplicateSameUri(bookId, title)
        else ImportBookUseCase.Result.DuplicateSameHash(bookId, title)
    is PdfImportUseCase.Outcome.Failure ->
        ImportBookUseCase.Result.Failure(message)
}

/** 漫画登记结果 → 统一的导入结果（两种「重复」要分开，提示文案不同）。 */
internal fun ComicImportUseCase.Outcome.toImportResult(): ImportBookUseCase.Result = when (this) {
    is ComicImportUseCase.Outcome.Registered ->
        ImportBookUseCase.Result.Imported(bookId, title, encodingConfidence = 1f)
    is ComicImportUseCase.Outcome.Duplicate ->
        if (sameUri) ImportBookUseCase.Result.DuplicateSameUri(bookId, title)
        else ImportBookUseCase.Result.DuplicateSameHash(bookId, title)
    is ComicImportUseCase.Outcome.Failure ->
        ImportBookUseCase.Result.Failure(message)
}

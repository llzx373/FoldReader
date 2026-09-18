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
import com.llzx373.foldreader.core.format.TextCleaner
import com.llzx373.foldreader.core.format.TsCharMap
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
        data class Imported(val bookId: Long, val title: String, val encodingConfidence: Float) : Result
        data class DuplicateSameUri(val bookId: Long, val title: String) : Result
        data class DuplicateSameHash(val bookId: Long, val title: String) : Result
        data class Failure(val message: String?) : Result
    }

    suspend fun import(
        uri: Uri,
        options: TextCleaner.CleanOptions = TextCleaner.CleanOptions(),
        source: BookSource = BookSource.IMPORT,
        onProgress: (Float) -> Unit = {},
    ): Result = import(uri.toString(), options, source, onProgress)

    suspend fun import(
        uriKey: String,
        options: TextCleaner.CleanOptions = TextCleaner.CleanOptions(),
        source: BookSource = BookSource.IMPORT,
        onProgress: (Float) -> Unit = {},
    ): Result = withContext(Dispatchers.IO) {
        runCatching { doImport(uriKey, options, source, onProgress) }.getOrElse { Result.Failure(it.message) }
    }

    private suspend fun doImport(
        uriKey: String,
        options: TextCleaner.CleanOptions,
        source: BookSource,
        onProgress: (Float) -> Unit,
    ): Result {
        if (options.isNoop) {
            bookshelfRepository.findByFileUri(uriKey)
                ?.takeIf { it.cleanedFilePath == null }
                ?.let { return Result.DuplicateSameUri(it.id, it.title) }
        }

        openChannel(uriKey).use { channel ->
            val displayName = displayNameOf(uriKey)
            val head = UriChannels.readHead(channel, EncodingDetector.SAMPLE_SIZE)
            val format = FormatDetector.detect(displayName, mimeType = null, head = head)
            if (format == BookFormat.PDF && pdfImport != null) {
                // PDF 也走"只登记不复制"：页数/元数据/封面留给打开时回填与后台预热
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
                // 非 TXT 忽略 CleanOptions：不复制原文件，转换发生在首开压平时
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

            if (options.isNoop) {
                val contentHash = hashOf(channel)
                bookshelfRepository.findByContentHash(contentHash)
                    ?.let { return Result.DuplicateSameHash(it.id, it.title) }
                return insert(uriKey, headText, contentHash, detection, cleanedFilePath = null, source)
            }

            val tmp = File(cleanedDir, ".tmp-${UUID.randomUUID()}.txt")
            try {
                writeCleanedCopy(channel, bom, detection, options, tmp, onProgress)
                val cleanedHash = hashOf(tmp)
                bookshelfRepository.findByContentHash(cleanedHash)
                    ?.let { return Result.DuplicateSameHash(it.id, it.title) }
                val target = File(cleanedDir, "$cleanedHash.txt")
                if (!target.exists() && !tmp.renameTo(target)) {
                    throw java.io.IOException("清洗副本写入失败: ${target.absolutePath}")
                }
                return insert(uriKey, headText, cleanedHash, detection, target.absolutePath, source)
            } finally {
                tmp.delete()
            }
        }
    }

    private fun writeCleanedCopy(
        channel: SeekableByteChannel,
        bomLength: Int,
        detection: EncodingDetection,
        options: TextCleaner.CleanOptions,
        target: File,
        onProgress: (Float) -> Unit,
    ) {
        cleanedDir.mkdirs()
        val total = channel.size()
        channel.position(bomLength.toLong())
        target.outputStream().buffered().writer(Charsets.UTF_8).buffered().use { writer ->
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
            TextCleaner.cleanStream(
                reader = counting.reader(detection.charset).buffered(),
                writer = writer,
                options = options,
                tsMap = if (options.traditionalToSimplified) traditionalMap() else emptyMap(),
            )
        }
        onProgress(1f)
    }

    private fun hashOf(channel: SeekableByteChannel): String =
        ContentHasher.hash(channel.size()) { offset, length ->
            UriChannels.readAt(channel, offset, length)
        }

    private fun hashOf(file: File): String =
        RandomAccessFile(file, "r").use { hashOf(it.channel) }

    /**
     * 非 TXT（EPUB/FB2…）导入：跳过编码检测/TextCleaner/标题启发；
     * 元数据由对应格式 parser 的 parseMeta 提供（失败回退文件名）。
     * 无论 CleanOptions 如何都不复制原文件（压平转换发生在首开）。
     */
    private suspend fun importConverted(
        format: BookFormat,
        uriKey: String,
        channel: SeekableByteChannel,
        source: BookSource,
        onProgress: (Float) -> Unit,
    ): Result {
        bookshelfRepository.findByFileUri(uriKey)
            ?.takeIf { it.cleanedFilePath == null }
            ?.let { return Result.DuplicateSameUri(it.id, it.title) }
        val contentHash = hashOf(channel)
        bookshelfRepository.findByContentHash(contentHash)
            ?.let { return Result.DuplicateSameHash(it.id, it.title) }
        val meta = try {
            convertedParsers[format]?.parseMeta(Uri.parse(uriKey))
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
                convertedParsers[format]?.extractCover(Uri.parse(uriKey))
                    ?.let { writeCover(dir, contentHash, it) }
            }.getOrNull()
        }
        val bookId = bookshelfRepository.upsertBook(
            convertedEntity(title, meta, uriKey, contentHash, format, source, coverPath),
        )
        onProgress(1f)
        // 压平交给后台队列：导入即刻返回，等用户真去点开时通常已经命中缓存。
        // 这里只是入队（非阻塞），失败也不影响导入结果。
        runCatching { enqueuePrewarm(bookId, uriKey, format) }
        return Result.Imported(bookId, title, encodingConfidence = 1f)
    }

    /** 非 TXT 入库实体：EPUB 扩展元数据全字段映射（meta 为 null 时全部留空）。 */
    internal fun convertedEntity(
        title: String,
        meta: BookMeta?,
        uriKey: String,
        contentHash: String,
        format: BookFormat,
        source: BookSource,
        coverPath: String?,
    ): BookEntity = BookEntity(
        title = title,
        author = meta?.author,
        fileUri = uriKey,
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

    private suspend fun insert(
        uriKey: String,
        headText: String,
        contentHash: String,
        detection: EncodingDetection,
        cleanedFilePath: String?,
        source: BookSource,
    ): Result {
        val baseName = displayNameOf(uriKey)
            ?.substringBeforeLast('.')
            ?.takeIf { it.isNotBlank() }
        val (title, author) = TitleHeuristics.infer(baseName, headText)
        val bookId = bookshelfRepository.upsertBook(
            BookEntity(
                title = title,
                author = author,
                fileUri = uriKey,
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
        return Result.Imported(bookId, title, detection.confidence)
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

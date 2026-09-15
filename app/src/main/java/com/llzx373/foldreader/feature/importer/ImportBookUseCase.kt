package com.llzx373.foldreader.feature.importer

import android.content.Context
import android.net.Uri
import com.llzx373.foldreader.core.data.db.BookEntity
import com.llzx373.foldreader.core.data.db.BookFormat
import com.llzx373.foldreader.core.data.repository.BookshelfRepository
import com.llzx373.foldreader.core.format.ContentHasher
import com.llzx373.foldreader.core.format.EncodingDetection
import com.llzx373.foldreader.core.format.EncodingDetector
import com.llzx373.foldreader.core.format.TextCleaner
import com.llzx373.foldreader.core.format.TsCharMap
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
) {

    constructor(context: Context, bookshelfRepository: BookshelfRepository) : this(
        bookshelfRepository = bookshelfRepository,
        cleanedDir = File(context.filesDir, "cleaned"),
        openChannel = { key -> UriChannels.open(context, Uri.parse(key)) },
        displayNameOf = { key -> UriChannels.displayName(context, Uri.parse(key)) },
        traditionalMap = { TsCharMap.load(context) },
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
        onProgress: (Float) -> Unit = {},
    ): Result = import(uri.toString(), options, onProgress)

    suspend fun import(
        uriKey: String,
        options: TextCleaner.CleanOptions = TextCleaner.CleanOptions(),
        onProgress: (Float) -> Unit = {},
    ): Result = withContext(Dispatchers.IO) {
        runCatching { doImport(uriKey, options, onProgress) }.getOrElse { Result.Failure(it.message) }
    }

    private suspend fun doImport(
        uriKey: String,
        options: TextCleaner.CleanOptions,
        onProgress: (Float) -> Unit,
    ): Result {
        if (options.isNoop) {
            bookshelfRepository.findByFileUri(uriKey)
                ?.takeIf { it.cleanedFilePath == null }
                ?.let { return Result.DuplicateSameUri(it.id, it.title) }
        }

        openChannel(uriKey).use { channel ->
            val head = UriChannels.readHead(channel, EncodingDetector.SAMPLE_SIZE)
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
                return insert(uriKey, headText, contentHash, detection, cleanedFilePath = null)
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
                return insert(uriKey, headText, cleanedHash, detection, target.absolutePath)
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

    private suspend fun insert(
        uriKey: String,
        headText: String,
        contentHash: String,
        detection: EncodingDetection,
        cleanedFilePath: String?,
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
            ),
        )
        return Result.Imported(bookId, title, detection.confidence)
    }
}

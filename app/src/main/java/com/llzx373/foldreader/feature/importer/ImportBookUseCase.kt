package com.llzx373.foldreader.feature.importer

import android.content.Context
import android.net.Uri
import com.llzx373.foldreader.core.data.db.BookEntity
import com.llzx373.foldreader.core.data.db.BookFormat
import com.llzx373.foldreader.core.data.repository.BookshelfRepository
import com.llzx373.foldreader.core.format.EncodingDetection
import com.llzx373.foldreader.core.format.EncodingDetector
import com.llzx373.foldreader.core.format.txt.TxtBookParser
import com.llzx373.foldreader.core.format.txt.UriChannels
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ImportBookUseCase(
    private val context: Context,
    private val parser: TxtBookParser,
    private val bookshelfRepository: BookshelfRepository,
) {

    sealed interface Result {
        data class Imported(val bookId: Long, val title: String, val chapterCount: Int) : Result
        data class DuplicateSameUri(val bookId: Long, val title: String) : Result
        data class DuplicateSameHash(val bookId: Long, val title: String) : Result
        data class Failure(val message: String?) : Result
    }

    suspend fun import(uri: Uri): Result = withContext(Dispatchers.IO) {
        runCatching { doImport(uri) }.getOrElse { Result.Failure(it.message) }
    }

    private suspend fun doImport(uri: Uri): Result {
        bookshelfRepository.findByFileUri(uri.toString())?.let {
            return Result.DuplicateSameUri(it.id, it.title)
        }

        val detection: EncodingDetection
        val contentHash: String
        val headText: String
        UriChannels.open(context, uri).use { channel ->
            val head = UriChannels.readHead(channel, EncodingDetector.SAMPLE_SIZE)
            detection = EncodingDetector.detect(head)
            contentHash = ContentHasher.hash(channel.size()) { offset, length ->
                readAt(channel, offset, length)
            }
            val bom = EncodingDetector.bomLengthOf(head)
            headText = String(
                bytes = head,
                offset = bom,
                length = minOf(head.size - bom, 4096).coerceAtLeast(0),
                charset = detection.charset,
            )
        }

        bookshelfRepository.findByContentHash(contentHash)?.let {
            return Result.DuplicateSameHash(it.id, it.title)
        }

        val baseName = UriChannels.displayName(context, uri)
            ?.substringBeforeLast('.')
            ?.takeIf { it.isNotBlank() }
        val (title, author) = TitleHeuristics.infer(baseName, headText)
        val index = parser.index(uri)
        val bookId = bookshelfRepository.upsertBook(
            BookEntity(
                title = title,
                author = author,
                fileUri = uri.toString(),
                contentHash = contentHash,
                format = BookFormat.TXT,
                totalChars = index.charCount,
                encoding = detection.charset.name(),
                importedAt = System.currentTimeMillis(),
                lastReadAt = null,
            ),
        )
        return Result.Imported(bookId, title, index.chapters.size)
    }

    private fun readAt(channel: SeekableByteChannel, offset: Long, length: Int): ByteArray {
        channel.position(offset)
        val buffer = ByteBuffer.allocate(length)
        while (buffer.hasRemaining() && channel.read(buffer) >= 0) Unit
        return buffer.array().copyOf(buffer.position())
    }
}

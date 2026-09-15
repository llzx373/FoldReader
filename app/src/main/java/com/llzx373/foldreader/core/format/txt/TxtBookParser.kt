package com.llzx373.foldreader.core.format.txt

import android.content.Context
import android.net.Uri
import com.llzx373.foldreader.core.format.BookContent
import com.llzx373.foldreader.core.format.BookMeta
import com.llzx373.foldreader.core.format.BookParser
import com.llzx373.foldreader.core.format.Chapter
import com.llzx373.foldreader.core.format.ChapterRules
import com.llzx373.foldreader.core.format.ContentHasher
import com.llzx373.foldreader.core.format.EncodingDetector
import com.llzx373.foldreader.core.format.OffsetIndex
import com.llzx373.foldreader.core.format.OffsetIndexStore
import java.nio.channels.SeekableByteChannel
import java.nio.charset.Charset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class TxtBookParser(
    private val context: Context,
    private val offsetIndexStore: OffsetIndexStore? = null,
    private val indexScope: CoroutineScope? = null,
    private val bookIdResolver: suspend (Uri) -> Long? = { null },
    private val contentUriResolver: suspend (Uri) -> Uri = { it },
    private val onBookIndexed: suspend (bookId: Long, totalChars: Long) -> Unit = { _, _ -> },
    private val chapterRules: suspend () -> List<Regex> = { ChapterRules.DEFAULT },
) : BookParser {

    override suspend fun parseMeta(uri: Uri): BookMeta = withContext(Dispatchers.IO) {
        UriChannels.open(context, contentUriResolver(uri)).use { channel ->
            val sample = UriChannels.readHead(channel, EncodingDetector.SAMPLE_SIZE)
            val detection = EncodingDetector.detect(sample)
            BookMeta(
                title = guessTitle(uri),
                author = null,
                encoding = detection.charset.name(),
                byteSize = channel.size(),
            )
        }
    }

    override suspend fun parseChapters(uri: Uri, charsetOverride: Charset?): List<Chapter> {
        val index = index(uri, charsetOverride)
        persistIndex(uri, index)
        return index.chapters
    }

    suspend fun index(uri: Uri, charsetOverride: Charset? = null): TxtIndex = withContext(Dispatchers.IO) {
        UriChannels.open(context, contentUriResolver(uri)).use { channel ->
            val sample = UriChannels.readHead(channel, EncodingDetector.SAMPLE_SIZE)
            val charset = effectiveCharset(sample, charsetOverride)
            val bom = EncodingDetector.bomLengthOf(sample)
            TxtIndexer.index(channel, charset, bomLength = bom, chapterRules = chapterRules())
        }
    }

    override suspend fun openContent(uri: Uri, charsetOverride: Charset?): BookContent = withContext(Dispatchers.IO) {
        val contentUri = contentUriResolver(uri)
        val channel = UriChannels.open(context, contentUri)
        try {
            val sample = UriChannels.readHead(channel, EncodingDetector.SAMPLE_SIZE)
            val charset = effectiveCharset(sample, charsetOverride)
            val bom = EncodingDetector.bomLengthOf(sample)
            val store = offsetIndexStore
            val scope = indexScope
            val bookId = bookIdResolver(uri)
            if (store != null && bookId != null) {
                val fileLength = channel.size()
                val contentHash = contentHash(channel, fileLength)
                val key = bookId.toString()
                val snapshot = runCatching {
                    store.loadValid(key, fileLength, contentHash, charset.name())
                }.getOrNull()
                if (snapshot != null) {
                    return@withContext TxtBookContent(
                        channel = channel,
                        charset = charset,
                        offsetIndex = OffsetIndex.restore(snapshot),
                    )
                }
                if (scope != null) {
                    return@withContext openLiveContent(
                        uri = contentUri,
                        channel = channel,
                        charset = charset,
                        bom = bom,
                        fileLength = fileLength,
                        contentHash = contentHash,
                        key = key,
                        bookId = bookId,
                        store = store,
                        parentScope = scope,
                        rules = chapterRules(),
                    )
                }
            }
            val index = TxtIndexer.index(channel, charset, bomLength = bom, chapterRules = chapterRules())
            persistIndex(uri, index)
            TxtBookContent(channel, charset, index.offsetIndex)
        } catch (t: Throwable) {
            channel.close()
            throw t
        }
    }

    private fun openLiveContent(
        uri: Uri,
        channel: SeekableByteChannel,
        charset: Charset,
        bom: Int,
        fileLength: Long,
        contentHash: String,
        key: String,
        bookId: Long,
        store: OffsetIndexStore,
        parentScope: CoroutineScope,
        rules: List<Regex>,
    ): BookContent {
        val shared = OffsetIndex(
            blockChars = OffsetIndex.DEFAULT_BLOCK_CHARS,
            initialByteOffset = bom.toLong(),
        )
        val progress = MutableStateFlow(if (fileLength > 0) 0f else 1f)
        val buildScope = CoroutineScope(SupervisorJob(parentScope.coroutineContext.job) + Dispatchers.IO)
        val content = TxtBookContent(
            channel = channel,
            charset = charset,
            offsetIndex = shared,
            indexProgress = progress,
            onClose = { buildScope.cancel() },
        )
        buildScope.launch {
            var indexChannel: SeekableByteChannel? = null
            try {
                indexChannel = UriChannels.open(context, uri)
                store.begin(key)
                val batch = ArrayList<Pair<Int, Long>>(PERSIST_BATCH_BLOCKS)
                TxtIndexer.indexInto(indexChannel, charset, shared, bomLength = bom, chapterRules = rules) { chunkIndex, startByteOffset, endByteOffset ->
                    if (!isActive) throw CancellationException()
                    progress.value = if (fileLength > 0) {
                        (endByteOffset.toFloat() / fileLength).coerceIn(0f, 1f)
                    } else {
                        1f
                    }
                    batch += chunkIndex to startByteOffset
                    if (batch.size >= PERSIST_BATCH_BLOCKS) {
                        val pending = batch.toList()
                        batch.clear()
                        runBlocking { store.appendBlocks(key, pending) }
                    }
                }
                if (batch.isNotEmpty()) store.appendBlocks(key, batch.toList())
                store.complete(key, fileLength, contentHash, charset.name(), shared.totalChars)
                progress.value = 1f
                runCatching { onBookIndexed(bookId, shared.totalChars) }
            } catch (t: Throwable) {
                shared.abort(t)
                if (t !is CancellationException) runCatching { store.invalidate(key) }
                throw t
            } finally {
                indexChannel?.close()
            }
        }
        return content
    }

    private suspend fun persistIndex(uri: Uri, index: TxtIndex) {
        val store = offsetIndexStore ?: return
        val bookId = bookIdResolver(uri) ?: return
        runCatching {
            UriChannels.open(context, contentUriResolver(uri)).use { channel ->
                val fileLength = channel.size()
                store.saveValid(
                    key = bookId.toString(),
                    snapshot = index.offsetIndex.snapshot(),
                    fileLength = fileLength,
                    contentHash = contentHash(channel, fileLength),
                    charsetName = index.charset.name(),
                )
            }
            onBookIndexed(bookId, index.charCount)
        }
    }

    private fun contentHash(channel: SeekableByteChannel, fileLength: Long): String =
        ContentHasher.hash(fileLength) { offset, length -> UriChannels.readAt(channel, offset, length) }

    private fun guessTitle(uri: Uri): String =
        UriChannels.displayName(context, uri)
            ?.substringBeforeLast('.')
            ?.takeIf { it.isNotBlank() }
            ?: "未知书名"

    private companion object {
        const val PERSIST_BATCH_BLOCKS = 512
    }
}

internal fun effectiveCharset(sample: ByteArray, charsetOverride: Charset?): Charset =
    charsetOverride ?: EncodingDetector.detect(sample).charset

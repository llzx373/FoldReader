package com.llzx373.foldreader.core.format.txt

import android.content.Context
import android.net.Uri
import com.llzx373.foldreader.core.debug.DiagnosticLog
import com.llzx373.foldreader.core.format.BookContent
import com.llzx373.foldreader.core.format.BookMeta
import com.llzx373.foldreader.core.format.BookParser
import com.llzx373.foldreader.core.format.Chapter
import com.llzx373.foldreader.core.format.ChapterRules
import com.llzx373.foldreader.core.format.ContentHasher
import com.llzx373.foldreader.core.format.EncodingDetector
import com.llzx373.foldreader.core.format.OffsetIndex
import com.llzx373.foldreader.core.format.OffsetIndexBlock
import com.llzx373.foldreader.core.format.OffsetIndexStore
import java.nio.channels.SeekableByteChannel
import java.nio.charset.Charset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TxtBookParser(
    private val context: Context,
    private val offsetIndexStore: OffsetIndexStore? = null,
    private val indexScope: CoroutineScope? = null,
    private val bookIdResolver: suspend (Uri, Long?) -> Long? = { _, _ -> null },
    private val contentUriResolver: suspend (Uri, Long?) -> Uri = { uri, _ -> uri },
    private val onBookIndexed: suspend (bookId: Long, totalChars: Long) -> Unit = { _, _ -> },
    /** 实时索引（异步建偏移索引）扫描完成后回传章节，调用方负责落库与通知 UI。 */
    private val onChaptersIndexed: suspend (bookId: Long, chapters: List<Chapter>) -> Unit = { _, _ -> },
    /** 按书取章节规则；bookId 为 null（还没入库的书）时只有内置/全局规则可用。 */
    private val chapterRules: suspend (bookId: Long?) -> List<Regex> = { ChapterRules.DEFAULT },
) : BookParser {

    /**
     * 取正文用的真实 URI 与这本书的 bookId。
     *
     * 调用方知道 bookId 时会直接采用，不再按 URI 反查——同一个 `fileUri` 可能对应库里多行
     * （原版 + 清洗版），按 URI 反查会落到另一本上，正文与偏移索引就串了。
     */
    private suspend fun resolveContent(uri: Uri, bookId: Long?): Pair<Uri, Long?> {
        val effectiveBookId = bookIdResolver(uri, bookId)
        return contentUriResolver(uri, effectiveBookId) to effectiveBookId
    }

    override suspend fun parseMeta(uri: Uri): BookMeta = withContext(Dispatchers.IO) {
        val (contentUri, _) = resolveContent(uri, null)
        UriChannels.open(context, contentUri).use { channel ->
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

    override suspend fun parseChapters(uri: Uri, charsetOverride: Charset?): List<Chapter> =
        parseChapters(uri, charsetOverride, null)

    override suspend fun parseChapters(uri: Uri, charsetOverride: Charset?, bookId: Long?): List<Chapter> {
        val index = index(uri, charsetOverride, bookId)
        persistIndex(uri, index, bookId)
        return index.chapters
    }

    suspend fun index(
        uri: Uri,
        charsetOverride: Charset? = null,
        bookId: Long? = null,
    ): TxtIndex = withContext(Dispatchers.IO) {
        val (contentUri, effectiveBookId) = resolveContent(uri, bookId)
        UriChannels.open(context, contentUri).use { channel ->
            val sample = UriChannels.readHead(channel, EncodingDetector.SAMPLE_SIZE)
            val charset = effectiveCharset(sample, charsetOverride)
            val bom = EncodingDetector.bomLengthOf(sample)
            TxtIndexer.index(channel, charset, bomLength = bom, chapterRules = chapterRules(effectiveBookId))
        }
    }

    override suspend fun openContent(uri: Uri, charsetOverride: Charset?): BookContent =
        openContent(uri, charsetOverride, null)

    override suspend fun openContent(uri: Uri, charsetOverride: Charset?, bookId: Long?): BookContent =
        withContext(Dispatchers.IO) {
            val (contentUri, effectiveBookId) = resolveContent(uri, bookId)
            // 「读到的还是原文」这类问题只能靠"实际打开的是哪一份"来定位，记一行现场。
            // 私有文件的文件名是内容哈希，不含用户的文件名。
            DiagnosticLog.line(
                "reader: bookId=$effectiveBookId 正文=${describeContentUri(contentUri)}",
            )
            val channel = UriChannels.open(context, contentUri)
            try {
                val sample = UriChannels.readHead(channel, EncodingDetector.SAMPLE_SIZE)
                val charset = effectiveCharset(sample, charsetOverride)
                val bom = EncodingDetector.bomLengthOf(sample)
                val store = offsetIndexStore
                val scope = indexScope
                if (store != null && effectiveBookId != null) {
                    val fileLength = channel.size()
                    val contentHash = contentHash(channel, fileLength)
                    val key = effectiveBookId.toString()
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
                            bookId = effectiveBookId,
                            store = store,
                            parentScope = scope,
                            rules = chapterRules(effectiveBookId),
                        )
                    }
                }
                val index = TxtIndexer.index(channel, charset, bomLength = bom, chapterRules = chapterRules(effectiveBookId))
                persistIndex(uri, index, effectiveBookId)
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
            // 分段落盘交给独立消费者协程：TxtIndexer 的解码循环不是 suspend 的，
            // 回调里没有挂起点，直接写库只能 runBlocking 占住一个 IO 线程。
            val persistQueue = Channel<List<OffsetIndexBlock>>(Channel.UNLIMITED)
            val persistJob = launch { for (pending in persistQueue) store.appendBlocks(key, pending) }
            try {
                indexChannel = UriChannels.open(context, uri)
                store.begin(key)
                var batch = ArrayList<OffsetIndexBlock>(PERSIST_BATCH_BLOCKS)
                val chapters = TxtIndexer.indexInto(indexChannel, charset, shared, bomLength = bom, chapterRules = rules) { chunkIndex, startByteOffset, endByteOffset ->
                    if (!isActive) throw CancellationException()
                    progress.value = if (fileLength > 0) {
                        (endByteOffset.toFloat() / fileLength).coerceIn(0f, 1f)
                    } else {
                        1f
                    }
                    // 字符起点必须一并落盘：代理对跨块时它不等于 chunkIndex * blockChars，
                    // 而该边界上不存在合法字节偏移，推算不出来。
                    batch += OffsetIndexBlock(
                        chunkIndex = chunkIndex,
                        byteOffset = startByteOffset,
                        charStart = shared.charStartOfBlock(chunkIndex),
                    )
                    if (batch.size >= PERSIST_BATCH_BLOCKS) {
                        persistQueue.trySend(batch)
                        batch = ArrayList(PERSIST_BATCH_BLOCKS)
                    }
                }
                if (batch.isNotEmpty()) persistQueue.trySend(batch)
                persistQueue.close()
                persistJob.join()
                store.complete(key, fileLength, contentHash, charset.name(), shared.totalChars)
                progress.value = 1f
                runCatching { onChaptersIndexed(bookId, chapters) }
                runCatching { onBookIndexed(bookId, shared.totalChars) }
            } catch (t: Throwable) {
                persistQueue.close()
                persistJob.cancel()
                shared.abort(t)
                if (t !is CancellationException) runCatching { store.invalidate(key) }
                throw t
            } finally {
                indexChannel?.close()
            }
        }
        return content
    }

    private suspend fun persistIndex(uri: Uri, index: TxtIndex, bookId: Long?) {
        val store = offsetIndexStore ?: return
        val (contentUri, effectiveBookId) = resolveContent(uri, bookId)
        if (effectiveBookId == null) return
        runCatching {
            UriChannels.open(context, contentUri).use { channel ->
                val fileLength = channel.size()
                store.saveValid(
                    key = effectiveBookId.toString(),
                    snapshot = index.offsetIndex.snapshot(),
                    fileLength = fileLength,
                    contentHash = contentHash(channel, fileLength),
                    charsetName = index.charset.name(),
                )
            }
            onBookIndexed(effectiveBookId, index.charCount)
        }
    }

    /** 诊断用：只说"读的是哪一份"，不记完整 URI（外部 URI 可能带着用户的文件名）。 */
    private fun describeContentUri(uri: Uri): String =
        if (uri.scheme == "file") "私有文件:${uri.lastPathSegment}" else "外部源:${uri.scheme}"

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

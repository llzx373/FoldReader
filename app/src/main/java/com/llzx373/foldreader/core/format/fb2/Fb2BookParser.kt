package com.llzx373.foldreader.core.format.fb2

import android.net.Uri
import android.util.Xml
import com.llzx373.foldreader.core.format.BookContent
import com.llzx373.foldreader.core.format.BookMeta
import com.llzx373.foldreader.core.format.BookParser
import com.llzx373.foldreader.core.format.Chapter
import com.llzx373.foldreader.core.format.ConvertedBookStore
import com.llzx373.foldreader.core.format.FlattenContent
import com.llzx373.foldreader.core.format.FlattenedBook
import com.llzx373.foldreader.core.format.FormatDetector
import com.llzx373.foldreader.core.format.epub.FlattenSink
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.channels.Channels
import java.nio.channels.SeekableByteChannel
import java.nio.charset.Charset
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser

/**
 * FB2 解析器：首开时经 [Fb2Flattener] 压平为 `convertedDir/<contentHash>.txt`
 * （UTF-8 纯文本，contentHash 为原书采样哈希，与导入去重一致），
 * 之后由 [openFlattenedContent]（TXT 管线）提供内容与偏移索引。
 * 章节取各级 `<section><title>` 拍平结果，压平完成时经 [onChaptersIndexed] 回填，
 * TXT 启发式章节扫描不参与 FB2。支持 `.fb2.zip` 变体（zip 内含单个 .fb2 条目）。
 *
 * Uri 壳方法只做 复制/哈希/委托；核心逻辑走 File 参数的内部方法以便 JVM 单测。
 */
class Fb2BookParser(
    convertedDir: File,
    private val openFlattenedContent: suspend (File) -> BookContent,
    private val openChannel: (Uri) -> SeekableByteChannel,
    private val displayNameOf: (Uri) -> String?,
    private val bookIdResolver: suspend (Uri) -> Long? = { null },
    private val onChaptersIndexed: suspend (bookId: Long, chapters: List<Chapter>) -> Unit = { _, _ -> },
    private val newParser: () -> XmlPullParser = { Xml.newPullParser() },
) : BookParser {

    private val store = ConvertedBookStore(convertedDir)
    private val flattener = Fb2Flattener(newParser)

    override suspend fun parseMeta(uri: Uri): BookMeta = withContext(Dispatchers.IO) {
        openChannel(uri).use { channel ->
            val byteSize = channel.size()
            val meta = withFb2Input(channel) { input -> flattener.readMeta(input) }
            BookMeta(
                title = meta.title?.takeIf { it.isNotBlank() } ?: fallbackTitle(uri),
                author = meta.author,
                encoding = Charsets.UTF_8.name(),
                byteSize = byteSize,
            )
        }
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

    /** 压平（命中缓存则零成本）；本次确实新压平时顺带把章节回填进章节表。 */
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
                store.cached(hash)
                    ?: store.store(hash) { out -> FlattenContent(flattenChannelTo(channel, out)) }
            }
        }
    }

    internal fun ensureFlattenedFile(fb2: File): FlattenedBook {
        val hash = store.contentHash(fb2)
        store.cached(hash)?.let { return it }
        return store.store(hash) { out -> FlattenContent(flattenTo(fb2, out)) }
    }

    internal suspend fun openContentFile(fb2: File): BookContent {
        val flattened = ensureFlattenedFile(fb2)
        return openFlattenedContent(flattened.file)
    }

    internal fun parseChaptersFile(fb2: File): List<Chapter> = ensureFlattenedFile(fb2).chapters

    internal fun readMetaFile(fb2: File): Fb2Meta =
        withFb2Input(fb2) { input -> flattener.readMeta(input) }

    private fun flattenTo(source: File, out: File): List<Chapter> =
        withFb2Input(source) { input -> flattenStream(input, out) }

    private fun flattenChannelTo(channel: SeekableByteChannel, out: File): List<Chapter> =
        withFb2Input(channel) { input -> flattenStream(input, out) }

    private fun flattenStream(input: InputStream, out: File): List<Chapter> =
        out.bufferedWriter(Charsets.UTF_8).use { writer ->
            flattener.flatten(input, FlattenSink(writer))
        }

    /** 裸 FB2 直接流式读；zip 头（.fb2.zip）则取 zip 内唯一 .fb2 条目。 */
    private fun <T> withFb2Input(channel: SeekableByteChannel, block: (InputStream) -> T): T {
        val head = store.readAt(channel, 0, ZIP_HEAD_BYTES)
        if (FormatDetector.isZip(head)) {
            val tmp = store.newTempFile(".zip")
            try {
                store.copyChannel(channel, tmp)
                return withFb2ZipEntry(tmp, block)
            } finally {
                tmp.delete()
            }
        }
        channel.position(0)
        return block(Channels.newInputStream(channel))
    }

    private fun <T> withFb2Input(file: File, block: (InputStream) -> T): T {
        val head = file.inputStream().use { it.readNBytes(ZIP_HEAD_BYTES) }
        if (FormatDetector.isZip(head)) return withFb2ZipEntry(file, block)
        return file.inputStream().use(block)
    }

    private fun <T> withFb2ZipEntry(zipFile: File, block: (InputStream) -> T): T {
        ZipFile(zipFile).use { zip ->
            val entries = zip.entries().toList()
                .filter { !it.isDirectory && it.name.lowercase().endsWith(".fb2") }
            if (entries.size != 1) {
                throw IOException("无法识别的 .fb2.zip（应包含单个 .fb2 条目，实际 ${entries.size} 个）")
            }
            return zip.getInputStream(entries[0]).use(block)
        }
    }

    private fun fallbackTitle(uri: Uri): String =
        displayNameOf(uri)
            ?.removeSuffix(".zip")
            ?.substringBeforeLast('.')
            ?.takeIf { it.isNotBlank() }
            ?: "未知书名"

    private companion object {
        const val ZIP_HEAD_BYTES = 4
    }
}

package com.llzx373.foldreader.feature.importer

import com.llzx373.foldreader.core.data.db.BookFormat
import com.llzx373.foldreader.core.data.repository.BookshelfRepository
import com.llzx373.foldreader.core.format.ContentHasher
import com.llzx373.foldreader.core.format.EncodingDetection
import com.llzx373.foldreader.core.format.EncodingDetector
import com.llzx373.foldreader.core.format.OffsetIndexStore
import com.llzx373.foldreader.core.format.clean.CleanProfile
import com.llzx373.foldreader.core.format.clean.CleanReport
import com.llzx373.foldreader.core.format.clean.NovelCleaner
import com.llzx373.foldreader.core.format.txt.UriChannels
import com.llzx373.foldreader.core.reader.PageDiskCache
import java.io.File
import java.io.FilterInputStream
import java.io.RandomAccessFile
import java.nio.channels.Channels
import java.nio.channels.SeekableByteChannel
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * 对**已导入**的书重新跑一遍智能清理，产出新的清洗副本。
 *
 * 与导入期清洗的两点关键区别：
 * 1. 读的是**原始源文件**（`book.fileUri`），不是上一版副本——否则就是「洗副本」，
 *    规则改进后再也回不到原文上重洗。
 * 2. 内容一换，所有按字符偏移存下来的东西都会漂移：偏移索引、页边界缓存必须**显式作废**，
 *    章节要重扫。进度与书签/标注的锚点会按比例错位，这一点必须让用户事先知道。
 *
 * 刻意**不改 `contentHash`**：它参与备份匹配（`BackupCodec`）与封面/压平缓存命名，
 * 是这本书的稳定身份；内容变了靠显式作废缓存来兜，比改身份安全。
 */
class RecleanBookUseCase(
    private val bookshelfRepository: BookshelfRepository,
    private val cleanedDir: File,
    private val openChannel: (String) -> SeekableByteChannel,
    private val offsetIndexStore: OffsetIndexStore,
    private val pageDiskCache: PageDiskCache,
    private val traditionalMap: () -> Map<Char, Char> = { emptyMap() },
) {

    sealed interface Outcome {
        /** [changed] 为 false 表示按当前档位洗出来与现状一致，没有替换副本。 */
        data class Done(val report: CleanReport, val changed: Boolean) : Outcome
        data class Failure(val message: String) : Outcome
    }

    /**
     * 用 [profile] 重新清洗这本书。
     *
     * **每次都从 [com.llzx373.foldreader.core.data.db.BookEntity.fileUri]（原始源文件）重洗**，
     * 而不是在上一版副本上再洗——所以可以反复换档位/换规则，结果永远只取决于「原文 + 本次配方」，
     * 不会累积。传 [CleanProfile.NONE]（没有任何规则启用）等价于**撤销清理**：
     * 删掉副本、恢复原始编码，阅读器回到直接读原文件。
     */
    suspend fun reclean(
        bookId: Long,
        profile: CleanProfile,
        onProgress: (Float) -> Unit = {},
    ): Outcome = withContext(Dispatchers.IO) {
        val book = bookshelfRepository.getBook(bookId) ?: return@withContext Outcome.Failure("书籍不存在")
        if (book.format != BookFormat.TXT) {
            return@withContext Outcome.Failure("智能整理目前只支持 TXT 书籍")
        }
        if (profile.isNoop) return@withContext revertToSource(book)

        runCatching {
            cleanedDir.mkdirs()
            val tmp = File(cleanedDir, ".tmp-${UUID.randomUUID()}.txt")
            try {
                val report = writeCopy(book.fileUri, profile, tmp, onProgress)
                val hash = hashOf(tmp)
                val previous = book.cleanedFilePath
                // 与**当前副本的实际内容**比。不能拿 contentHash 比：它是这本书的稳定身份
                // （刻意不随内容改写），洗出来的内容哈希永远对不上它，那样「无改动」分支就是死代码。
                val previousHash = previous?.let { runCatching { hashOf(File(it)) }.getOrNull() }
                if (hash == previousHash) {
                    return@withContext Outcome.Done(report, changed = false)
                }
                val target = File(cleanedDir, "$hash.txt")
                if (target.exists()) tmp.delete() else if (!tmp.renameTo(target)) {
                    return@withContext Outcome.Failure("清洗副本写入失败：${target.absolutePath}")
                }
                // 内容已换：字数归零等下次打开重算，编码一定是 UTF-8（副本恒为 UTF-8）
                bookshelfRepository.updateConvertedFile(bookId, target.absolutePath, 0)
                bookshelfRepository.updateEncoding(bookId, Charsets.UTF_8.name())
                invalidateCaches(bookId)
                deleteOrphan(previous, target.absolutePath, bookId)
                Outcome.Done(report, changed = true)
            } finally {
                tmp.delete()
            }
        }.getOrElse { Outcome.Failure(it.message ?: "智能整理失败") }
    }

    /**
     * 撤销清理：删掉副本、把编码恢复回原始文件的，阅读器重新直接读源文件。
     *
     * 编码必须一并恢复——副本恒为 UTF-8，直接沿用会让原文件被按 UTF-8 解码（多半乱码）。
     */
    private suspend fun revertToSource(
        book: com.llzx373.foldreader.core.data.db.BookEntity,
    ): Outcome {
        val previous = book.cleanedFilePath ?: return Outcome.Done(CleanReport(), changed = false)
        val encoding = detectCharset(book.fileUri) ?: book.encoding
        bookshelfRepository.updateConvertedFile(book.id, null, 0)
        bookshelfRepository.updateEncoding(book.id, encoding)
        invalidateCaches(book.id)
        deleteOrphan(previous, newPath = null, bookId = book.id)
        return Outcome.Done(CleanReport(), changed = true)
    }

    private fun detectCharset(uriKey: String): String? = runCatching {
        openChannel(uriKey).use { channel ->
            EncodingDetector.detect(UriChannels.readHead(channel, EncodingDetector.SAMPLE_SIZE))
                .charset.name()
        }
    }.getOrNull()

    /**
     * 内容变了，按字符偏移派生的缓存全部作废：
     * 偏移索引（按 bookId）与该书的全部页边界文件（文件名含版式指纹，一本多份）。
     */
    private suspend fun invalidateCaches(bookId: Long) {
        offsetIndexStore.invalidate(bookId.toString())
        pageDiskCache.deleteForBook(bookId)
    }

    /** 旧副本只在没有别的书还引用它时才删——同一份清洗产物理论上可能被两本书共享。 */
    private suspend fun deleteOrphan(previous: String?, newPath: String?, bookId: Long) {
        if (previous == null || previous == newPath) return
        val stillUsed = runCatching {
            bookshelfRepository.observeBookshelf().first()
                .any { it.id != bookId && it.cleanedFilePath == previous }
        }.getOrDefault(true)
        if (!stillUsed) File(previous).delete()
    }

    private fun writeCopy(
        uriKey: String,
        profile: CleanProfile,
        target: File,
        onProgress: (Float) -> Unit,
    ): CleanReport {
        openChannel(uriKey).use { channel ->
            val head = UriChannels.readHead(channel, EncodingDetector.SAMPLE_SIZE)
            val detection: EncodingDetection = EncodingDetector.detect(head)
            val bom = EncodingDetector.bomLengthOf(head)
            val total = channel.size()
            channel.position(bom.toLong())
            return target.outputStream().buffered().writer(Charsets.UTF_8).buffered().use { writer ->
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
            }.also { onProgress(1f) }
        }
    }

    private fun hashOf(file: File): String =
        RandomAccessFile(file, "r").use { raf ->
            ContentHasher.hash(raf.length()) { offset, length ->
                raf.seek(offset)
                val buffer = ByteArray(length)
                raf.readFully(buffer)
                buffer
            }
        }
}

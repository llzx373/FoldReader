package com.llzx373.foldreader.feature.importer

import android.net.Uri
import com.llzx373.foldreader.core.data.db.BookEntity
import com.llzx373.foldreader.core.data.db.BookFormat
import com.llzx373.foldreader.core.data.db.BookSource
import com.llzx373.foldreader.core.data.repository.BookshelfRepository
import com.llzx373.foldreader.core.format.ContentHasher
import com.llzx373.foldreader.core.format.txt.UriChannels
import java.io.File
import java.nio.channels.SeekableByteChannel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * PDF 登记：复制源文件进私有目录 + 哈希去重 + 写库，不解析内容。
 *
 * 源文件必须复制（与 TXT/EPUB/FB2 一致）：外部「打开方式」给的 `content://` 是临时授权，
 * 任务结束/重启就失效，只登记 URI 的话下次打开会读不到文件。
 *
 * P3 阶段刻意**不解析 PDF**（页数、元数据、目录、封面都不碰）：
 * - 页数由阅读器首次打开时回填（`openPagedSource` 一开就知道），书架先显示「待解析」；
 * - 元数据/目录/封面由后台预热用 PdfBox 补齐（P4）。
 *
 * 这样导入本身只做「复制 + 哈希 + 去重 + 写库」，批量导入几十个 PDF 不会卡在解析上。
 * 加密 PDF 也能照常入架——打开时才需要密码。
 */
class PdfImportUseCase(
    private val bookshelfRepository: BookshelfRepository,
    private val openChannel: (String) -> SeekableByteChannel,
    private val displayNameOf: (String) -> String?,
    /** 源文件副本目录（filesDir/source），与 TXT/EPUB/FB2 共用。 */
    private val sourceDir: File,
    /** 导入成功后的后台预热入队（非阻塞，失败不影响导入结果）。 */
    private val enqueuePrewarm: (bookId: Long, uriKey: String, format: BookFormat) -> Unit =
        { _, _, _ -> },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    sealed interface Outcome {
        data class Registered(val bookId: Long, val title: String) : Outcome
        data class Duplicate(val bookId: Long, val title: String, val sameUri: Boolean) : Outcome
        data class Failure(val message: String?) : Outcome
    }

    suspend fun register(
        uri: Uri,
        source: BookSource,
        groupName: String? = null,
    ): Outcome = withContext(ioDispatcher) {
        runCatching { doRegister(uri, source, groupName) }
            .getOrElse { Outcome.Failure(it.message) }
    }

    private suspend fun doRegister(
        uri: Uri,
        source: BookSource,
        groupName: String?,
    ): Outcome {
        val uriKey = uri.toString()
        bookshelfRepository.findByFileUri(uriKey)
            ?.let { return Outcome.Duplicate(it.id, it.title, sameUri = true) }

        return openChannel(uriKey).use { channel ->
            val contentHash = ContentHasher.hash(channel.size()) { offset, length ->
                UriChannels.readAt(channel, offset, length)
            }
            bookshelfRepository.findByContentHash(contentHash)
                ?.let { return Outcome.Duplicate(it.id, it.title, sameUri = false) }

            // 之后的回填/预热/阅读都读这份副本：导入时授权还在，复制完就不再依赖外部 URI
            val contentFileUri = copySourceToPrivateDir(sourceDir, channel, contentHash, BookFormat.PDF)
            val title = displayNameOf(uriKey)
                ?.substringBeforeLast('.')
                ?.takeIf { it.isNotBlank() }
                ?: DEFAULT_TITLE
            val bookId = bookshelfRepository.upsertBook(
                BookEntity(
                    title = title,
                    author = null,
                    fileUri = contentFileUri,
                    contentHash = contentHash,
                    format = BookFormat.PDF,
                    totalChars = 0,
                    encoding = Charsets.UTF_8.name(),
                    importedAt = System.currentTimeMillis(),
                    lastReadAt = null,
                    groupName = groupName?.trim()?.takeIf { it.isNotEmpty() },
                    cleanedFilePath = null,
                    source = source,
                    // 页数/元数据/封面都留给"打开时回填 + 后台预热"，导入保持轻
                    comicPageCount = null,
                    coverPath = null,
                ),
            )
            runCatching { enqueuePrewarm(bookId, contentFileUri, BookFormat.PDF) }
            Outcome.Registered(bookId, title)
        }
    }

    companion object {
        const val DEFAULT_TITLE = "未命名文档"
    }
}

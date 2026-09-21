package com.llzx373.foldreader.feature.importer

import android.net.Uri
import com.llzx373.foldreader.core.comic.ComicArchiveFactory
import com.llzx373.foldreader.core.comic.ComicContainer
import com.llzx373.foldreader.core.comic.ComicCoverWriter
import com.llzx373.foldreader.core.comic.ComicExtractionStore
import com.llzx373.foldreader.core.data.db.BookEntity
import com.llzx373.foldreader.core.data.db.BookFormat
import com.llzx373.foldreader.core.data.db.BookSource
import com.llzx373.foldreader.core.data.repository.BookshelfRepository
import com.llzx373.foldreader.core.format.ContentHasher
import com.llzx373.foldreader.core.format.txt.UriChannels
import java.io.File
import java.io.IOException
import java.nio.channels.SeekableByteChannel
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 漫画登记：把「源位置 + 容器 + 页数 + 封面」写进 books 表。
 *
 * 容器漫画（zip/rar/tar/7z）的源位置按授权分两类：
 * - 书架 SAF 导入的有**持久授权**，直接引用外部 URI——「前后卷切换」要靠原始位置发现
 *   同目录里还没导入的卷，复制进来反而会弄丢这个能力；
 * - 外部「打开方式」送进来的只有**临时授权**，登记时把源文件复制进私有目录，
 *   否则授权失效后这本书就打不开。目录漫画没有单一文件可复制，依赖 SAF 树授权的持久化。
 *
 * 页数按容器分档处理：
 * - zip 读中央目录、目录漫画列目录，导入即知；
 * - rar/tar/7z 必须先解压一遍才知道，导入时留 null（书架显示「待解析」），
 *   解压成本挪到后台预热——和 EPUB/FB2 的首开压平是同一套思路。
 */
class ComicImportUseCase(
    private val bookshelfRepository: BookshelfRepository,
    private val archiveFactory: ComicArchiveFactory,
    private val extractionStore: ComicExtractionStore,
    private val openChannel: (String) -> SeekableByteChannel,
    private val displayNameOf: (String) -> String?,
    /** 源文件副本目录（filesDir/source），与 TXT/EPUB/FB2/PDF 共用。 */
    private val sourceDir: File,
    /** 该 URI 是否持有持久化读授权（SAF 导入）；false 时容器漫画登记会复制源文件。 */
    private val hasPersistedRead: (String) -> Boolean = { false },
    /** 封面目录（filesDir/covers）；null 时跳过封面提取。 */
    private val coversDir: File? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    sealed interface Outcome {
        /**
         * [needsPreparation] = 导入时还不知道页数（rar/tar/7z 必须先解压），
         * 调用方据此决定要不要入队后台预热。
         */
        data class Registered(
            val bookId: Long,
            val title: String,
            val needsPreparation: Boolean = false,
        ) : Outcome

        data class Duplicate(val bookId: Long, val title: String, val sameUri: Boolean) : Outcome
        data class Failure(val message: String?) : Outcome
    }

    suspend fun register(
        uri: Uri,
        container: ComicContainer,
        source: BookSource,
        groupName: String? = null,
        onProgress: (Float) -> Unit = {},
    ): Outcome = withContext(ioDispatcher) {
        runCatching { doRegister(uri, container, source, groupName, onProgress) }
            .getOrElse { Outcome.Failure(it.message) }
    }

    private suspend fun doRegister(
        uri: Uri,
        container: ComicContainer,
        source: BookSource,
        groupName: String?,
        onProgress: (Float) -> Unit,
    ): Outcome {
        val uriKey = uri.toString()
        bookshelfRepository.findByFileUri(uriKey)
            ?.let { return Outcome.Duplicate(it.id, it.title, sameUri = true) }

        // 目录漫画没有单一文件可采样，用 Uri 派生哈希即可：它只是给 books.contentHash 一个稳定值，
        // 目录本身的去重由上面的 findByFileUri 负责（按内容列表做哈希会把不同卷的同名页表误判为同一本）
        val folderPaths = if (container == ComicContainer.FOLDER) {
            archiveFactory.scanFolderPagePaths(uri)
        } else {
            null
        }
        if (folderPaths != null && folderPaths.isEmpty()) {
            return Outcome.Failure("目录内没有可显示的图片")
        }
        val contentHash = folderPaths?.let { hashOfFolder(uriKey) }
            ?: openChannel(uriKey).use { hashOfChannel(it) }
        bookshelfRepository.findByContentHash(contentHash)
            ?.let { return Outcome.Duplicate(it.id, it.title, sameUri = false) }

        val displayName = displayNameOf(uriKey)
        val pageCount = when (container) {
            ComicContainer.FOLDER -> folderPaths!!.size
            // zip 读中央目录很便宜；读不出来说明它根本不是漫画容器（损坏 / 里面没有图片），
            // 这种要明确失败，不能建出一本页数未知的空书
            ComicContainer.ZIP -> try {
                archiveFactory.pageCount(uri, container, contentHash)
            } catch (t: Throwable) {
                return Outcome.Failure(t.message ?: "无法读取压缩包")
            }
            ComicContainer.RAR, ComicContainer.TAR, ComicContainer.SEVEN_ZIP -> null
        }
        val title = displayName
            ?.substringBeforeLast('.')
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_TITLE
        val coverPath = extractCoverPath(uri, container, contentHash)
        // 容器漫画在没有持久授权时（外部「打开方式」的临时 content://）把源文件复制进私有目录，
        // 否则授权失效后这本书就打不开；SAF 导入的有持久授权，直接引用以保住「同目录找卷」。
        // 复制放在页数/封面之后，容器读不出来时不留孤儿副本。目录漫画没有单一文件可复制。
        val contentFileUri = if (container == ComicContainer.FOLDER || hasPersistedRead(uriKey)) {
            uriKey
        } else {
            openChannel(uriKey).use { channel ->
                copySourceToPrivateDir(
                    sourceDir = sourceDir,
                    channel = channel,
                    sourceHash = contentHash,
                    format = BookFormat.COMIC,
                    extension = displayName?.substringAfterLast('.', ""),
                )
            }
        }
        onProgress(1f)

        val bookId = bookshelfRepository.upsertBook(
            BookEntity(
                title = title,
                author = null,
                fileUri = contentFileUri,
                contentHash = contentHash,
                format = BookFormat.COMIC,
                totalChars = 0,
                encoding = Charsets.UTF_8.name(),
                importedAt = System.currentTimeMillis(),
                lastReadAt = null,
                groupName = groupName?.trim()?.takeIf { it.isNotEmpty() },
                cleanedFilePath = null,
                source = source,
                coverPath = coverPath,
                comicContainer = container,
                comicPageCount = pageCount,
            ),
        )
        return Outcome.Registered(bookId, title, needsPreparation = pageCount == null)
    }

    /**
     * 后台预热：把「必须先解压才知道内容」的容器解压到缓存、回填页数与封面。
     *
     * 与 EPUB/FB2 的导入后压平同一套思路——把首次打开要等的成本挪到导入后的空闲时间。
     * 失败静默：预热只是加速，真打开时还会照常重来一遍。
     */
    suspend fun prepare(bookId: Long) {
        val book = bookshelfRepository.getBook(bookId) ?: return
        val container = book.comicContainer ?: return
        if (book.format != BookFormat.COMIC) return
        val uri = Uri.parse(book.fileUri)
        val archive = archiveFactory.open(uri, container, book.contentHash)
        val pageCount = archive.use { it.pages.size }
        bookshelfRepository.updateComicPageCount(bookId, pageCount)
        if (book.coverPath == null) {
            val dir = coversDir ?: return
            val cover = archive.use { opened ->
                val first = opened.pages.firstOrNull() ?: return@use null
                ComicCoverWriter.encode(opened.readPage(first.index))
            }
            if (cover != null) {
                bookshelfRepository.updateCoverPath(bookId, writeCoverFile(dir, book.contentHash, cover))
            }
        }
    }

    /**
     * 「复制到本地」：把内容解包成逐页文件存进应用私有目录。容器漫画的源文件在登记时已经
     * 复制进来了，这一步主要是给目录漫画一个脱离 SAF 授权的持久副本，同时让逐页读取不再
     * 依赖容器结构。返回页目录路径。
     */
    suspend fun copyLocal(bookId: Long): String {
        val book = bookshelfRepository.getBook(bookId) ?: throw IOException("书籍不存在")
        val container = book.comicContainer ?: throw IOException("缺少漫画容器信息")
        val pages = archiveFactory.copyLocal(Uri.parse(book.fileUri), container, book.contentHash)
        val pagesDir = pages.firstOrNull()?.parentFile?.absolutePath
        bookshelfRepository.updateComicLocalPath(bookId, pagesDir)
        bookshelfRepository.updateComicPageCount(bookId, pages.size)
        return pagesDir.orEmpty()
    }

    /** 删除解包出来的本地页副本，回到读登记的源（私有副本或 SAF 目录）。 */
    suspend fun removeLocalCopy(bookId: Long) {
        val book = bookshelfRepository.getBook(bookId) ?: return
        extractionStore.deleteLocalCopy(book.contentHash)
        bookshelfRepository.updateComicLocalPath(bookId, null)
    }

    /**
     * 封面只对「打开不需要解压」的容器做：rar/tar/7z 为了封面触发一次整本解压不值得，
     * 交给后台预热；用户第一次打开时缓存已经就位。
     */
    private suspend fun extractCoverPath(
        uri: Uri,
        container: ComicContainer,
        contentHash: String,
    ): String? {
        val dir = coversDir ?: return null
        if (container != ComicContainer.ZIP && container != ComicContainer.FOLDER) return null
        return runCatching {
            archiveFactory.open(uri, container, contentHash).use { archive ->
                val first = archive.pages.firstOrNull() ?: return null
                val cover = ComicCoverWriter.encode(archive.readPage(first.index)) ?: return null
                writeCoverFile(dir, contentHash, cover)
            }
        }.getOrNull()
    }

    private fun hashOfChannel(channel: SeekableByteChannel): String =
        ContentHasher.hash(channel.size()) { offset, length ->
            UriChannels.readAt(channel, offset, length)
        }

    private fun hashOfFolder(uriKey: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("comic-folder:".toByteArray(Charsets.UTF_8))
        digest.update(uriKey.toByteArray(Charsets.UTF_8))
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val DEFAULT_TITLE = "未命名漫画"
    }
}

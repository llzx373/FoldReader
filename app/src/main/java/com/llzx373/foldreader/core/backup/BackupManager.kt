package com.llzx373.foldreader.core.backup

import android.content.Context
import android.net.Uri
import com.llzx373.foldreader.core.comic.ComicContainer
import com.llzx373.foldreader.core.data.db.BookEntity
import com.llzx373.foldreader.core.data.db.BookFormat
import com.llzx373.foldreader.core.data.db.BookPrefsDao
import com.llzx373.foldreader.core.data.db.ReadingSessionDao
import com.llzx373.foldreader.core.data.repository.BookshelfRepository
import com.llzx373.foldreader.core.data.settings.SettingsRepository
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 书架数据 + 阅读偏好 + 书籍文件本体的本地备份（v11 起为 zip：backup.json + files/ + covers/，
 * 流式读写，整包不进内存）。导入按头部 PK 魔数嗅探：zip 走解压 + 新建书籍链路，
 * 旧版纯 JSON 备份行为不变——按 contentHash 匹配书籍，未匹配的列入"文件缺失"清单。
 */
class BackupManager(
    private val context: Context,
    private val bookshelfRepository: BookshelfRepository,
    settingsRepository: SettingsRepository,
    bookPrefsDao: BookPrefsDao,
    readingSessionDao: ReadingSessionDao,
    glossaryTermDao: com.llzx373.foldreader.core.data.db.GlossaryTermDao? = null,
    wordEntryDao: com.llzx373.foldreader.core.data.db.WordEntryDao? = null,
    chapterSummaryDao: com.llzx373.foldreader.core.data.db.ChapterSummaryDao? = null,
    bookOutlineDao: com.llzx373.foldreader.core.data.db.BookOutlineDao? = null,
) {

    private val codec = BackupCodec(
        bookshelfRepository = bookshelfRepository,
        settingsRepository = settingsRepository,
        bookPrefsDao = bookPrefsDao,
        readingSessionDao = readingSessionDao,
        glossaryTermDao = glossaryTermDao,
        wordEntryDao = wordEntryDao,
        chapterSummaryDao = chapterSummaryDao,
        bookOutlineDao = bookOutlineDao,
    )

    data class ImportResult(
        val restoredBooks: Int,
        val missingBookTitles: List<String>,
        val restoredBookmarks: Int,
        val restoredAnnotations: Int,
        val restoredSessions: Int = 0,
        val restoredBookPrefs: Int = 0,
        val restoredGlossary: Int = 0,
        val restoredVocabulary: Int = 0,
        val restoredSummaries: Int = 0,
        val restoredOutlines: Int = 0,
        /** v11 zip 备份：本机没有、靠包内书文件新建入架的本数（已含在 [restoredBooks] 里）。 */
        val createdBooks: Int = 0,
    )

    /** 导出结果；[skippedBookTitles] 是文件本体没能打进包里的书（SAF 授权失效/目录漫画等）。 */
    data class ExportResult(val skippedBookTitles: List<String> = emptyList())

    /** 恢复前预览（M25）：只解析元信息不落库；非备份文件/版本不认时抛异常。 */
    data class BackupPreview(
        val version: Int,
        val bookCount: Int,
        val exportedAt: Long,
    )

    /** 导出 zip 到 SAF 目标（设置页「导出备份」与自动备份共用）。 */
    suspend fun exportTo(uri: Uri): ExportResult = withContext(Dispatchers.IO) {
        val stream = context.contentResolver.openOutputStream(uri)
            ?: error("无法写入备份文件")
        stream.use { exportZipTo(it) }
    }

    /** 导出 zip 到本地临时文件（WebDAV 上传用）。 */
    suspend fun exportToFile(file: File): ExportResult = withContext(Dispatchers.IO) {
        file.outputStream().use { exportZipTo(it) }
    }

    suspend fun importFrom(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        val stream = context.contentResolver.openInputStream(uri)
            ?: error("无法读取备份文件")
        stream.use { importFromStream(it) }
    }

    /** 从本地临时文件导入（WebDAV 下载恢复用）。 */
    suspend fun importFromFile(file: File): ImportResult = withContext(Dispatchers.IO) {
        file.inputStream().use { importFromStream(it) }
    }

    /** 预览本地备份文件（WebDAV 恢复前确认用）：zip 只读 backup.json 条目，不解压。 */
    fun previewFile(file: File): BackupPreview {
        file.inputStream().use { raw ->
            val (stream, isZip) = raw.withSniffedHeader()
            return if (isZip) {
                preview(BackupArchive.readManifestText(stream))
            } else {
                preview(stream.bufferedReader(Charsets.UTF_8).readText())
            }
        }
    }

    private suspend fun exportZipTo(output: OutputStream): ExportResult {
        val books = bookshelfRepository.observeBookshelf().first()
        val skipped = mutableListOf<String>()
        val entries = mutableListOf<BackupArchive.PendingEntry>()
        val refs = HashMap<Long, BookArchiveRefs>()

        books.forEach { book ->
            // 目录漫画（逐页 SAF 文档）打包成本高且授权易失效——跳过文件，数据照常备份
            val folderComic =
                book.format == BookFormat.COMIC && book.comicContainer == ComicContainer.FOLDER
            var fileEntry: String? = null
            if (folderComic) {
                skipped += book.title
            } else {
                val entryName = BackupArchive.FILES_PREFIX + book.contentHash + "." + extensionOf(book)
                val opener = fileOpener(book.fileUri)
                val probe = opener()
                if (probe == null) {
                    skipped += book.title
                } else {
                    probe.close()
                    entries += BackupArchive.PendingEntry(entryName, opener)
                    fileEntry = entryName
                }
            }

            var coverEntry: String? = null
            val coverFile = book.coverPath?.takeIf { it.isNotBlank() }?.let(::File)
            if (coverFile != null && coverFile.isFile) {
                val ext = coverFile.extension.ifBlank { "jpg" }
                val name = BackupArchive.COVERS_PREFIX + book.contentHash + "." + ext
                entries += BackupArchive.PendingEntry(name) {
                    runCatching { coverFile.inputStream() }.getOrNull()
                }
                coverEntry = name
            }

            if (fileEntry != null || coverEntry != null) {
                refs[book.id] = BookArchiveRefs(file = fileEntry, cover = coverEntry)
            }
        }

        val json = codec.exportJson { refs[it.id] }
        BackupArchive.writeZip(output, json, entries)
        return ExportResult(skippedBookTitles = skipped)
    }

    /** file:// 直接开文件；content://（外部漫画容器）走 contentResolver，读不到返回 null。 */
    private fun fileOpener(fileUri: String): () -> InputStream? =
        if (fileUri.startsWith("file://")) {
            val file = File(fileUri.removePrefix("file://"))
            ({ file.takeIf { it.isFile }?.inputStream() })
        } else {
            ({ runCatching { context.contentResolver.openInputStream(Uri.parse(fileUri)) }.getOrNull() })
        }

    private suspend fun importFromStream(raw: InputStream): ImportResult {
        val (stream, isZip) = raw.withSniffedHeader()
        return if (isZip) {
            importZip(stream)
        } else {
            codec.importJson(stream.bufferedReader(Charsets.UTF_8).readText())
        }
    }

    private suspend fun importZip(stream: InputStream): ImportResult {
        val extracted = BackupArchive.readZip(
            stream,
            filesDir = File(context.filesDir, "source"),
            coversDir = File(context.filesDir, "covers"),
        )
        return codec.importJson(extracted.manifestText) { bookJson ->
            val file = bookJson.optStringOrNull("archiveFile")?.let(extracted.files::get)
            if (file == null) {
                null
            } else {
                ResolvedBookFile(
                    fileUri = "file://${file.absolutePath}",
                    coverPath = bookJson.optStringOrNull("archiveCover")
                        ?.let(extracted.covers::get)?.absolutePath,
                )
            }
        }
    }

    /** 读前两个字节嗅探格式（PK = zip），并把读掉的字节推回流里。 */
    private fun InputStream.withSniffedHeader(): Pair<InputStream, Boolean> {
        val stream = PushbackInputStream(this.buffered(), 2)
        val first = stream.read()
        val second = stream.read()
        when {
            first < 0 -> Unit
            second < 0 -> stream.unread(first)
            else -> stream.unread(byteArrayOf(first.toByte(), second.toByte()))
        }
        return stream to BackupArchive.isZipHeader(first, second)
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

    companion object {
        /**
         * v12：阅读进度新增 `comicPage` / `translationAnchor` / `charsReadTotal`（页式锚点、
         * 译文锚点、累计已读字符），导入侧改为按字段合并——旧备份缺的字段保留本机现值，
         * 不再整行 REPLACE。
         * v11：备份容器从纯 JSON 改为 zip（backup.json + files/ 书文件本体 + covers/ 封面）；
         * 书记录新增 `archiveFile`/`archiveCover` 与漫画的 `comicContainer`/`comicPageCount`，
         * 导入侧对旧版纯 JSON 备份保持兼容。
         * v10：书籍新增 `hidden`（M34 隐私锁：指定书籍隐藏状态随备份走，恢复后仍隐藏）。
         * v9：新增章节摘要 `summaries` 与全书大纲 `outlines` 段（M29，与术语表同口径：
         * 按 contentHash 重映射来源书，只备份 done 摘要）。
         * v8：新增生词本 `vocabulary` 段（M28：词条/释义/上下文例句/位置，按 contentHash 重映射来源书）。
         * v7：书籍新增 `genreTag` / `metaSource`（M17 题材标签与「AI 生成」/用户锁定标记）。
         * v6：新增术语表 `glossary` 段（全局/系列/单书全表，单书行附 contentHash 供换机重映射）。
         * v5：书签/标注增加页式锚点（页序号 + 归一化页内坐标），中间点击区动作入备份。
         * 导入侧对老版本仍然兼容——新字段缺失即按 null / 默认值处理。
         */
        const val BACKUP_VERSION = 12

        /** 解析备份元信息供恢复前预览（M25）；不碰数据库，纯函数可测。 */
        fun preview(text: String): BackupPreview {
            val root = JSONObject(text)
            val version = root.optInt("version", 0)
            require(version >= 1) { "备份版本不支持" }
            return BackupPreview(
                version = version,
                bookCount = root.optJSONArray("books")?.length() ?: 0,
                exportedAt = root.optLong("exportedAt"),
            )
        }
    }
}

/** 书文件扩展名：优先取 fileUri 的后缀（<内容哈希>.<原扩展名>），取不到按格式给默认。 */
internal fun extensionOf(book: BookEntity): String {
    val fromUri = book.fileUri.substringAfterLast('.', "").lowercase()
    if (fromUri.length in 1..5 && fromUri.all { it.isLetterOrDigit() }) return fromUri
    return when (book.format) {
        BookFormat.TXT -> "txt"
        BookFormat.EPUB -> "epub"
        BookFormat.FB2 -> "fb2"
        BookFormat.MARKDOWN -> "md"
        else -> book.format.name.lowercase()
    }
}

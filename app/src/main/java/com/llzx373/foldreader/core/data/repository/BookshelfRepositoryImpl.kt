package com.llzx373.foldreader.core.data.repository

import com.llzx373.foldreader.core.data.db.AnnotationDao
import com.llzx373.foldreader.core.data.db.AnnotationEntity
import com.llzx373.foldreader.core.data.db.BookDao
import com.llzx373.foldreader.core.data.db.BookEntity
import com.llzx373.foldreader.core.data.db.BookWithProgress
import com.llzx373.foldreader.core.data.db.BookmarkDao
import com.llzx373.foldreader.core.data.db.BookmarkEntity
import com.llzx373.foldreader.core.data.db.ChapterDao
import com.llzx373.foldreader.core.data.db.ChapterEntity
import com.llzx373.foldreader.core.data.db.PersonAppearanceDao
import com.llzx373.foldreader.core.data.db.PersonAppearanceEntity
import com.llzx373.foldreader.core.data.db.ReadingProgressDao
import com.llzx373.foldreader.core.data.db.ReadingProgressEntity
import com.llzx373.foldreader.core.data.db.ReadingSessionDao
import com.llzx373.foldreader.core.data.db.ReadingSessionEntity
import com.llzx373.foldreader.core.format.Chapter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class BookshelfRepositoryImpl(
    private val bookDao: BookDao,
    private val progressDao: ReadingProgressDao,
    private val chapterDao: ChapterDao,
    private val bookmarkDao: BookmarkDao,
    private val annotationDao: AnnotationDao,
    private val sessionDao: ReadingSessionDao,
    private val personAppearanceDao: PersonAppearanceDao? = null,
    /** 非 TXT 压平缓存目录；删除书籍时按 contentHash 一并清理（TXT 无此文件，删除为 no-op）。 */
    private val convertedDir: java.io.File? = null,
    /** 封面目录；删除书籍时按 coverPath 一并清理。 */
    private val coversDir: java.io.File? = null,
    /** 页边界缓存；删除书籍时按 bookId 清理（改一次版式就多一份文件，不删则只增不减）。 */
    private val pageDiskCache: com.llzx373.foldreader.core.reader.PageDiskCache? = null,
    /** 漫画解压缓存与本地副本；删除书籍时按 contentHash 清理。 */
    private val comicStore: com.llzx373.foldreader.core.comic.ComicExtractionStore? = null,
    /** 源文件副本目录；删除书籍时按 `fileUri` 清理（只在**这个目录内**才动手）。 */
    private val sourceDir: java.io.File? = null,
    /** M19 译本副本；删除书籍且清本地数据时按 bookId 连文件一起清理。 */
    private val translationStore: com.llzx373.foldreader.core.translate.TranslationStore? = null,
    /** M19 翻译台账；随「删除本地数据」一起清（书都没了，台账留着只会误导重译判据）。 */
    private val translationDao: com.llzx373.foldreader.core.data.db.TranslationDao? = null,
    /** M20 术语表；单书术语随「删除本地数据」按 (scope=book, ownerKey=bookId) 定点清。 */
    private val glossaryTermDao: com.llzx373.foldreader.core.data.db.GlossaryTermDao? = null,
) : BookshelfRepository {

    override fun observeBookshelf(): Flow<List<BookEntity>> = bookDao.observeBookshelf()

    override fun observeBookshelfWithProgress(): Flow<List<BookWithProgress>> =
        bookDao.observeBookshelfWithProgress()

    override suspend fun getBook(bookId: Long): BookEntity? = bookDao.getById(bookId)

    override fun observeBook(bookId: Long): Flow<BookEntity?> = bookDao.observeById(bookId)

    override suspend fun updateEncoding(bookId: Long, encoding: String) =
        bookDao.updateEncoding(bookId, encoding)

    override suspend fun findByFileUri(fileUri: String): BookEntity? =
        bookDao.getByFileUri(fileUri)

    override suspend fun findByContentHash(contentHash: String): BookEntity? =
        bookDao.getByContentHash(contentHash)

    override suspend fun upsertBook(book: BookEntity): Long = bookDao.upsert(book)

    override suspend fun touchLastRead(bookId: Long, timestamp: Long) =
        bookDao.touchLastRead(bookId, timestamp)

    override suspend fun markContentPrepared(bookId: Long, timestamp: Long) =
        bookDao.markContentPrepared(bookId, timestamp)

    override suspend fun updateComicPageCount(bookId: Long, pageCount: Int) =
        bookDao.updateComicPageCount(bookId, pageCount)

    override suspend fun updateCoverPath(bookId: Long, coverPath: String?) =
        bookDao.updateCoverPath(bookId, coverPath)

    override suspend fun backfillPdfMetadata(
        bookId: Long,
        title: String?,
        author: String?,
        description: String?,
        subjects: String?,
    ) = bookDao.backfillPdfMetadata(bookId, title, author, description, subjects)

    override suspend fun updateComicLocalPath(bookId: Long, localPath: String?) =
        bookDao.updateComicLocalPath(bookId, localPath)

    override suspend fun updateConvertedFile(bookId: Long, cleanedFilePath: String?, totalChars: Long) =
        bookDao.updateConvertedFile(bookId, cleanedFilePath, totalChars)

    override suspend fun deleteBooks(bookIds: List<Long>, deleteLocalData: Boolean) {
        if (deleteLocalData) {
            progressDao.deleteByBookIds(bookIds)
            bookmarkDao.deleteByBookIds(bookIds)
            annotationDao.deleteByBookIds(bookIds)
            translationDao?.let { dao -> bookIds.forEach { dao.deleteForBook(it) } }
            translationStore?.let { store -> bookIds.forEach { store.deleteBook(it) } }
            glossaryTermDao?.let { dao ->
                bookIds.forEach {
                    dao.deleteFor(
                        com.llzx373.foldreader.core.data.db.GlossaryTermEntity.SCOPE_BOOK,
                        it.toString(),
                    )
                }
            }
        }
        pageDiskCache?.let { cache -> bookIds.forEach { cache.deleteForBook(it) } }
        val booksById = bookDao.getByIds(bookIds).associateBy { it.id }
        // 清洗副本与源副本都是**内容寻址**的，同一个文件的原版与清洗版会共用同一份（源副本按原文
        // 哈希，清洗副本按产物哈希——两行用同一套规则重洗就落到同一个文件上）。所以文件不能随行
        // 一起删：先收集候选，等行删完再判「还有没有别人引用它」。
        val cleanedCandidates = bookIds.mapNotNull { booksById[it]?.cleanedFilePath }.distinct()
        val sourceCopyCandidates = bookIds.mapNotNull { id ->
            val book = booksById[id] ?: return@mapNotNull null
            book.sourceCopyFile(sourceDir)?.let { file -> book.fileUri to file }
        }.distinctBy { it.second.absolutePath }
        bookIds.forEach { id ->
            val book = booksById[id]
            book?.coverPath?.let { java.io.File(it).delete() }
            if (book != null && convertedDir != null && book.contentHash.isNotBlank()) {
                java.io.File(convertedDir, "${book.contentHash}.txt").delete()
                java.io.File(convertedDir, "${book.contentHash}.toc").delete()
                java.io.File(convertedDir, "${book.contentHash}.anchors").delete()
                java.io.File(convertedDir, "${book.contentHash}.pages").delete()
                java.io.File(convertedDir, "${book.contentHash}.spans").delete()
                java.io.File(convertedDir, "${book.contentHash}.version").delete()
                java.io.File(convertedDir, "${book.contentHash}.images").deleteRecursively()
            }
            if (book != null && coversDir != null && book.coverPath == null &&
                book.contentHash.isNotBlank()
            ) {
                // 兜底：coverPath 缺失（如旧版本导入的书）时按 contentHash 前缀清
                coversDir.listFiles { f -> f.name.startsWith("${book.contentHash}.") }
                    ?.forEach { it.delete() }
            }
            if (book != null && comicStore != null && book.contentHash.isNotBlank()) {
                // 漫画解压缓存与「复制到本地」的副本一并清掉（引用外部源的原始文件不动）
                comicStore.deleteAll(book.contentHash)
            }
        }
        bookDao.deleteByIds(bookIds)
        // 行已删完，这时反查为空才说明这份文件真的没人用了
        cleanedCandidates.forEach { path ->
            if (bookDao.getByCleanedFilePath(path) == null) java.io.File(path).delete()
        }
        sourceCopyCandidates.forEach { (uri, file) ->
            if (bookDao.getByFileUri(uri) == null) file.delete()
        }
    }

    override fun observeGroupNames(): Flow<List<String>> = bookDao.observeGroupNames()

    override fun observeBookshelfWithProgressInGroup(groupName: String?): Flow<List<BookWithProgress>> =
        bookDao.observeBookshelfWithProgressInGroup(groupName)

    override suspend fun updateGroup(bookIds: List<Long>, groupName: String?) {
        if (bookIds.isEmpty()) return
        bookDao.updateGroup(bookIds, groupName?.trim()?.takeIf { it.isNotEmpty() })
    }

    override suspend fun clearGroup(groupName: String) = bookDao.clearGroup(groupName)

    override suspend fun applyAiMetadata(
        bookId: Long,
        author: String?,
        description: String?,
        genreTag: String?,
        metaSource: String,
    ) = bookDao.applyAiMetadata(bookId, author, description, genreTag, metaSource)

    override suspend fun updateUserMetadata(
        bookId: Long,
        author: String?,
        description: String?,
        genreTag: String?,
        metaSource: String,
    ) = bookDao.updateUserMetadata(bookId, author, description, genreTag, metaSource)

    override suspend fun groupBooksByGenreTag(): Int = bookDao.groupByGenreTag()

    override fun observeProgress(bookId: Long): Flow<ReadingProgressEntity?> =
        progressDao.observe(bookId)

    override suspend fun getProgress(bookId: Long): ReadingProgressEntity? =
        progressDao.get(bookId)

    override suspend fun saveProgress(progress: ReadingProgressEntity) =
        progressDao.upsert(progress)

    private fun ChapterEntity.toChapter() =
        Chapter(title = title, charStart = charStart, charEnd = charEnd, depth = depth, pageIndex = pageIndex)

    override suspend fun getChapters(bookId: Long): List<Chapter> =
        chapterDao.getForBook(bookId).map { it.toChapter() }

    override fun observeChapters(bookId: Long): Flow<List<Chapter>> =
        chapterDao.observeForBook(bookId).map { list -> list.map { it.toChapter() } }

    override fun observePersonAppearances(bookId: Long): Flow<List<PersonAppearanceEntity>> =
        personAppearanceDao?.observeForBook(bookId) ?: kotlinx.coroutines.flow.flowOf(emptyList())

    override suspend fun saveChapters(bookId: Long, chapters: List<Chapter>) {
        chapterDao.replaceForBook(
            bookId,
            chapters.mapIndexed { index, chapter ->
                ChapterEntity(
                    bookId = bookId,
                    chapterIndex = index,
                    title = chapter.title,
                    charStart = chapter.charStart,
                    charEnd = chapter.charEnd,
                    depth = chapter.depth,
                    pageIndex = chapter.pageIndex,
                )
            },
        )
    }

    override fun observeBookmarks(bookId: Long): Flow<List<BookmarkEntity>> =
        bookmarkDao.observeByBook(bookId)

    override fun observeAllBookmarks(): Flow<List<BookmarkEntity>> = bookmarkDao.observeAll()

    override suspend fun addBookmark(bookmark: BookmarkEntity): Long = bookmarkDao.insert(bookmark)

    override suspend fun renameBookmark(bookmark: BookmarkEntity) = bookmarkDao.update(bookmark)

    override suspend fun deleteBookmark(id: Long) = bookmarkDao.deleteById(id)

    override fun observeAnnotations(bookId: Long): Flow<List<AnnotationEntity>> =
        annotationDao.observeByBook(bookId)

    override fun observeAllAnnotations(): Flow<List<AnnotationEntity>> = annotationDao.observeAll()

    override suspend fun addAnnotation(annotation: AnnotationEntity): Long =
        annotationDao.insert(annotation)

    override suspend fun updateAnnotation(annotation: AnnotationEntity) =
        annotationDao.update(annotation)

    override suspend fun deleteAnnotation(id: Long) = annotationDao.deleteById(id)

    override suspend fun addReadingSession(bookId: Long, dayStartMs: Long, deltaMs: Long) {
        if (deltaMs <= 0L) return
        val existing = sessionDao.get(bookId, dayStartMs)
        sessionDao.upsert(
            ReadingSessionEntity(
                bookId = bookId,
                dayStartMs = dayStartMs,
                durationMs = (existing?.durationMs ?: 0L) + deltaMs,
            ),
        )
    }

    override suspend fun getReadingSessionsBetween(startMs: Long, endMs: Long): List<ReadingSessionEntity> =
        sessionDao.getBetween(startMs, endMs)

    override suspend fun getReadingDayCount(bookId: Long): Int = sessionDao.countReadingDays(bookId)
}

/**
 * 这本书的源文件副本——`fileUri` 指向私有 source 目录里的那一份时返回它，否则 null。
 *
 * **只认这个目录内的路径**：漫画与 PDF 的 `fileUri` 仍然指向外部（可能是用户自己的文件），
 * 拿它当路径去删会删掉用户的东西，所以必须按目录前缀卡住。
 */
private fun BookEntity.sourceCopyFile(sourceDir: java.io.File?): java.io.File? {
    if (sourceDir == null) return null
    val path = fileUri.removePrefix("file://")
    val prefix = sourceDir.absolutePath + java.io.File.separator
    return path.takeIf { it.startsWith(prefix) }?.let { java.io.File(it) }
}

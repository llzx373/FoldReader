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
import com.llzx373.foldreader.core.data.db.ReadingProgressDao
import com.llzx373.foldreader.core.data.db.ReadingProgressEntity
import com.llzx373.foldreader.core.data.db.ReadingSessionDao
import com.llzx373.foldreader.core.data.db.ReadingSessionEntity
import com.llzx373.foldreader.core.format.Chapter
import kotlinx.coroutines.flow.Flow

class BookshelfRepositoryImpl(
    private val bookDao: BookDao,
    private val progressDao: ReadingProgressDao,
    private val chapterDao: ChapterDao,
    private val bookmarkDao: BookmarkDao,
    private val annotationDao: AnnotationDao,
    private val sessionDao: ReadingSessionDao,
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

    override suspend fun deleteBooks(bookIds: List<Long>, deleteLocalData: Boolean) {
        if (deleteLocalData) {
            progressDao.deleteByBookIds(bookIds)
            bookmarkDao.deleteByBookIds(bookIds)
            annotationDao.deleteByBookIds(bookIds)
        }
        bookIds.forEach { id ->
            bookDao.getById(id)?.cleanedFilePath?.let { java.io.File(it).delete() }
        }
        bookDao.deleteByIds(bookIds)
    }

    override fun observeGroupNames(): Flow<List<String>> = bookDao.observeGroupNames()

    override fun observeBookshelfWithProgressInGroup(groupName: String?): Flow<List<BookWithProgress>> =
        bookDao.observeBookshelfWithProgressInGroup(groupName)

    override suspend fun updateGroup(bookIds: List<Long>, groupName: String?) {
        if (bookIds.isEmpty()) return
        bookDao.updateGroup(bookIds, groupName?.trim()?.takeIf { it.isNotEmpty() })
    }

    override suspend fun clearGroup(groupName: String) = bookDao.clearGroup(groupName)

    override fun observeProgress(bookId: Long): Flow<ReadingProgressEntity?> =
        progressDao.observe(bookId)

    override suspend fun getProgress(bookId: Long): ReadingProgressEntity? =
        progressDao.get(bookId)

    override suspend fun saveProgress(progress: ReadingProgressEntity) =
        progressDao.upsert(progress)

    override suspend fun getChapters(bookId: Long): List<Chapter> =
        chapterDao.getForBook(bookId).map { Chapter(it.title, it.charStart, it.charEnd) }

    override suspend fun saveChapters(bookId: Long, chapters: List<Chapter>) {
        chapterDao.deleteForBook(bookId)
        chapterDao.upsertAll(
            chapters.mapIndexed { index, chapter ->
                ChapterEntity(
                    bookId = bookId,
                    chapterIndex = index,
                    title = chapter.title,
                    charStart = chapter.charStart,
                    charEnd = chapter.charEnd,
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

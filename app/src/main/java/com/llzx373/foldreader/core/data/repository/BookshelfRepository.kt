package com.llzx373.foldreader.core.data.repository

import com.llzx373.foldreader.core.data.db.AnnotationEntity
import com.llzx373.foldreader.core.data.db.BookEntity
import com.llzx373.foldreader.core.data.db.BookWithProgress
import com.llzx373.foldreader.core.data.db.BookmarkEntity
import com.llzx373.foldreader.core.data.db.ReadingProgressEntity
import com.llzx373.foldreader.core.data.db.ReadingSessionEntity
import com.llzx373.foldreader.core.format.Chapter
import kotlinx.coroutines.flow.Flow

interface BookshelfRepository {
    fun observeBookshelf(): Flow<List<BookEntity>>
    fun observeBookshelfWithProgress(): Flow<List<BookWithProgress>>
    suspend fun getBook(bookId: Long): BookEntity?
    suspend fun findByFileUri(fileUri: String): BookEntity?
    suspend fun findByContentHash(contentHash: String): BookEntity?
    suspend fun upsertBook(book: BookEntity): Long
    suspend fun touchLastRead(bookId: Long, timestamp: Long = System.currentTimeMillis())
    suspend fun deleteBooks(bookIds: List<Long>)

    fun observeProgress(bookId: Long): Flow<ReadingProgressEntity?>
    suspend fun getProgress(bookId: Long): ReadingProgressEntity?
    suspend fun saveProgress(progress: ReadingProgressEntity)

    suspend fun getChapters(bookId: Long): List<Chapter>
    suspend fun saveChapters(bookId: Long, chapters: List<Chapter>)

    fun observeBookmarks(bookId: Long): Flow<List<BookmarkEntity>>
    suspend fun addBookmark(bookmark: BookmarkEntity): Long
    suspend fun renameBookmark(bookmark: BookmarkEntity)
    suspend fun deleteBookmark(id: Long)

    fun observeAnnotations(bookId: Long): Flow<List<AnnotationEntity>>
    suspend fun addAnnotation(annotation: AnnotationEntity): Long
    suspend fun updateAnnotation(annotation: AnnotationEntity)
    suspend fun deleteAnnotation(id: Long)

    /** 按天分桶累加阅读时长（增量 [deltaMs]）。 */
    suspend fun addReadingSession(bookId: Long, dayStartMs: Long, deltaMs: Long)
    suspend fun getReadingSessionsBetween(startMs: Long, endMs: Long): List<ReadingSessionEntity>
}

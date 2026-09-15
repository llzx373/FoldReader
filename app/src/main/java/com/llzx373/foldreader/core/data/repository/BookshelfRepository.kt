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
    fun observeBook(bookId: Long): Flow<BookEntity?>

    /** [encoding] 为空串表示恢复自动检测。 */
    suspend fun updateEncoding(bookId: Long, encoding: String)
    suspend fun findByFileUri(fileUri: String): BookEntity?
    suspend fun findByContentHash(contentHash: String): BookEntity?
    suspend fun upsertBook(book: BookEntity): Long
    suspend fun touchLastRead(bookId: Long, timestamp: Long = System.currentTimeMillis())
    suspend fun deleteBooks(bookIds: List<Long>, deleteLocalData: Boolean = true)

    fun observeGroupNames(): Flow<List<String>>
    fun observeBookshelfWithProgressInGroup(groupName: String?): Flow<List<BookWithProgress>>

    /** 批量设置分组；[groupName] 为 null 表示移出分组。 */
    suspend fun updateGroup(bookIds: List<Long>, groupName: String?)

    /** 删除分组：把该分组下所有书移出分组。 */
    suspend fun clearGroup(groupName: String)

    fun observeProgress(bookId: Long): Flow<ReadingProgressEntity?>
    suspend fun getProgress(bookId: Long): ReadingProgressEntity?
    suspend fun saveProgress(progress: ReadingProgressEntity)

    suspend fun getChapters(bookId: Long): List<Chapter>
    suspend fun saveChapters(bookId: Long, chapters: List<Chapter>)

    fun observeBookmarks(bookId: Long): Flow<List<BookmarkEntity>>
    fun observeAllBookmarks(): Flow<List<BookmarkEntity>>
    suspend fun addBookmark(bookmark: BookmarkEntity): Long
    suspend fun renameBookmark(bookmark: BookmarkEntity)
    suspend fun deleteBookmark(id: Long)

    fun observeAnnotations(bookId: Long): Flow<List<AnnotationEntity>>
    fun observeAllAnnotations(): Flow<List<AnnotationEntity>>
    suspend fun addAnnotation(annotation: AnnotationEntity): Long
    suspend fun updateAnnotation(annotation: AnnotationEntity)
    suspend fun deleteAnnotation(id: Long)

    /** 按天分桶累加阅读时长（增量 [deltaMs]）。 */
    suspend fun addReadingSession(bookId: Long, dayStartMs: Long, deltaMs: Long)
    suspend fun getReadingSessionsBetween(startMs: Long, endMs: Long): List<ReadingSessionEntity>

    /** 实际阅读天数：该书有阅读记录的日期去重计数。 */
    suspend fun getReadingDayCount(bookId: Long): Int
}

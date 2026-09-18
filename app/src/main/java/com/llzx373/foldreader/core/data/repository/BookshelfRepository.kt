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

    /**
     * 标记内容已就绪（EPUB/FB2 的整本压平已完成）。由后台预热队列与阅读器各自回写，
     * 书架角标据此显示/隐藏。
     */
    suspend fun markContentPrepared(bookId: Long, timestamp: Long = System.currentTimeMillis())

    /** 回填漫画页数（rar/tar/7z 首次打开才知道真实页数）。 */
    suspend fun updateComicPageCount(bookId: Long, pageCount: Int)

    /** 回填封面路径（漫画的封面要等后台解压完才拿得到）。 */
    suspend fun updateCoverPath(bookId: Long, coverPath: String?)

    /** PDF 预热回填元数据：只填空值，传 null 表示"没读到"而不是"清空"。 */
    suspend fun backfillPdfMetadata(
        bookId: Long,
        title: String?,
        author: String?,
        description: String?,
        subjects: String?,
    )

    /** 记录压平产物（PDF 文本模式用）；cleanedFilePath 为 null 表示没有可读正文。 */
    suspend fun updateConvertedFile(bookId: Long, cleanedFilePath: String?, totalChars: Long)

    /** 记录/清除「复制到本地」的页目录（null = 回到引用外部源）。 */
    suspend fun updateComicLocalPath(bookId: Long, localPath: String?)

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
    fun observeChapters(bookId: Long): Flow<List<Chapter>>
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

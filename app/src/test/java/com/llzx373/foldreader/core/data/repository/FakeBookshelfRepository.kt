package com.llzx373.foldreader.core.data.repository

import com.llzx373.foldreader.core.data.db.AnnotationEntity
import com.llzx373.foldreader.core.data.db.BookEntity
import com.llzx373.foldreader.core.data.db.BookWithProgress
import com.llzx373.foldreader.core.data.db.BookmarkEntity
import com.llzx373.foldreader.core.data.db.ReadingProgressEntity
import com.llzx373.foldreader.core.data.db.ReadingSessionEntity
import com.llzx373.foldreader.core.format.Chapter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * 内存版书架仓库，供不关心 Room 的单测使用。
 *
 * 只有「书 + 进度 + 分组」做了真实实现；书签/标注/章节/会话这些还没被用到的接口直接抛错，
 * 免得测试悄悄依赖一个空实现的假成功。需要时再补。
 */
class FakeBookshelfRepository : BookshelfRepository {

    val books = MutableStateFlow<List<BookEntity>>(emptyList())
    private val progress = MutableStateFlow<Map<Long, ReadingProgressEntity>>(emptyMap())
    private var nextId = 1L

    override fun observeBookshelf(): Flow<List<BookEntity>> = books

    override fun observeBookshelfWithProgress(): Flow<List<BookWithProgress>> =
        books.map { list ->
            list.map { BookWithProgress(it, progress.value[it.id]?.charOffset, progress.value[it.id]?.comicPage) }
        }

    override suspend fun getBook(bookId: Long): BookEntity? = books.value.firstOrNull { it.id == bookId }

    override fun observeBook(bookId: Long): Flow<BookEntity?> = books.map { list ->
        list.firstOrNull { it.id == bookId }
    }

    override suspend fun updateEncoding(bookId: Long, encoding: String) {
        books.value = books.value.map { if (it.id == bookId) it.copy(encoding = encoding) else it }
    }

    override suspend fun findByFileUri(fileUri: String): BookEntity? =
        books.value.firstOrNull { it.fileUri == fileUri }

    override suspend fun findByContentHash(contentHash: String): BookEntity? =
        books.value.firstOrNull { it.contentHash == contentHash }

    override suspend fun upsertBook(book: BookEntity): Long {
        val id = if (book.id != 0L) book.id else nextId++
        books.value = books.value.filter { it.id != id } +
            book.copy(id = id, importedAt = if (book.importedAt == 0L) 1L else book.importedAt)
        return id
    }

    override suspend fun touchLastRead(bookId: Long, timestamp: Long) {
        books.value = books.value.map { if (it.id == bookId) it.copy(lastReadAt = timestamp) else it }
    }

    override suspend fun markContentPrepared(bookId: Long, timestamp: Long) {
        books.value = books.value.map { if (it.id == bookId) it.copy(contentPreparedAt = timestamp) else it }
    }

    override suspend fun updateComicPageCount(bookId: Long, pageCount: Int) {
        books.value = books.value.map { if (it.id == bookId) it.copy(comicPageCount = pageCount) else it }
    }

    override suspend fun updateCoverPath(bookId: Long, coverPath: String?) {
        books.value = books.value.map { if (it.id == bookId) it.copy(coverPath = coverPath) else it }
    }

    override suspend fun backfillPdfMetadata(
        bookId: Long,
        title: String?,
        author: String?,
        description: String?,
        subjects: String?,
    ) {
        books.value = books.value.map {
            if (it.id != bookId) {
                it
            } else {
                it.copy(
                    title = title ?: it.title,
                    author = author ?: it.author,
                    description = description ?: it.description,
                    subjects = subjects ?: it.subjects,
                )
            }
        }
    }

    override suspend fun updateComicLocalPath(bookId: Long, localPath: String?) {
        books.value = books.value.map { if (it.id == bookId) it.copy(comicLocalPath = localPath) else it }
    }

    override suspend fun updateConvertedFile(bookId: Long, cleanedFilePath: String?, totalChars: Long) {
        books.value = books.value.map {
            if (it.id == bookId) it.copy(cleanedFilePath = cleanedFilePath, totalChars = totalChars) else it
        }
    }

    override suspend fun deleteBooks(bookIds: List<Long>, deleteLocalData: Boolean) {
        books.value = books.value.filterNot { it.id in bookIds }
    }

    override fun observeGroupNames(): Flow<List<String>> = books.map { list ->
        list.mapNotNull { it.groupName }.distinct().sorted()
    }

    override fun observeBookshelfWithProgressInGroup(groupName: String?): Flow<List<BookWithProgress>> =
        books.map { list ->
            list.filter { it.groupName == groupName }
                .map { BookWithProgress(it, progress.value[it.id]?.charOffset, progress.value[it.id]?.comicPage) }
        }

    override suspend fun updateGroup(bookIds: List<Long>, groupName: String?) {
        books.value = books.value.map {
            if (it.id in bookIds) it.copy(groupName = groupName) else it
        }
    }

    override suspend fun clearGroup(groupName: String) {
        books.value = books.value.map { if (it.groupName == groupName) it.copy(groupName = null) else it }
    }

    override fun observeProgress(bookId: Long): Flow<ReadingProgressEntity?> =
        MutableStateFlow(progress.value[bookId])

    override suspend fun getProgress(bookId: Long): ReadingProgressEntity? = progress.value[bookId]

    override suspend fun saveProgress(entity: ReadingProgressEntity) {
        progress.value = progress.value + (entity.bookId to entity)
    }

    override suspend fun getChapters(bookId: Long): List<Chapter> = unsupported()

    override fun observeChapters(bookId: Long): Flow<List<Chapter>> = unsupported()

    override suspend fun saveChapters(bookId: Long, chapters: List<Chapter>) = unsupported()

    override fun observeBookmarks(bookId: Long): Flow<List<BookmarkEntity>> = unsupported()

    override fun observeAllBookmarks(): Flow<List<BookmarkEntity>> = unsupported()

    override suspend fun addBookmark(bookmark: BookmarkEntity): Long = unsupported()

    override suspend fun renameBookmark(bookmark: BookmarkEntity) = unsupported()

    override suspend fun deleteBookmark(id: Long) = unsupported()

    override fun observeAnnotations(bookId: Long): Flow<List<AnnotationEntity>> = unsupported()

    override fun observeAllAnnotations(): Flow<List<AnnotationEntity>> = unsupported()

    override suspend fun addAnnotation(annotation: AnnotationEntity): Long = unsupported()

    override suspend fun updateAnnotation(annotation: AnnotationEntity) = unsupported()

    override suspend fun deleteAnnotation(id: Long) = unsupported()

    override suspend fun addReadingSession(bookId: Long, dayStartMs: Long, deltaMs: Long) = unsupported()

    override suspend fun getReadingSessionsBetween(startMs: Long, endMs: Long): List<ReadingSessionEntity> =
        unsupported()

    override suspend fun getReadingDayCount(bookId: Long): Int = unsupported()

    private fun unsupported(): Nothing =
        error("FakeBookshelfRepository 未实现该接口（本测试用不到）")
}

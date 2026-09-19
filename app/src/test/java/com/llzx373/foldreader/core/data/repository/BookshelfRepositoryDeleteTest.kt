package com.llzx373.foldreader.core.data.repository

import com.llzx373.foldreader.core.data.db.AnnotationDao
import com.llzx373.foldreader.core.data.db.AnnotationEntity
import com.llzx373.foldreader.core.data.db.BookDao
import com.llzx373.foldreader.core.data.db.BookEntity
import com.llzx373.foldreader.core.data.db.BookFormat
import com.llzx373.foldreader.core.data.db.BookWithProgress
import com.llzx373.foldreader.core.data.db.BookmarkDao
import com.llzx373.foldreader.core.data.db.BookmarkEntity
import com.llzx373.foldreader.core.data.db.ChapterDao
import com.llzx373.foldreader.core.data.db.ChapterEntity
import com.llzx373.foldreader.core.data.db.ReadingProgressDao
import com.llzx373.foldreader.core.data.db.ReadingProgressEntity
import com.llzx373.foldreader.core.data.db.ReadingSessionDao
import com.llzx373.foldreader.core.data.db.ReadingSessionEntity
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BookshelfRepositoryDeleteTest {

    private lateinit var bookDao: FakeBookDao
    private lateinit var progressDao: FakeProgressDao
    private lateinit var bookmarkDao: FakeBookmarkDao
    private lateinit var annotationDao: FakeAnnotationDao
    private lateinit var pageDiskCache: RecordingPageDiskCache
    private lateinit var repository: BookshelfRepository

    @Before
    fun setUp() {
        bookDao = FakeBookDao()
        progressDao = FakeProgressDao()
        bookmarkDao = FakeBookmarkDao()
        annotationDao = FakeAnnotationDao()
        pageDiskCache = RecordingPageDiskCache()
        repository = BookshelfRepositoryImpl(
            bookDao = bookDao,
            progressDao = progressDao,
            chapterDao = FakeChapterDao(),
            bookmarkDao = bookmarkDao,
            annotationDao = annotationDao,
            sessionDao = FakeSessionDao(),
            pageDiskCache = pageDiskCache,
        )
        bookDao.books += book(1)
        bookDao.books += book(2)
        progressDao.rows += progress(1)
        progressDao.rows += progress(2)
        bookmarkDao.rows += BookmarkEntity(id = 1, bookId = 1, charOffset = 0, chapterIndex = 0, snapshotText = "", createdAt = 0)
        bookmarkDao.rows += BookmarkEntity(id = 2, bookId = 2, charOffset = 0, chapterIndex = 0, snapshotText = "", createdAt = 0)
        annotationDao.rows += AnnotationEntity(id = 1, bookId = 1, startCharOffset = 0, endCharOffset = 1, selectedText = "", color = 0, note = null, createdAt = 0, updatedAt = 0)
        annotationDao.rows += AnnotationEntity(id = 2, bookId = 2, startCharOffset = 0, endCharOffset = 1, selectedText = "", color = 0, note = null, createdAt = 0, updatedAt = 0)
    }

    @Test
    fun `默认删除时进度书签标注随书删除`() = runBlocking {
        repository.deleteBooks(listOf(1L))

        assertEquals(listOf(2L), bookDao.books.map { it.id })
        assertEquals(listOf(2L), progressDao.rows.map { it.bookId })
        assertEquals(listOf(2L), bookmarkDao.rows.map { it.bookId })
        assertEquals(listOf(2L), annotationDao.rows.map { it.bookId })
    }

    @Test
    fun `显式级联删除时进度书签标注随书删除`() = runBlocking {
        repository.deleteBooks(listOf(1L), deleteLocalData = true)

        assertEquals(listOf(2L), bookDao.books.map { it.id })
        assertTrue(progressDao.rows.none { it.bookId == 1L })
        assertTrue(bookmarkDao.rows.none { it.bookId == 1L })
        assertTrue(annotationDao.rows.none { it.bookId == 1L })
    }

    @Test
    fun `保留本地数据时进度书签标注保留`() = runBlocking {
        repository.deleteBooks(listOf(1L), deleteLocalData = false)

        assertEquals(listOf(2L), bookDao.books.map { it.id })
        assertEquals(listOf(1L, 2L), progressDao.rows.map { it.bookId }.sorted())
        assertEquals(listOf(1L, 2L), bookmarkDao.rows.map { it.bookId }.sorted())
        assertEquals(listOf(1L, 2L), annotationDao.rows.map { it.bookId }.sorted())
    }

    @Test
    fun `批量删除多本书时仅目标书数据被清理`() = runBlocking {
        repository.deleteBooks(listOf(1L, 2L))

        assertTrue(bookDao.books.isEmpty())
        assertTrue(progressDao.rows.isEmpty())
        assertTrue(bookmarkDao.rows.isEmpty())
        assertTrue(annotationDao.rows.isEmpty())
    }

    @Test
    fun `删除书籍时一并清理该书的页边界缓存`() = runBlocking {
        repository.deleteBooks(listOf(1L, 2L))

        // 改一次版式就多一份 bounds 文件，不按 bookId 清理则 page_bounds/ 只增不减
        assertEquals(listOf(1L, 2L), pageDiskCache.deletedBooks)
    }

    @Test
    fun `保留本地数据时仍清理页边界缓存`() = runBlocking {
        // 页边界只是分页加速缓存，与「保留本地进度/标注」无关，删书就该回收
        repository.deleteBooks(listOf(1L), deleteLocalData = false)

        assertEquals(listOf(1L), pageDiskCache.deletedBooks)
    }

    /**
     * 清洗副本与源副本都是**内容寻址**的，同一个源文件的原版与清洗版会共用同一份
     * （两行用同一套规则重洗就落到同一个文件上）。删书不能无条件删文件，否则删一本会把
     * 另一本的正文一起删掉——那一本以后打开只有「无法打开书籍」。
     */
    @Test
    fun `共享的清洗副本与源副本只在最后一个引用者被删时才清理`() = runBlocking {
        val dir = java.nio.file.Files.createTempDirectory("foldreader-delete").toFile()
        val sourceDir = File(dir, "source").apply { mkdirs() }
        val sourceCopy = File(sourceDir, "h1.txt").apply { writeText("原文") }
        val cleanedCopy = File(dir, "cleaned-h1.txt").apply { writeText("清洗后") }
        val sharedSourceUri = "file://${sourceCopy.absolutePath}"

        bookDao.books.replaceAll {
            if (it.id == 1L || it.id == 2L) {
                it.copy(fileUri = sharedSourceUri, cleanedFilePath = cleanedCopy.absolutePath)
            } else {
                it
            }
        }
        val repoWithSourceDir = BookshelfRepositoryImpl(
            bookDao = bookDao,
            progressDao = progressDao,
            chapterDao = FakeChapterDao(),
            bookmarkDao = bookmarkDao,
            annotationDao = annotationDao,
            sessionDao = FakeSessionDao(),
            pageDiskCache = pageDiskCache,
            sourceDir = sourceDir,
        )

        repoWithSourceDir.deleteBooks(listOf(1L))
        assertTrue("还有一行在用，两个文件都不能删", sourceCopy.isFile && cleanedCopy.isFile)

        repoWithSourceDir.deleteBooks(listOf(2L))
        assertTrue("最后一个引用者被删，两个文件才该清掉", !sourceCopy.exists() && !cleanedCopy.exists())
    }

    private fun book(id: Long) = BookEntity(
        id = id,
        title = "书$id",
        author = null,
        fileUri = "content://book/$id",
        contentHash = "hash$id",
        format = BookFormat.TXT,
        totalChars = 1000,
        encoding = "UTF-8",
        importedAt = 0,
        lastReadAt = null,
    )

    private fun progress(bookId: Long) = ReadingProgressEntity(
        bookId = bookId,
        charOffset = 0,
        chapterIndex = 0,
        totalReadingMillis = 0,
        updatedAt = 0,
    )

    private class FakeBookDao : BookDao {
        val books = mutableListOf<BookEntity>()
        override fun observeBookshelf(): Flow<List<BookEntity>> = flowOf(books)
        override fun observeBookshelfWithProgress(): Flow<List<BookWithProgress>> = flowOf(emptyList())
        override fun observeGroupNames(): Flow<List<String>> =
            flowOf(books.mapNotNull { it.groupName }.distinct().sorted())
        override fun observeBookshelfWithProgressInGroup(groupName: String?): Flow<List<BookWithProgress>> =
            flowOf(emptyList())
        override suspend fun updateGroup(bookIds: List<Long>, groupName: String?) {
            books.replaceAll { if (it.id in bookIds) it.copy(groupName = groupName) else it }
        }
        override suspend fun clearGroup(groupName: String) {
            books.replaceAll { if (it.groupName == groupName) it.copy(groupName = null) else it }
        }
        override suspend fun getById(bookId: Long): BookEntity? = books.find { it.id == bookId }
        override suspend fun getByIds(bookIds: List<Long>): List<BookEntity> =
            books.filter { it.id in bookIds }
        override fun observeById(bookId: Long): Flow<BookEntity?> = flowOf(books.find { it.id == bookId })
        override suspend fun updateEncoding(bookId: Long, encoding: String) {
            books.replaceAll { if (it.id == bookId) it.copy(encoding = encoding) else it }
        }
        override suspend fun getByFileUri(fileUri: String): BookEntity? =
            books.firstOrNull { it.fileUri == fileUri }
        override suspend fun getByContentHash(contentHash: String): BookEntity? = null
        override suspend fun getByCleanedFilePath(cleanedFilePath: String): BookEntity? =
            books.firstOrNull { it.cleanedFilePath == cleanedFilePath }
        override suspend fun upsert(book: BookEntity): Long = book.id
        override suspend fun update(book: BookEntity) = Unit
        override suspend fun touchLastRead(bookId: Long, timestamp: Long) = Unit
        override suspend fun markContentPrepared(bookId: Long, timestamp: Long) {
            books.replaceAll { if (it.id == bookId) it.copy(contentPreparedAt = timestamp) else it }
        }
        override suspend fun updateComicPageCount(bookId: Long, pageCount: Int) {
            books.replaceAll { if (it.id == bookId) it.copy(comicPageCount = pageCount) else it }
        }
        override suspend fun updateCoverPath(bookId: Long, coverPath: String?) {
            books.replaceAll { if (it.id == bookId) it.copy(coverPath = coverPath) else it }
        }

        override suspend fun backfillPdfMetadata(
            bookId: Long,
            title: String?,
            author: String?,
            description: String?,
            subjects: String?,
        ) {
            books.replaceAll {
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
            books.replaceAll { if (it.id == bookId) it.copy(comicLocalPath = localPath) else it }
        }
        override suspend fun updateConvertedFile(
            bookId: Long,
            cleanedFilePath: String?,
            totalChars: Long,
        ) = Unit
        override suspend fun deleteByIds(bookIds: List<Long>) {
            books.removeAll { it.id in bookIds }
        }
    }

    private class FakeProgressDao : ReadingProgressDao {
        val rows = mutableListOf<ReadingProgressEntity>()
        override suspend fun get(bookId: Long): ReadingProgressEntity? = rows.find { it.bookId == bookId }
        override fun observe(bookId: Long): Flow<ReadingProgressEntity?> = flowOf(rows.find { it.bookId == bookId })
        override suspend fun upsert(progress: ReadingProgressEntity) {
            rows.removeAll { it.bookId == progress.bookId }
            rows += progress
        }
        override suspend fun delete(bookId: Long) {
            rows.removeAll { it.bookId == bookId }
        }
        override suspend fun deleteByBookIds(bookIds: List<Long>) {
            rows.removeAll { it.bookId in bookIds }
        }
    }

    private class FakeChapterDao : ChapterDao {
        override suspend fun getForBook(bookId: Long): List<ChapterEntity> = emptyList()
        override fun observeForBook(bookId: Long): Flow<List<ChapterEntity>> = flowOf(emptyList())
        override suspend fun upsertAll(chapters: List<ChapterEntity>) = Unit
        override suspend fun deleteForBook(bookId: Long) = Unit
    }

    private class FakeBookmarkDao : BookmarkDao {
        val rows = mutableListOf<BookmarkEntity>()
        override fun observeByBook(bookId: Long): Flow<List<BookmarkEntity>> = flowOf(rows.filter { it.bookId == bookId })
        override fun observeAll(): Flow<List<BookmarkEntity>> = flowOf(rows)
        override suspend fun insert(bookmark: BookmarkEntity): Long {
            rows += bookmark
            return bookmark.id
        }
        override suspend fun update(bookmark: BookmarkEntity) = Unit
        override suspend fun deleteById(id: Long) {
            rows.removeAll { it.id == id }
        }
        override suspend fun deleteByBookIds(bookIds: List<Long>) {
            rows.removeAll { it.bookId in bookIds }
        }
    }

    private class FakeAnnotationDao : AnnotationDao {
        val rows = mutableListOf<AnnotationEntity>()
        override fun observeByBook(bookId: Long): Flow<List<AnnotationEntity>> = flowOf(rows.filter { it.bookId == bookId })
        override fun observeAll(): Flow<List<AnnotationEntity>> = flowOf(rows)
        override suspend fun insert(annotation: AnnotationEntity): Long {
            rows += annotation
            return annotation.id
        }
        override suspend fun update(annotation: AnnotationEntity) = Unit
        override suspend fun deleteById(id: Long) {
            rows.removeAll { it.id == id }
        }
        override suspend fun deleteByBookIds(bookIds: List<Long>) {
            rows.removeAll { it.bookId in bookIds }
        }
    }

    private class RecordingPageDiskCache : com.llzx373.foldreader.core.reader.PageDiskCache {
        val deletedBooks = mutableListOf<Long>()

        override fun load(key: com.llzx373.foldreader.core.reader.PaginatorKey, charCount: Long): LongArray? = null
        override fun save(
            key: com.llzx373.foldreader.core.reader.PaginatorKey,
            charCount: Long,
            bounds: LongArray,
        ) = Unit

        override fun append(
            key: com.llzx373.foldreader.core.reader.PaginatorKey,
            charCount: Long,
            allBounds: LongArray,
            persistedCount: Int,
        ): Boolean = false

        override fun deleteForBook(bookId: Long) {
            deletedBooks += bookId
        }
    }

    private class FakeSessionDao : ReadingSessionDao {
        override suspend fun get(bookId: Long, dayStartMs: Long): ReadingSessionEntity? = null
        override suspend fun upsert(session: ReadingSessionEntity) = Unit
        override suspend fun getBetween(startMs: Long, endMs: Long): List<ReadingSessionEntity> = emptyList()
        override suspend fun getAll(): List<ReadingSessionEntity> = emptyList()
        override suspend fun countReadingDays(bookId: Long): Int = 0
    }
}

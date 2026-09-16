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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class BookshelfRepositoryGroupTest {

    private lateinit var bookDao: FakeBookDao
    private lateinit var repository: BookshelfRepository

    @Before
    fun setUp() {
        bookDao = FakeBookDao()
        repository = BookshelfRepositoryImpl(
            bookDao = bookDao,
            progressDao = FakeProgressDao(),
            chapterDao = FakeChapterDao(),
            bookmarkDao = FakeBookmarkDao(),
            annotationDao = FakeAnnotationDao(),
            sessionDao = FakeSessionDao(),
        )
        bookDao.books += book(1, groupName = "科幻")
        bookDao.books += book(2, groupName = "科幻")
        bookDao.books += book(3, groupName = "历史")
        bookDao.books += book(4, groupName = null)
    }

    @Test
    fun `分组名列表去重且不包含未分组`() = runBlocking {
        assertEquals(listOf("历史", "科幻"), repository.observeGroupNames().first())
    }

    @Test
    fun `按分组过滤仅返回该分组书籍`() = runBlocking {
        val scifi = repository.observeBookshelfWithProgressInGroup("科幻").first()
        assertEquals(listOf(1L, 2L), scifi.map { it.book.id }.sorted())

        val ungrouped = repository.observeBookshelfWithProgressInGroup(null).first()
        assertEquals(listOf(4L), ungrouped.map { it.book.id })
    }

    @Test
    fun `批量移动到分组只影响目标书籍`() = runBlocking {
        repository.updateGroup(listOf(3L, 4L), "科幻")

        assertEquals("科幻", bookDao.books.first { it.id == 3L }.groupName)
        assertEquals("科幻", bookDao.books.first { it.id == 4L }.groupName)
        assertEquals(listOf(1L, 2L, 3L, 4L), bookDao.books.filter { it.groupName == "科幻" }.map { it.id })
    }

    @Test
    fun `传入空白分组名视为移出分组`() = runBlocking {
        repository.updateGroup(listOf(1L), "  ")

        assertNull(bookDao.books.first { it.id == 1L }.groupName)
    }

    @Test
    fun `移出分组将分组名置空`() = runBlocking {
        repository.updateGroup(listOf(1L, 2L), null)

        assertNull(bookDao.books.first { it.id == 1L }.groupName)
        assertNull(bookDao.books.first { it.id == 2L }.groupName)
        assertEquals("历史", bookDao.books.first { it.id == 3L }.groupName)
    }

    @Test
    fun `空 id 列表不触发更新`() = runBlocking {
        repository.updateGroup(emptyList(), "科幻")

        assertEquals(0, bookDao.updateGroupCalls)
    }

    @Test
    fun `删除分组将该分组所有书移出`() = runBlocking {
        repository.clearGroup("科幻")

        assertNull(bookDao.books.first { it.id == 1L }.groupName)
        assertNull(bookDao.books.first { it.id == 2L }.groupName)
        assertEquals("历史", bookDao.books.first { it.id == 3L }.groupName)
        assertEquals(listOf("历史"), repository.observeGroupNames().first())
    }

    private fun book(id: Long, groupName: String?) = BookEntity(
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
        groupName = groupName,
    )

    private class FakeBookDao : BookDao {
        val books = mutableListOf<BookEntity>()
        var updateGroupCalls = 0
        override fun observeBookshelf(): Flow<List<BookEntity>> = flowOf(books)
        override fun observeBookshelfWithProgress(): Flow<List<BookWithProgress>> = flowOf(emptyList())
        override fun observeGroupNames(): Flow<List<String>> =
            flowOf(books.mapNotNull { it.groupName }.distinct().sorted())
        override fun observeBookshelfWithProgressInGroup(groupName: String?): Flow<List<BookWithProgress>> =
            flowOf(
                books.filter { it.groupName == groupName }
                    .map { BookWithProgress(book = it, charOffset = null) },
            )
        override suspend fun updateGroup(bookIds: List<Long>, groupName: String?) {
            updateGroupCalls++
            books.replaceAll { if (it.id in bookIds) it.copy(groupName = groupName) else it }
        }
        override suspend fun clearGroup(groupName: String) {
            books.replaceAll { if (it.groupName == groupName) it.copy(groupName = null) else it }
        }
        override suspend fun getById(bookId: Long): BookEntity? = books.find { it.id == bookId }
        override fun observeById(bookId: Long): Flow<BookEntity?> = flowOf(books.find { it.id == bookId })
        override suspend fun updateEncoding(bookId: Long, encoding: String) {
            books.replaceAll { if (it.id == bookId) it.copy(encoding = encoding) else it }
        }
        override suspend fun getByFileUri(fileUri: String): BookEntity? = null
        override suspend fun getByContentHash(contentHash: String): BookEntity? = null
        override suspend fun upsert(book: BookEntity): Long = book.id
        override suspend fun update(book: BookEntity) = Unit
        override suspend fun touchLastRead(bookId: Long, timestamp: Long) = Unit
        override suspend fun deleteByIds(bookIds: List<Long>) {
            books.removeAll { it.id in bookIds }
        }
    }

    private class FakeProgressDao : ReadingProgressDao {
        override suspend fun get(bookId: Long): ReadingProgressEntity? = null
        override fun observe(bookId: Long): Flow<ReadingProgressEntity?> = flowOf(null)
        override suspend fun upsert(progress: ReadingProgressEntity) = Unit
        override suspend fun delete(bookId: Long) = Unit
        override suspend fun deleteByBookIds(bookIds: List<Long>) = Unit
    }

    private class FakeChapterDao : ChapterDao {
        override suspend fun getForBook(bookId: Long): List<ChapterEntity> = emptyList()
        override fun observeForBook(bookId: Long): Flow<List<ChapterEntity>> = flowOf(emptyList())
        override suspend fun upsertAll(chapters: List<ChapterEntity>) = Unit
        override suspend fun deleteForBook(bookId: Long) = Unit
    }

    private class FakeBookmarkDao : BookmarkDao {
        override fun observeByBook(bookId: Long): Flow<List<BookmarkEntity>> = flowOf(emptyList())
        override fun observeAll(): Flow<List<BookmarkEntity>> = flowOf(emptyList())
        override suspend fun insert(bookmark: BookmarkEntity): Long = bookmark.id
        override suspend fun update(bookmark: BookmarkEntity) = Unit
        override suspend fun deleteById(id: Long) = Unit
        override suspend fun deleteByBookIds(bookIds: List<Long>) = Unit
    }

    private class FakeAnnotationDao : AnnotationDao {
        override fun observeByBook(bookId: Long): Flow<List<AnnotationEntity>> = flowOf(emptyList())
        override fun observeAll(): Flow<List<AnnotationEntity>> = flowOf(emptyList())
        override suspend fun insert(annotation: AnnotationEntity): Long = annotation.id
        override suspend fun update(annotation: AnnotationEntity) = Unit
        override suspend fun deleteById(id: Long) = Unit
        override suspend fun deleteByBookIds(bookIds: List<Long>) = Unit
    }

    private class FakeSessionDao : ReadingSessionDao {
        override suspend fun get(bookId: Long, dayStartMs: Long): ReadingSessionEntity? = null
        override suspend fun upsert(session: ReadingSessionEntity) = Unit
        override suspend fun getBetween(startMs: Long, endMs: Long): List<ReadingSessionEntity> = emptyList()
        override suspend fun getAll(): List<ReadingSessionEntity> = emptyList()
        override suspend fun countReadingDays(bookId: Long): Int = 0
    }
}

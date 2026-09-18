package com.llzx373.foldreader.feature.importer

import com.llzx373.foldreader.core.data.db.AnnotationEntity
import com.llzx373.foldreader.core.data.db.BookEntity
import com.llzx373.foldreader.core.data.db.BookFormat
import com.llzx373.foldreader.core.data.db.BookWithProgress
import com.llzx373.foldreader.core.data.db.BookmarkEntity
import com.llzx373.foldreader.core.data.db.ReadingProgressEntity
import com.llzx373.foldreader.core.data.db.ReadingSessionEntity
import com.llzx373.foldreader.core.data.repository.BookshelfRepository
import com.llzx373.foldreader.core.format.Chapter
import com.llzx373.foldreader.core.format.ContentHasher
import com.llzx373.foldreader.core.format.TextCleaner
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportBookCleanTest {

    private val tsMap = mapOf('開' to '开', '廣' to '广', '體' to '体')

    private fun newUseCase(
        repo: FakeBookshelfRepository,
        cleanedDir: File,
        sources: Map<String, File>,
    ) = ImportBookUseCase(
        bookshelfRepository = repo,
        cleanedDir = cleanedDir,
        openChannel = { key -> RandomAccessFile(sources.getValue(key), "r").channel },
        displayNameOf = { key -> File(key).name },
        traditionalMap = { tsMap },
    )

    private fun newSource(dir: File, name: String, text: String): String {
        val file = File(dir, name)
        file.writeText(text, Charsets.UTF_8)
        return file.absolutePath
    }

    private fun hashOf(file: File): String {
        val bytes = file.readBytes()
        return ContentHasher.hash(bytes.size.toLong()) { offset, length ->
            bytes.copyOfRange(offset.toInt(), offset.toInt() + length)
        }
    }

    @Test
    fun `无清理选项走原有路径不写副本`() = runBlocking {
        val dir = Files.createTempDirectory("foldreader-import").toFile()
        val repo = FakeBookshelfRepository()
        val sources = mutableMapOf<String, File>()
        val uriKey = newSource(dir, "书.txt", "第一行\n\n第二行\n").also {
            sources[it] = File(it)
        }
        val useCase = newUseCase(repo, File(dir, "cleaned"), sources)

        val result = useCase.import(uriKey, TextCleaner.CleanOptions())

        assertTrue(result is ImportBookUseCase.Result.Imported)
        val book = repo.books.single()
        assertNull(book.cleanedFilePath)
        assertEquals("UTF-8", book.encoding)
        assertEquals(hashOf(File(uriKey)), book.contentHash)
        assertTrue(File(dir, "cleaned").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `有清理选项时物化 UTF-8 副本且哈希按清洗产物计算`() = runBlocking {
        val dir = Files.createTempDirectory("foldreader-import").toFile()
        val repo = FakeBookshelfRepository()
        val sources = mutableMapOf<String, File>()
        val uriKey = newSource(
            dir,
            "书.txt",
            "第一章 開篇\n\n廣告：本章完\n身體第二行\n",
        ).also { sources[it] = File(it) }
        val useCase = newUseCase(repo, File(dir, "cleaned"), sources)
        val options = TextCleaner.CleanOptions(
            removeBlankLines = true,
            adPatterns = listOf(Regex("广告")),
            traditionalToSimplified = true,
        )
        val progresses = mutableListOf<Float>()

        val result = useCase.import(uriKey, options) { progresses += it }

        assertTrue(result is ImportBookUseCase.Result.Imported)
        val book = repo.books.single()
        assertEquals("UTF-8", book.encoding)
        val cleaned = File(book.cleanedFilePath!!)
        assertTrue(cleaned.exists())
        assertEquals("第一章 开篇\n身体第二行\n", cleaned.readText(Charsets.UTF_8))
        assertEquals(hashOf(cleaned), book.contentHash)
        assertNotEquals(hashOf(File(uriKey)), book.contentHash)
        assertEquals(1f, progresses.last())
    }

    @Test
    fun `同一原文件原版与清洗版视为两本书且重复导入被去重`() = runBlocking {
        val dir = Files.createTempDirectory("foldreader-import").toFile()
        val repo = FakeBookshelfRepository()
        val sources = mutableMapOf<String, File>()
        val uriKey = newSource(dir, "书.txt", "第一章 開篇\n正文\n").also {
            sources[it] = File(it)
        }
        val useCase = newUseCase(repo, File(dir, "cleaned"), sources)
        val clean = TextCleaner.CleanOptions(traditionalToSimplified = true)

        val raw = useCase.import(uriKey, TextCleaner.CleanOptions())
        val cleaned = useCase.import(uriKey, clean)
        val cleanedAgain = useCase.import(uriKey, clean)
        val rawAgain = useCase.import(uriKey, TextCleaner.CleanOptions())

        assertTrue(raw is ImportBookUseCase.Result.Imported)
        assertTrue(cleaned is ImportBookUseCase.Result.Imported)
        assertEquals(2, repo.books.size)
        assertNotEquals(
            (raw as ImportBookUseCase.Result.Imported).bookId,
            (cleaned as ImportBookUseCase.Result.Imported).bookId,
        )
        assertTrue(cleanedAgain is ImportBookUseCase.Result.DuplicateSameHash)
        assertTrue(rawAgain is ImportBookUseCase.Result.DuplicateSameUri)
    }

    private class FakeBookshelfRepository : BookshelfRepository {
        val books = mutableListOf<BookEntity>()
        private var nextId = 1L

        override fun observeBookshelf(): Flow<List<BookEntity>> = flowOf(books)
        override fun observeBookshelfWithProgress(): Flow<List<BookWithProgress>> = flowOf(emptyList())
        override suspend fun getBook(bookId: Long): BookEntity? = books.find { it.id == bookId }
        override fun observeBook(bookId: Long): Flow<BookEntity?> = flowOf(books.find { it.id == bookId })
        override suspend fun updateEncoding(bookId: Long, encoding: String) = Unit
        override suspend fun findByFileUri(fileUri: String): BookEntity? =
            books.find { it.fileUri == fileUri }
        override suspend fun findByContentHash(contentHash: String): BookEntity? =
            books.find { it.contentHash == contentHash }
        override suspend fun upsertBook(book: BookEntity): Long {
            val id = if (book.id == 0L) nextId++ else book.id
            books.removeAll { it.id == id }
            books += book.copy(id = id)
            return id
        }
        override suspend fun touchLastRead(bookId: Long, timestamp: Long) = Unit
        override suspend fun markContentPrepared(bookId: Long, timestamp: Long) = Unit
                override suspend fun backfillPdfMetadata(
            bookId: Long,
            title: String?,
            author: String?,
            description: String?,
            subjects: String?,
        ) = Unit
        override suspend fun updateComicPageCount(bookId: Long, pageCount: Int) = Unit
        override suspend fun updateCoverPath(bookId: Long, coverPath: String?) = Unit
        override suspend fun updateComicLocalPath(bookId: Long, localPath: String?) = Unit
        override suspend fun updateConvertedFile(bookId: Long, cleanedFilePath: String?, totalChars: Long) = Unit
        override suspend fun deleteBooks(bookIds: List<Long>, deleteLocalData: Boolean) = Unit
        override fun observeGroupNames(): Flow<List<String>> = flowOf(emptyList())
        override fun observeBookshelfWithProgressInGroup(groupName: String?): Flow<List<BookWithProgress>> =
            flowOf(emptyList())
        override suspend fun updateGroup(bookIds: List<Long>, groupName: String?) = Unit
        override suspend fun clearGroup(groupName: String) = Unit
        override fun observeProgress(bookId: Long): Flow<ReadingProgressEntity?> = flowOf(null)
        override suspend fun getProgress(bookId: Long): ReadingProgressEntity? = null
        override suspend fun saveProgress(progress: ReadingProgressEntity) = Unit
        override suspend fun getChapters(bookId: Long): List<Chapter> = emptyList()
        override fun observeChapters(bookId: Long): Flow<List<Chapter>> = flowOf(emptyList())
        override suspend fun saveChapters(bookId: Long, chapters: List<Chapter>) = Unit
        override fun observeBookmarks(bookId: Long): Flow<List<BookmarkEntity>> = flowOf(emptyList())
        override fun observeAllBookmarks(): Flow<List<BookmarkEntity>> = flowOf(emptyList())
        override suspend fun addBookmark(bookmark: BookmarkEntity): Long = 0
        override suspend fun renameBookmark(bookmark: BookmarkEntity) = Unit
        override suspend fun deleteBookmark(id: Long) = Unit
        override fun observeAnnotations(bookId: Long): Flow<List<AnnotationEntity>> = flowOf(emptyList())
        override fun observeAllAnnotations(): Flow<List<AnnotationEntity>> = flowOf(emptyList())
        override suspend fun addAnnotation(annotation: AnnotationEntity): Long = 0
        override suspend fun updateAnnotation(annotation: AnnotationEntity) = Unit
        override suspend fun deleteAnnotation(id: Long) = Unit
        override suspend fun addReadingSession(bookId: Long, dayStartMs: Long, deltaMs: Long) = Unit
        override suspend fun getReadingSessionsBetween(startMs: Long, endMs: Long): List<ReadingSessionEntity> =
            emptyList()
        override suspend fun getReadingDayCount(bookId: Long): Int = 0
    }
}

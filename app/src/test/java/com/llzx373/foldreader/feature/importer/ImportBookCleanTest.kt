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
import com.llzx373.foldreader.core.format.clean.CleanLevel
import com.llzx373.foldreader.core.format.clean.CleanProfile
import com.llzx373.foldreader.core.format.clean.CleanToggles
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportBookCleanTest {

    private val tsMap = mapOf('開' to '开', '廣' to '广', '體' to '体')

    private fun newUseCase(
        repo: FakeBookshelfRepository,
        cleanedDir: File,
        sources: Map<String, File>,
        sourceDir: File = File(cleanedDir.parentFile, "source"),
    ) = ImportBookUseCase(
        bookshelfRepository = repo,
        cleanedDir = cleanedDir,
        sourceDir = sourceDir,
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

    /** 库里的正文来源必须是指向私有 source 目录的一份副本，且与源文件逐字节相同。 */
    private fun assertSourceCopy(fileUri: String, dir: File, source: File) {
        val copy = File(fileUri.removePrefix("file://"))
        assertEquals(File(dir, "source"), copy.parentFile)
        assertTrue("源文件副本不存在：${copy.absolutePath}", copy.isFile)
        assertArrayEquals(source.readBytes(), copy.readBytes())
    }

    @Test
    fun `无清理选项不写清洗副本 但仍复制一份源文件`() = runBlocking {
        val dir = Files.createTempDirectory("foldreader-import").toFile()
        val repo = FakeBookshelfRepository()
        val sources = mutableMapOf<String, File>()
        val uriKey = newSource(dir, "书.txt", "第一行\n\n第二行\n").also {
            sources[it] = File(it)
        }
        val useCase = newUseCase(repo, File(dir, "cleaned"), sources)

        val result = useCase.import(uriKey, CleanProfile.NONE)

        assertTrue(result is ImportBookUseCase.Result.Imported)
        val book = repo.books.single()
        assertNull(book.cleanedFilePath)
        assertEquals("UTF-8", book.encoding)
        assertEquals(hashOf(File(uriKey)), book.contentHash)
        assertTrue(File(dir, "cleaned").listFiles().orEmpty().isEmpty())
        // 正文来源是私有目录里的源副本，不是外部 URI——外部授权（如「打开方式」给的临时授权）
        // 一失效，引用它的那本书就打不开了
        assertSourceCopy(book.fileUri, dir, File(uriKey))
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
        val profile = CleanProfile(
            level = CleanLevel.STANDARD,
            toggles = CleanToggles.preset(CleanLevel.STANDARD).copy(traditionalToSimplified = true),
            adPatterns = listOf(Regex("广告")),
        )
        val progresses = mutableListOf<Float>()

        val result = useCase.import(uriKey, profile) { progresses += it }

        assertTrue("导入未成功：$result", result is ImportBookUseCase.Result.Imported)
        // 选了清理：原版与清洗版各占一行，导入返回（并打开）的是清洗版
        val importedId = (result as ImportBookUseCase.Result.Imported).bookId
        assertEquals(2, repo.books.size)
        val book = repo.books.single { it.id == importedId }
        assertEquals("UTF-8", book.encoding)
        val cleaned = File(book.cleanedFilePath!!)
        assertTrue(cleaned.exists())
        assertEquals("第一章 开篇\n\n身体第二行\n", cleaned.readText(Charsets.UTF_8))
        assertEquals(hashOf(cleaned), book.contentHash)
        assertNotEquals(hashOf(File(uriKey)), book.contentHash)
        // 清洗版读清洗副本，但它重洗时要读的是**原文**，所以源副本同样要留在私有目录里
        assertSourceCopy(book.fileUri, dir, File(uriKey))
        assertEquals(1f, progresses.last())
        val report = (result as ImportBookUseCase.Result.Imported).cleanReport
        assertTrue(report != null && report.removedNoiseLines == 1)
    }

    @Test
    fun `选清理导入时原版与清洗版各占一行`() = runBlocking {
        val dir = Files.createTempDirectory("foldreader-import").toFile()
        val repo = FakeBookshelfRepository()
        val sources = mutableMapOf<String, File>()
        val uriKey = newSource(dir, "书.txt", "第一章 開篇\n\n廣告：本章完\n身體第二行\n").also {
            sources[it] = File(it)
        }
        val useCase = newUseCase(repo, File(dir, "cleaned"), sources)
        val profile = CleanProfile(
            level = CleanLevel.CUSTOM,
            toggles = CleanToggles.NONE.copy(traditionalToSimplified = true),
        )

        val result = useCase.import(uriKey, profile)

        // 用户在书架上直接看到两条，自己挑看「原文」还是「清洗后」——不需要先导入一次再重洗
        assertTrue("导入未成功：$result", result is ImportBookUseCase.Result.Imported)
        val imported = result as ImportBookUseCase.Result.Imported
        val importedId = imported.bookId
        assertEquals(2, repo.books.size)
        val cleaned = repo.books.single { it.id == importedId }
        val original = repo.books.single { it.id != importedId }
        assertNotNull("导入返回的应当是清洗版", cleaned.cleanedFilePath)
        assertNull("原版那一行不该有清洗副本", original.cleanedFilePath)
        // 原版那一行要一并带出去，批量导入才能把两行都归进同一组
        assertEquals(original.id, imported.originalBookId)
        // 两本共用同一份源副本，所以 fileUri 相同（这也正是取正文必须按 bookId 的原因）
        assertEquals(original.fileUri, cleaned.fileUri)
        assertEquals(hashOf(File(uriKey)), original.contentHash)
        assertNotEquals(original.contentHash, cleaned.contentHash)
        // 原版保留原始编码，只有清洗副本是 UTF-8
        assertEquals("UTF-8", original.encoding)
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
        val clean = CleanProfile(
            level = CleanLevel.CUSTOM,
            toggles = CleanToggles.NONE.copy(traditionalToSimplified = true),
        )

        val raw = useCase.import(uriKey, CleanProfile.NONE)
        val cleaned = useCase.import(uriKey, clean)
        val cleanedAgain = useCase.import(uriKey, clean)
        val rawAgain = useCase.import(uriKey, CleanProfile.NONE)

        assertTrue(raw is ImportBookUseCase.Result.Imported)
        assertTrue("清洗版导入未成功：$cleaned", cleaned is ImportBookUseCase.Result.Imported)
        assertEquals(2, repo.books.size)
        assertNotEquals(
            (raw as ImportBookUseCase.Result.Imported).bookId,
            (cleaned as ImportBookUseCase.Result.Imported).bookId,
        )
        // 重复导入同一档位：两行都已在架上，直接把已有的清洗版打开（不再提示「已在书架」）
        assertTrue("重复导入应打开已有清洗版：$cleanedAgain", cleanedAgain is ImportBookUseCase.Result.Imported)
        assertEquals(
            (cleaned as ImportBookUseCase.Result.Imported).bookId,
            (cleanedAgain as ImportBookUseCase.Result.Imported).bookId,
        )
        assertEquals(2, repo.books.size)
        // 「不清理」那一支仍然按内容去重，不会因为多插了一行原版而改变
        // （"同一路径"那支只留给漫画/PDF——它们仍然直接引用外部源）
        assertTrue(rawAgain is ImportBookUseCase.Result.DuplicateSameHash)
        // 两本共用同一份源副本（副本按原文哈希命名），所以它们的 fileUri 相同——
        // 这也正是「取正文必须按 bookId、不能按 fileUri 反查」的原因
        assertEquals(1, repo.books.map { it.fileUri }.distinct().size)
    }

    @Test
    fun `预览只回报告且不落盘不写库`() = runBlocking {
        val dir = Files.createTempDirectory("foldreader-import").toFile()
        val repo = FakeBookshelfRepository()
        val sources = mutableMapOf<String, File>()
        val uriKey = newSource(
            dir,
            "书.txt",
            "请记住本站域名 www.example-novel.com\n他停下脚步，　看着远方。\n",
        ).also { sources[it] = File(it) }
        val useCase = newUseCase(repo, File(dir, "cleaned"), sources)

        val report = useCase.preview(uriKey, CleanProfile(level = CleanLevel.STANDARD))

        assertTrue(report.removedNoiseLines == 1)
        assertTrue(report.changed)
        assertTrue(report.samples.isNotEmpty())
        assertTrue(repo.books.isEmpty())
        assertTrue(File(dir, "cleaned").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `不清理时预览不做任何事`() = runBlocking {
        val dir = Files.createTempDirectory("foldreader-import").toFile()
        val repo = FakeBookshelfRepository()
        val sources = mutableMapOf<String, File>()
        val uriKey = newSource(dir, "书.txt", "请记住本站域名 www.example-novel.com\n").also {
            sources[it] = File(it)
        }
        val useCase = newUseCase(repo, File(dir, "cleaned"), sources)

        val report = useCase.preview(uriKey, CleanProfile.NONE)

        assertFalse(report.changed)
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

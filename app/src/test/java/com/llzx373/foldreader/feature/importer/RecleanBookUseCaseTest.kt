package com.llzx373.foldreader.feature.importer

import com.llzx373.foldreader.core.data.db.BookEntity
import com.llzx373.foldreader.core.data.db.BookFormat
import com.llzx373.foldreader.core.data.repository.FakeBookshelfRepository
import com.llzx373.foldreader.core.format.OffsetIndexBlock
import com.llzx373.foldreader.core.format.OffsetIndexSnapshot
import com.llzx373.foldreader.core.format.OffsetIndexStore
import com.llzx373.foldreader.core.format.clean.CleanLevel
import com.llzx373.foldreader.core.format.clean.CleanProfile
import com.llzx373.foldreader.core.reader.PageDiskCache
import com.llzx373.foldreader.core.reader.PaginatorKey
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.Charset
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecleanBookUseCaseTest {

    private val GBK_SAMPLE = buildString {
        repeat(20) { i -> append("第").append(i).append("章 他停下脚步，看着远方，心里想着许多年前的旧事。\n") }
    }

    private class RecordingOffsetIndexStore : OffsetIndexStore {
        val invalidated = mutableListOf<String>()

        override suspend fun load(key: String): OffsetIndexSnapshot? = null

        override suspend fun save(key: String, snapshot: OffsetIndexSnapshot) = Unit

        override suspend fun loadValid(
            key: String,
            fileLength: Long,
            contentHash: String,
            charsetName: String,
        ): OffsetIndexSnapshot? = null

        override suspend fun saveValid(
            key: String,
            snapshot: OffsetIndexSnapshot,
            fileLength: Long,
            contentHash: String,
            charsetName: String,
        ) = Unit

        override suspend fun begin(key: String) = Unit

        override suspend fun appendBlocks(key: String, blocks: List<OffsetIndexBlock>) = Unit

        override suspend fun complete(
            key: String,
            fileLength: Long,
            contentHash: String,
            charsetName: String,
            totalChars: Long,
        ) = Unit

        override suspend fun invalidate(key: String) {
            invalidated += key
        }
    }

    private class RecordingPageDiskCache : PageDiskCache {
        val deletedForBook = mutableListOf<Long>()

        override fun load(key: PaginatorKey, charCount: Long): LongArray? = null

        override fun save(key: PaginatorKey, charCount: Long, bounds: LongArray) = Unit

        override fun append(
            key: PaginatorKey,
            charCount: Long,
            allBounds: LongArray,
            persistedCount: Int,
        ): Boolean = false

        override fun deleteForBook(bookId: Long) {
            deletedForBook += bookId
        }
    }

    private class Fixture {
        val dir: File = Files.createTempDirectory("foldreader-reclean").toFile()
        val cleanedDir = File(dir, "cleaned").apply { mkdirs() }
        val repo = FakeBookshelfRepository()
        val indexStore = RecordingOffsetIndexStore()
        val pageCache = RecordingPageDiskCache()

        fun source(name: String, text: String, charset: java.nio.charset.Charset = Charsets.UTF_8): String {
            val file = File(dir, name)
            file.writeBytes(text.toByteArray(charset))
            return file.absolutePath
        }

        fun useCase(tsMap: Map<Char, Char> = emptyMap()) = RecleanBookUseCase(
            bookshelfRepository = repo,
            cleanedDir = cleanedDir,
            openChannel = { key -> RandomAccessFile(File(key), "r").channel },
            offsetIndexStore = indexStore,
            pageDiskCache = pageCache,
            traditionalMap = { tsMap },
        )

        fun addBook(
            uriKey: String,
            cleanedFilePath: String? = null,
            format: BookFormat = BookFormat.TXT,
        ): Long = runBlocking {
            repo.upsertBook(
                BookEntity(
                    title = "书",
                    author = null,
                    fileUri = uriKey,
                    contentHash = "原始哈希",
                    format = format,
                    totalChars = 123,
                    encoding = Charsets.UTF_8.name(),
                    importedAt = 1L,
                    lastReadAt = null,
                    cleanedFilePath = cleanedFilePath,
                ),
            )
        }
    }

    @Test
    fun `重新清洗读原始文件、写新副本并作废派生缓存`() = runBlocking {
        val f = Fixture()
        val uriKey = f.source("书.txt", "第一章 開篇\n\n廣告：本章完\n身體第二行\n")
        val stale = File(f.cleanedDir, "stale.txt").apply { writeText("旧副本", Charsets.UTF_8) }
        val bookId = f.addBook(uriKey, cleanedFilePath = stale.absolutePath)

        val outcome = f.useCase(mapOf('開' to '开', '廣' to '广', '體' to '体')).reclean(
            bookId,
            CleanProfile(
                level = CleanLevel.STANDARD,
                toggles = CleanProfile(level = CleanLevel.STANDARD).toggles.copy(traditionalToSimplified = true),
                adPatterns = listOf(Regex("广告")),
            ),
        )

        assertTrue(outcome is RecleanBookUseCase.Outcome.Done)
        assertTrue((outcome as RecleanBookUseCase.Outcome.Done).changed)

        val book = f.repo.getBook(bookId)!!
        val newCopy = File(book.cleanedFilePath!!)
        assertTrue(newCopy.isFile)
        assertNotEquals(stale.absolutePath, newCopy.absolutePath)
        assertEquals("第一章 开篇\n\n身体第二行\n", newCopy.readText(Charsets.UTF_8))
        // 副本恒为 UTF-8，字数归零等下次打开重算
        assertEquals(Charsets.UTF_8.name(), book.encoding)
        assertEquals(0L, book.totalChars)
        // contentHash 是这本书的稳定身份（备份匹配用），不随内容改写
        assertEquals("原始哈希", book.contentHash)
        // 按字符偏移派生的缓存必须作废
        assertEquals(listOf(bookId.toString()), f.indexStore.invalidated)
        assertEquals(listOf(bookId), f.pageCache.deletedForBook)
        // 旧副本成孤儿，删掉
        assertFalse(stale.exists())
    }

    @Test
    fun `内容没有变化时不替换副本`() = runBlocking {
        val f = Fixture()
        val uriKey = f.source("书.txt", "他停下脚步，看着远方。\n风从窗外吹进来。\n她站在门口。\n")
        val bookId = f.addBook(uriKey)
        val useCase = f.useCase()
        val profile = CleanProfile(level = CleanLevel.STANDARD)

        val first = useCase.reclean(bookId, profile)
        val firstCopy = f.repo.getBook(bookId)!!.cleanedFilePath

        val second = useCase.reclean(bookId, profile)

        assertTrue((first as RecleanBookUseCase.Outcome.Done).changed)
        assertFalse((second as RecleanBookUseCase.Outcome.Done).changed)
        assertEquals(firstCopy, f.repo.getBook(bookId)!!.cleanedFilePath)
    }

    @Test
    fun `反复换档位重洗都基于原始文件`() = runBlocking {
        val f = Fixture()
        val source = "第一章 開篇\n\n廣告：本章完\n身體第二行\n"
        val uriKey = f.source("书.txt", source)
        val bookId = f.addBook(uriKey)
        val useCase = f.useCase(mapOf('開' to '开', '廣' to '广', '體' to '体'))
        val conservative = CleanProfile(level = CleanLevel.CONSERVATIVE)
        val standard = CleanProfile(level = CleanLevel.STANDARD)

        val first = useCase.reclean(bookId, conservative)
        val afterFirst = f.repo.getBook(bookId)!!.cleanedFilePath!!
        val second = useCase.reclean(bookId, standard)
        val afterSecond = f.repo.getBook(bookId)!!.cleanedFilePath!!
        val third = useCase.reclean(bookId, conservative)
        val afterThird = f.repo.getBook(bookId)!!.cleanedFilePath!!

        assertTrue((first as RecleanBookUseCase.Outcome.Done).changed)
        assertTrue((second as RecleanBookUseCase.Outcome.Done).changed)
        // 换回上一套规则时，结果必须与第一次完全相同——因为每次读的都是同一个原文
        assertEquals(afterFirst, afterThird)
        assertEquals(File(afterFirst).readText(Charsets.UTF_8), File(afterThird).readText(Charsets.UTF_8))
        // 标准档洗出来的副本与保守档不同
        assertNotEquals(afterFirst, afterSecond)
        assertTrue((third as RecleanBookUseCase.Outcome.Done).changed)
        // 中间那版副本被当孤儿清掉了，cleaned/ 里只留当前这一份
        assertFalse(File(afterSecond).exists())
        assertEquals(1, f.cleanedDir.listFiles().orEmpty().count { it.name.endsWith(".txt") })
    }

    @Test
    fun `不清理等价于撤销清理并恢复原始编码`() = runBlocking {
        val f = Fixture()
        // 原文用 GBK：撤销清理必须把编码恢复成 GBK，否则原文件会被按 UTF-8 解码成乱码
        val uriKey = f.source("书.txt", GBK_SAMPLE, Charset.forName("GBK"))
        val bookId = f.addBook(uriKey)
        val useCase = f.useCase()

        useCase.reclean(bookId, CleanProfile(level = CleanLevel.STANDARD))
        val copy = f.repo.getBook(bookId)!!.cleanedFilePath!!
        assertTrue(File(copy).exists())
        assertEquals(Charsets.UTF_8.name(), f.repo.getBook(bookId)!!.encoding)

        val outcome = useCase.reclean(bookId, CleanProfile.NONE)

        assertTrue((outcome as RecleanBookUseCase.Outcome.Done).changed)
        val book = f.repo.getBook(bookId)!!
        assertNull(book.cleanedFilePath)
        assertEquals("GBK", book.encoding)
        assertEquals(0L, book.totalChars)
        assertFalse(File(copy).exists())
        // 一次清洗 + 一次撤销，两次都要作废派生缓存
        assertEquals(2, f.indexStore.invalidated.size)
        assertEquals(2, f.pageCache.deletedForBook.size)
    }

    @Test
    fun `本来就未清洗时撤销清理不改动任何东西`() = runBlocking {
        val f = Fixture()
        val uriKey = f.source("书.txt", "正文\n")
        val bookId = f.addBook(uriKey)

        val outcome = f.useCase().reclean(bookId, CleanProfile.NONE)

        assertFalse((outcome as RecleanBookUseCase.Outcome.Done).changed)
        assertEquals(0, f.indexStore.invalidated.size)
    }

    @Test
    fun `非 TXT 书籍拒绝智能整理`() = runBlocking {
        val f = Fixture()
        val uriKey = f.source("书.epub", "第一章\n正文\n")
        val bookId = f.addBook(uriKey, format = BookFormat.EPUB)

        val outcome = f.useCase().reclean(bookId, CleanProfile(level = CleanLevel.STANDARD))

        assertTrue(outcome is RecleanBookUseCase.Outcome.Failure)
        assertEquals(0, f.indexStore.invalidated.size)
    }

    @Test
    fun `没有启用任何规则时等同于撤销清理`() = runBlocking {
        val f = Fixture()
        val uriKey = f.source("书.txt", "正文\n")
        val bookId = f.addBook(uriKey)

        val outcome = f.useCase().reclean(bookId, CleanProfile.NONE)

        assertTrue(outcome is RecleanBookUseCase.Outcome.Done)
        assertFalse((outcome as RecleanBookUseCase.Outcome.Done).changed)
    }
}
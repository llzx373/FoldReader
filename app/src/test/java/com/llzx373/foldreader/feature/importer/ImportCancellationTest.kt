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
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导入/重洗中途取消不是「失败」：CancellationException 必须上抛（由批量层记为取消），
 * 不能被 runCatching/catch 吞成 Result.Failure 或半截书架记录。
 */
class ImportCancellationTest {

    private class NoopOffsetIndexStore : OffsetIndexStore {
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

        override suspend fun invalidate(key: String) = Unit
    }

    private class NoopPageDiskCache : PageDiskCache {
        override fun load(key: PaginatorKey, charCount: Long): LongArray? = null
        override fun save(key: PaginatorKey, charCount: Long, bounds: LongArray) = Unit
        override fun append(key: PaginatorKey, charCount: Long, allBounds: LongArray, persistedCount: Int) = false
        override fun deleteForBook(bookId: Long) = Unit
    }

    private fun newSource(dir: File, name: String, text: String): String {
        val file = File(dir, name)
        file.writeText(text, Charsets.UTF_8)
        return file.absolutePath
    }

    @Test
    fun `导入中途取消上抛且书架无半截记录`() {
        val dir = Files.createTempDirectory("foldreader-import-cancel").toFile()
        val repo = FakeBookshelfRepository()
        val uriKey = newSource(dir, "书.txt", "第一行\n\n第二行\n")
        val useCase = ImportBookUseCase(
            bookshelfRepository = repo,
            cleanedDir = File(dir, "cleaned"),
            sourceDir = File(dir, "source"),
            openChannel = { key -> RandomAccessFile(File(key), "r").channel },
            displayNameOf = { key -> File(key).name },
            traditionalMap = { emptyMap() },
        )

        // 进度回调在落库之前触发：从这里抛取消，等价于复制/清洗途中用户点了取消
        val outcome = runCatching {
            runBlocking {
                useCase.import(uriKey, CleanProfile(level = CleanLevel.STANDARD)) {
                    throw CancellationException("用户取消")
                }
            }
        }

        assertTrue(
            "取消必须上抛，实际：$outcome",
            outcome.exceptionOrNull() is CancellationException,
        )
        assertTrue("书架不得留下半截记录", repo.books.value.isEmpty())
    }

    @Test
    fun `重洗中途取消上抛且副本不被替换`() {
        val dir = Files.createTempDirectory("foldreader-reclean-cancel").toFile()
        val repo = FakeBookshelfRepository()
        val uriKey = newSource(dir, "书.txt", "第一章 起\n\n广告：本章完\n正文\n")
        val bookId = runBlocking {
            repo.upsertBook(
                BookEntity(
                    title = "书",
                    author = null,
                    fileUri = uriKey,
                    contentHash = "原始哈希",
                    format = BookFormat.TXT,
                    totalChars = 100,
                    encoding = Charsets.UTF_8.name(),
                    importedAt = 1L,
                    lastReadAt = null,
                ),
            )
        }
        val useCase = RecleanBookUseCase(
            bookshelfRepository = repo,
            cleanedDir = File(dir, "cleaned"),
            openChannel = { key -> RandomAccessFile(File(key), "r").channel },
            offsetIndexStore = NoopOffsetIndexStore(),
            pageDiskCache = NoopPageDiskCache(),
        )

        val outcome = runCatching {
            runBlocking {
                useCase.reclean(bookId, CleanProfile(level = CleanLevel.STANDARD)) {
                    throw CancellationException("用户取消")
                }
            }
        }

        assertTrue(
            "取消必须上抛，实际：$outcome",
            outcome.exceptionOrNull() is CancellationException,
        )
        assertNull("重洗取消不得改写书籍记录", runBlocking { repo.getBook(bookId) }!!.cleanedFilePath)
    }
}

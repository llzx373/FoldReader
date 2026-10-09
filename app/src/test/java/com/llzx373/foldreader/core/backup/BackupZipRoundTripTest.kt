package com.llzx373.foldreader.core.backup

import android.net.Uri
import com.llzx373.foldreader.core.data.db.BookEntity
import com.llzx373.foldreader.core.data.db.BookFormat
import com.llzx373.foldreader.core.data.db.BookmarkEntity
import com.llzx373.foldreader.core.data.db.ReadingProgressEntity
import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * v11 zip 备份（含书籍文件本体）的端到端往返：导出 → 空环境导入 → 新书落盘入架。
 * 走 BackupManager 真实链路（Robolectric 提供 file:// 的 contentResolver 读写）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BackupZipRoundTripTest {

    private val context = RuntimeEnvironment.getApplication()
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = java.nio.file.Files.createTempDirectory("backup-zip-test").toFile()
        File(context.filesDir, "source").deleteRecursively()
        File(context.filesDir, "covers").deleteRecursively()
    }

    private fun managerOf(books: FakeBookshelfRepository) = BackupManager(
        context = context,
        bookshelfRepository = books,
        settingsRepository = FakeSettingsRepository(),
        bookPrefsDao = FakeBookPrefsDao(),
        readingSessionDao = FakeSessionDao(),
    )

    @Test
    fun `zip 往返——新书文件落盘入架且进度书签恢复`() = runBlocking {
        val bookFile = File(tempDir, "hashA.txt").apply { writeText("正文内容") }
        val coverFile = File(tempDir, "cover.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val sourceBooks = FakeBookshelfRepository(
            mutableListOf(
                BookEntity(
                    id = 1,
                    title = "测试书",
                    author = "作者甲",
                    fileUri = "file://${bookFile.absolutePath}",
                    contentHash = "hashA",
                    format = BookFormat.TXT,
                    totalChars = 4,
                    encoding = "UTF-8",
                    importedAt = 100,
                    lastReadAt = 200,
                    coverPath = coverFile.absolutePath,
                ),
            ),
        )
        sourceBooks.progress += ReadingProgressEntity(
            bookId = 1,
            charOffset = 42,
            chapterIndex = 2,
            totalReadingMillis = 5000,
            firstReadAt = 10,
            updatedAt = 90,
        )
        sourceBooks.bookmarks += BookmarkEntity(
            bookId = 1,
            charOffset = 7,
            chapterIndex = 1,
            snapshotText = "快照",
            label = "书签",
            createdAt = 50,
        )

        val zipFile = File(tempDir, "backup.zip")
        val exportResult = managerOf(sourceBooks).exportTo(Uri.fromFile(zipFile))

        assertEquals(emptyList<String>(), exportResult.skippedBookTitles)
        // 包里有 manifest + 书文件 + 封面，且书记录带 archiveFile
        ZipFile(zipFile).use { zip ->
            assertNotNull(zip.getEntry("backup.json"))
            assertNotNull(zip.getEntry("files/hashA.txt"))
            assertNotNull(zip.getEntry("covers/hashA.png"))
            val manifest = zip.getInputStream(zip.getEntry("backup.json")).readBytes().toString(Charsets.UTF_8)
            val bookJson = org.json.JSONObject(manifest).getJSONArray("books").getJSONObject(0)
            assertEquals("files/hashA.txt", bookJson.getString("archiveFile"))
            assertEquals("covers/hashA.png", bookJson.getString("archiveCover"))
        }

        // 空环境导入：书被新建，文件与封面落盘，进度/书签恢复到新书的 id 上
        val targetBooks = FakeBookshelfRepository()
        val result = managerOf(targetBooks).importFrom(Uri.fromFile(zipFile))

        assertEquals(1, result.restoredBooks)
        assertEquals(1, result.createdBooks)
        assertEquals(1, result.restoredBookmarks)
        assertTrue(result.missingBookTitles.isEmpty())

        val book = targetBooks.books.single()
        assertEquals("测试书", book.title)
        assertEquals("作者甲", book.author)
        assertEquals("hashA", book.contentHash)
        assertEquals(BookFormat.TXT, book.format)

        val restoredFile = File(context.filesDir, "source/hashA.txt")
        assertTrue(restoredFile.isFile)
        assertEquals("正文内容", restoredFile.readText())
        assertEquals("file://${restoredFile.absolutePath}", book.fileUri)
        val restoredCover = File(context.filesDir, "covers/hashA.png")
        assertTrue(restoredCover.isFile)
        assertEquals(restoredCover.absolutePath, book.coverPath)

        assertEquals(42L, targetBooks.progress.single().charOffset)
        assertEquals(book.id, targetBooks.progress.single().bookId)
        assertEquals(book.id, targetBooks.bookmarks.single().bookId)
    }

    @Test
    fun `重复导入幂等——第二次走既有书匹配合并且不重复落盘`() = runBlocking {
        val bookFile = File(tempDir, "hashA.txt").apply { writeText("正文内容") }
        val sourceBooks = FakeBookshelfRepository(
            mutableListOf(
                BookEntity(
                    id = 1,
                    title = "测试书",
                    author = null,
                    fileUri = "file://${bookFile.absolutePath}",
                    contentHash = "hashA",
                    format = BookFormat.TXT,
                    totalChars = 4,
                    encoding = "UTF-8",
                    importedAt = 100,
                    lastReadAt = null,
                ),
            ),
        )
        val zipFile = File(tempDir, "backup.zip")
        managerOf(sourceBooks).exportTo(Uri.fromFile(zipFile))

        val targetBooks = FakeBookshelfRepository()
        val manager = managerOf(targetBooks)
        manager.importFrom(Uri.fromFile(zipFile))
        val second = manager.importFrom(Uri.fromFile(zipFile))

        assertEquals(1, targetBooks.books.size)
        assertEquals(1, second.restoredBooks)
        assertEquals(0, second.createdBooks)
    }

    @Test
    fun `读不到书文件时记入 skipped 且导入进缺失清单`() = runBlocking {
        val ghost = File(tempDir, "ghost.txt") // 刻意不创建
        val sourceBooks = FakeBookshelfRepository(
            mutableListOf(
                BookEntity(
                    id = 1,
                    title = "丢失的书",
                    author = null,
                    fileUri = "file://${ghost.absolutePath}",
                    contentHash = "hashG",
                    format = BookFormat.TXT,
                    totalChars = 1,
                    encoding = "UTF-8",
                    importedAt = 100,
                    lastReadAt = null,
                ),
            ),
        )
        val zipFile = File(tempDir, "backup.zip")
        val exportResult = managerOf(sourceBooks).exportTo(Uri.fromFile(zipFile))

        assertEquals(listOf("丢失的书"), exportResult.skippedBookTitles)

        val targetBooks = FakeBookshelfRepository()
        val result = managerOf(targetBooks).importFrom(Uri.fromFile(zipFile))

        assertEquals(0, result.restoredBooks)
        assertEquals(listOf("丢失的书"), result.missingBookTitles)
        assertTrue(targetBooks.books.isEmpty())
    }

    @Test
    fun `旧版纯 JSON 备份按原语义导入且可预览`() = runBlocking {
        val legacy = """
            {
              "app": "FoldReader",
              "version": 10,
              "exportedAt": 1759900000000,
              "books": [
                {"title": "书hashA", "contentHash": "hashA"}
              ]
            }
        """.trimIndent()
        val jsonFile = File(tempDir, "legacy.json").apply { writeText(legacy) }

        val targetBooks = FakeBookshelfRepository()
        val manager = managerOf(targetBooks)
        val result = manager.importFrom(Uri.fromFile(jsonFile))

        // 旧备份不含文件：未匹配的书进缺失清单，不新建
        assertEquals(0, result.restoredBooks)
        assertEquals(0, result.createdBooks)
        assertEquals(listOf("书hashA"), result.missingBookTitles)

        val preview = manager.previewFile(jsonFile)
        assertEquals(10, preview.version)
        assertEquals(1, preview.bookCount)
    }

    @Test
    fun `zip 预览只读 manifest`() = runBlocking {
        val bookFile = File(tempDir, "hashA.txt").apply { writeText("正文内容") }
        val sourceBooks = FakeBookshelfRepository(
            mutableListOf(
                BookEntity(
                    id = 1,
                    title = "测试书",
                    author = null,
                    fileUri = "file://${bookFile.absolutePath}",
                    contentHash = "hashA",
                    format = BookFormat.TXT,
                    totalChars = 4,
                    encoding = "UTF-8",
                    importedAt = 100,
                    lastReadAt = null,
                ),
            ),
        )
        val zipFile = File(tempDir, "backup.zip")
        managerOf(sourceBooks).exportTo(Uri.fromFile(zipFile))

        val preview = managerOf(FakeBookshelfRepository()).previewFile(zipFile)

        assertEquals(BackupManager.BACKUP_VERSION, preview.version)
        assertEquals(1, preview.bookCount)
        // 预览不解压文件
        assertNull(File(context.filesDir, "source/hashA.txt").takeIf { it.exists() })
    }
}

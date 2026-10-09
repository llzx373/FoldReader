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
    fun `漫画页序与译文锚点随备份往返且恢复不覆盖本机更新值`() = runBlocking {
        val bookFile = File(tempDir, "hashC.cbz").apply { writeBytes(byteArrayOf(9, 9, 9)) }
        val sourceBooks = FakeBookshelfRepository(
            mutableListOf(
                BookEntity(
                    id = 1,
                    title = "测试漫画",
                    author = null,
                    fileUri = "file://${bookFile.absolutePath}",
                    contentHash = "hashC",
                    format = BookFormat.COMIC,
                    totalChars = 0,
                    encoding = "UTF-8",
                    importedAt = 100,
                    lastReadAt = null,
                    comicPageCount = 120,
                ),
            ),
        )
        sourceBooks.progress += ReadingProgressEntity(
            bookId = 1,
            charOffset = 0,
            chapterIndex = 0,
            totalReadingMillis = 5000,
            firstReadAt = 10,
            updatedAt = 100,
            comicPage = 37,
            translationAnchor = 1234,
            charsReadTotal = 8888,
        )

        val zipFile = File(tempDir, "backup-comic.zip")
        managerOf(sourceBooks).exportTo(Uri.fromFile(zipFile))

        // 目标机：同一本书（按 contentHash 匹配），本机进度更旧且没有页式/译文锚点
        val targetBooks = FakeBookshelfRepository(
            mutableListOf(
                BookEntity(
                    id = 7,
                    title = "测试漫画",
                    author = null,
                    fileUri = "file://${bookFile.absolutePath}",
                    contentHash = "hashC",
                    format = BookFormat.COMIC,
                    totalChars = 0,
                    encoding = "UTF-8",
                    importedAt = 90,
                    lastReadAt = null,
                    comicPageCount = 120,
                ),
            ),
        )
        targetBooks.progress += ReadingProgressEntity(
            bookId = 7,
            charOffset = 0,
            chapterIndex = 0,
            totalReadingMillis = 1000,
            firstReadAt = 5,
            updatedAt = 50, // 比备份旧 → 备份生效
        )
        val targetManager = managerOf(targetBooks)
        targetManager.importFrom(Uri.fromFile(zipFile))

        val restored = targetBooks.progress.single { it.bookId == 7L }
        assertEquals(37, restored.comicPage)
        assertEquals(1234L, restored.translationAnchor)
        assertEquals(8888L, restored.charsReadTotal)
        assertEquals(5000L, restored.totalReadingMillis)

        // 本机进度更新（updatedAt 更大）后再恢复同一备份：一概不覆盖
        targetBooks.progress.removeAll { it.bookId == 7L }
        targetBooks.progress += restored.copy(updatedAt = 1000, comicPage = 5, charsReadTotal = 99999)
        targetManager.importFrom(Uri.fromFile(zipFile))

        val kept = targetBooks.progress.single { it.bookId == 7L }
        assertEquals(5, kept.comicPage)
        assertEquals(99999L, kept.charsReadTotal)
        assertEquals(1000L, kept.updatedAt)
    }

    @Test
    fun `v11 旧备份缺锚点字段时保留本机现值`() = runBlocking {
        // v11 及更早的 progress 只有 5 个字段：缺的锚点/累计字符必须保留本机现值
        val legacy = """
            {
              "app": "FoldReader",
              "version": 11,
              "books": [
                {
                  "title": "测试漫画",
                  "contentHash": "hashC",
                  "format": "COMIC",
                  "progress": {
                    "charOffset": 10,
                    "chapterIndex": 1,
                    "totalReadingMillis": 3000,
                    "firstReadAt": 8,
                    "updatedAt": 500
                  }
                }
              ]
            }
        """.trimIndent()
        val jsonFile = File(tempDir, "legacy-v11.json").apply { writeText(legacy) }

        val targetBooks = FakeBookshelfRepository(
            mutableListOf(
                BookEntity(
                    id = 7,
                    title = "测试漫画",
                    author = null,
                    fileUri = "file:///x/hashC.cbz",
                    contentHash = "hashC",
                    format = BookFormat.COMIC,
                    totalChars = 0,
                    encoding = "UTF-8",
                    importedAt = 90,
                    lastReadAt = null,
                ),
            ),
        )
        targetBooks.progress += ReadingProgressEntity(
            bookId = 7,
            charOffset = 0,
            chapterIndex = 0,
            totalReadingMillis = 1000,
            firstReadAt = 5,
            updatedAt = 400,
            comicPage = 37,
            translationAnchor = 99,
            charsReadTotal = 777,
        )
        managerOf(targetBooks).importFrom(Uri.fromFile(jsonFile))

        val merged = targetBooks.progress.single { it.bookId == 7L }
        // 备份里有的字段按备份恢复
        assertEquals(10L, merged.charOffset)
        assertEquals(500L, merged.updatedAt)
        // 备份里缺的字段保留本机现值（旧版行为会把它们清成 null/0）
        assertEquals(37, merged.comicPage)
        assertEquals(99L, merged.translationAnchor)
        assertEquals(777L, merged.charsReadTotal)
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

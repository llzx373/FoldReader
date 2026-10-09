package com.llzx373.foldreader.core.data.repository

import androidx.room.Room
import androidx.room.withTransaction
import com.llzx373.foldreader.core.data.db.AnnotationEntity
import com.llzx373.foldreader.core.data.db.BookEntity
import com.llzx373.foldreader.core.data.db.BookFormat
import com.llzx373.foldreader.core.data.db.FoldReaderDatabase
import com.llzx373.foldreader.core.data.db.GlossaryTermEntity
import com.llzx373.foldreader.core.data.db.BookmarkEntity
import com.llzx373.foldreader.core.data.db.ReadingProgressEntity
import com.llzx373.foldreader.core.data.db.TranslationEntity
import com.llzx373.foldreader.core.translate.TranslationStore
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 删书链路的真实 Room 内存库测试（A1）。
 *
 * reading_progress / bookmarks / annotations 三张子表的外键是 NO_ACTION：旧实现
 * 「不删本地数据」路径跳过子表清理后 `deleteByIds` 必抛 SQLiteConstraintException——
 * 假 DAO 测不出来，必须真库跑外键（历史教训：本测试类的前身就是假 DAO 版）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BookshelfRepositoryDeleteTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var db: FoldReaderDatabase
    private lateinit var pageDiskCache: RecordingPageDiskCache
    private lateinit var translationsDir: File
    private lateinit var translationStore: TranslationStore
    private lateinit var repository: BookshelfRepositoryImpl

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            FoldReaderDatabase::class.java,
        ).build()
        translationsDir = File(tempFolder.root, "translations")
        translationStore = TranslationStore(translationsDir)
        pageDiskCache = RecordingPageDiskCache()
        repository = repo()
        db.bookDao().upsert(book(1))
        db.bookDao().upsert(book(2))
        seedChildren(1)
        seedChildren(2)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun repo(sourceDir: File? = null) = BookshelfRepositoryImpl(
        bookDao = db.bookDao(),
        progressDao = db.readingProgressDao(),
        chapterDao = db.chapterDao(),
        bookmarkDao = db.bookmarkDao(),
        annotationDao = db.annotationDao(),
        sessionDao = db.readingSessionDao(),
        pageDiskCache = pageDiskCache,
        sourceDir = sourceDir,
        translationStore = translationStore,
        translationDao = db.translationDao(),
        glossaryTermDao = db.glossaryTermDao(),
        inTransaction = { block -> db.withTransaction { block() } },
    )

    /** 造「有进度 + 书签 + 标注 + 翻译台账 + 术语」的书（两张 NO_ACTION 子表都挂上）。 */
    private suspend fun seedChildren(bookId: Long) {
        db.readingProgressDao().upsert(
            ReadingProgressEntity(
                bookId = bookId,
                charOffset = 100,
                chapterIndex = 1,
                totalReadingMillis = 60_000,
                updatedAt = 10,
            ),
        )
        db.bookmarkDao().insert(
            BookmarkEntity(
                id = bookId,
                bookId = bookId,
                charOffset = 0,
                chapterIndex = 0,
                snapshotText = "",
                createdAt = 0,
            ),
        )
        db.annotationDao().insert(
            AnnotationEntity(
                id = bookId,
                bookId = bookId,
                startCharOffset = 0,
                endCharOffset = 1,
                selectedText = "",
                color = 0,
                note = null,
                createdAt = 0,
                updatedAt = 0,
            ),
        )
        db.translationDao().upsert(
            TranslationEntity(
                bookId = bookId,
                lang = "ZH_HANS",
                unitKind = "chapter",
                unitIndex = 0,
                status = TranslationEntity.STATUS_DONE,
                model = "m",
                paragraphCount = 3,
                updatedAt = 10,
            ),
        )
        File(translationsDir, "$bookId/ZH_HANS").mkdirs()
        File(translationsDir, "$bookId/ZH_HANS/units.json").writeText("{}")
        db.glossaryTermDao().upsert(
            GlossaryTermEntity(
                scope = GlossaryTermEntity.SCOPE_BOOK,
                ownerKey = bookId.toString(),
                source = "术语$bookId",
                target = "term$bookId",
                origin = GlossaryTermEntity.ORIGIN_USER,
                confirmed = true,
            ),
        )
    }

    @Test
    fun `不勾派生数据时进度书签标注仍随书删除且不抛约束`() = runBlocking {
        // 旧行为：deleteLocalData=false 跳过三张 NO_ACTION 子表，deleteByIds 必抛
        // SQLiteConstraintException——真库下这条路径曾经根本走不通
        repository.deleteBooks(listOf(1L), deleteLocalData = false)

        assertNull(db.bookDao().getById(1L))
        assertNull(db.readingProgressDao().get(1L))
        assertTrue(db.bookmarkDao().observeByBook(1L).first().isEmpty())
        assertTrue(db.annotationDao().observeByBook(1L).first().isEmpty())
        // 翻译台账行走 CASCADE，不勾也随书删
        assertTrue(db.translationDao().getForBook(1L, "ZH_HANS").isEmpty())
        // 但派生**文件产物**（译本副本）与单书术语是复选框管辖的：不勾就保留
        assertTrue(File(translationsDir, "1/ZH_HANS/units.json").isFile)
        assertEquals(
            1,
            db.glossaryTermDao().getAll().count {
                it.scope == GlossaryTermEntity.SCOPE_BOOK && it.ownerKey == "1"
            },
        )
        // 另一本书的行与文件一律不动
        assertEquals(listOf(2L), db.bookDao().getByIds(listOf(1L, 2L)).map { it.id })
        assertTrue(db.readingProgressDao().get(2L) != null)
        assertTrue(File(translationsDir, "2/ZH_HANS/units.json").isFile)
    }

    @Test
    fun `勾选派生数据时译本副本与单书术语一并清除`() = runBlocking {
        db.glossaryTermDao().upsert(
            GlossaryTermEntity(
                scope = GlossaryTermEntity.SCOPE_GLOBAL,
                ownerKey = "",
                source = "全局术语",
                target = "global",
                origin = GlossaryTermEntity.ORIGIN_USER,
                confirmed = true,
            ),
        )

        repository.deleteBooks(listOf(1L), deleteLocalData = true)

        assertNull(db.bookDao().getById(1L))
        assertNull(db.readingProgressDao().get(1L))
        assertFalse(File(translationsDir, "1").exists())
        assertTrue(
            db.glossaryTermDao().getAll()
                .none { it.scope == GlossaryTermEntity.SCOPE_BOOK && it.ownerKey == "1" },
        )
        // 全局术语不属于任何书，不受删书影响
        assertTrue(db.glossaryTermDao().getAll().any { it.scope == GlossaryTermEntity.SCOPE_GLOBAL })
        assertTrue(File(translationsDir, "2/ZH_HANS/units.json").isFile)
    }

    @Test
    fun `批量删除多本书时仅目标书数据被清理`() = runBlocking {
        db.bookDao().upsert(book(3))
        seedChildren(3)

        repository.deleteBooks(listOf(1L, 2L))

        assertEquals(listOf(3L), db.bookDao().getByIds(listOf(1L, 2L, 3L)).map { it.id })
        assertTrue(db.readingProgressDao().get(1L) == null)
        assertTrue(db.readingProgressDao().get(2L) == null)
        assertTrue(db.readingProgressDao().get(3L) != null)
        assertEquals(listOf(1L, 2L), pageDiskCache.deletedBooks)
        // 页边界只是分页加速缓存，与「派生数据」勾选无关，删书就该回收
    }

    /**
     * 清洗副本与源副本都是**内容寻址**的，同一个源文件的原版与清洗版会共用同一份
     * （两行用同一套规则重洗就落到同一个文件上）。删书不能无条件删文件，否则删一本会把
     * 另一本的正文一起删掉——那一本以后打开只有「无法打开书籍」。
     */
    @Test
    fun `共享的清洗副本与源副本只在最后一个引用者被删时才清理`() = runBlocking {
        val dir = File(tempFolder.root, "shared").apply { mkdirs() }
        val sourceDir = File(dir, "source").apply { mkdirs() }
        val sourceCopy = File(sourceDir, "h1.txt").apply { writeText("原文") }
        val cleanedCopy = File(dir, "cleaned-h1.txt").apply { writeText("清洗后") }
        val sharedSourceUri = "file://${sourceCopy.absolutePath}"
        db.bookDao().update(
            db.bookDao().getById(1L)!!.copy(
                fileUri = sharedSourceUri,
                cleanedFilePath = cleanedCopy.absolutePath,
            ),
        )
        db.bookDao().update(
            db.bookDao().getById(2L)!!.copy(
                fileUri = sharedSourceUri,
                cleanedFilePath = cleanedCopy.absolutePath,
            ),
        )
        val repoWithSourceDir = repo(sourceDir = sourceDir)

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
}

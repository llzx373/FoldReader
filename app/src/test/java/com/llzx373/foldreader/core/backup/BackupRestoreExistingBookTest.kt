package com.llzx373.foldreader.core.backup

import androidx.room.Room
import com.llzx373.foldreader.core.data.db.BookEntity
import com.llzx373.foldreader.core.data.db.BookFormat
import com.llzx373.foldreader.core.data.db.ChapterEntity
import com.llzx373.foldreader.core.data.db.FoldReaderDatabase
import com.llzx373.foldreader.core.data.db.TranslationEntity
import com.llzx373.foldreader.core.data.repository.BookshelfRepositoryImpl
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 备份恢复命中已有书的真库测试（A4）。
 *
 * 旧实现是「先 updateGroup 再整行 upsertBook(local.copy(...))」：upsert 是 INSERT OR REPLACE，
 * 用更新前抓的旧实体写回会把 groupName 顶回旧值，且 REPLACE（删旧行+插新行）会级联清空
 * chapters/translations 等 CASCADE 子表——假仓库测不出来，必须真 Room 跑外键级联。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BackupRestoreExistingBookTest {

    private lateinit var db: FoldReaderDatabase
    private lateinit var codec: BackupCodec

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            FoldReaderDatabase::class.java,
        ).build()
        val repository = BookshelfRepositoryImpl(
            bookDao = db.bookDao(),
            progressDao = db.readingProgressDao(),
            chapterDao = db.chapterDao(),
            bookmarkDao = db.bookmarkDao(),
            annotationDao = db.annotationDao(),
            sessionDao = db.readingSessionDao(),
        )
        codec = BackupCodec(
            bookshelfRepository = repository,
            settingsRepository = FakeSettingsRepository(),
            bookPrefsDao = db.bookPrefsDao(),
            readingSessionDao = db.readingSessionDao(),
        )
        db.bookDao().upsert(
            BookEntity(
                id = 7,
                title = "书7",
                author = null,
                fileUri = "content://book/7",
                contentHash = "hashA",
                format = BookFormat.TXT,
                totalChars = 1000,
                encoding = "UTF-8",
                importedAt = 0,
                lastReadAt = null,
                groupName = "旧组",
                description = "本地简介",
            ),
        )
        // CASCADE 子表各挂一行：恢复若走 REPLACE，这两行会被级联清空
        db.chapterDao().upsertAll(
            listOf(ChapterEntity(bookId = 7, chapterIndex = 0, title = "第一章", charStart = 0, charEnd = 100)),
        )
        db.translationDao().upsert(
            TranslationEntity(
                bookId = 7,
                lang = "ZH_HANS",
                unitKind = "chapter",
                unitIndex = 0,
                status = TranslationEntity.STATUS_DONE,
                model = "m",
                paragraphCount = 3,
                updatedAt = 10,
            ),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `恢复分组生效且 CASCADE 子表不被清空`() = runBlocking {
        val result = codec.importJson(
            """
            {
              "app": "FoldReader",
              "version": 12,
              "books": [
                { "title": "书7", "contentHash": "hashA", "format": "TXT", "groupName": "新组" }
              ]
            }
            """.trimIndent(),
        )

        assertEquals(1, result.restoredBooks)
        val book = db.bookDao().getById(7)!!
        // 旧实现里 updateGroup 先写成「新组」，随后的整行 REPLACE 又把它顶回「旧组」
        assertEquals("新组", book.groupName)
        // 备份没带的字段保留本地值
        assertEquals("本地简介", book.description)
        // REPLACE 会级联清空这两张子表；定点 UPDATE 不动它们
        assertEquals(1, db.chapterDao().getForBook(7).size)
        assertEquals(1, db.translationDao().getForBook(7, "ZH_HANS").size)
    }

    @Test
    fun `旧备份无分组与元数据字段时不动本地书籍行`() = runBlocking {
        val result = codec.importJson(
            """
            {
              "app": "FoldReader",
              "version": 2,
              "books": [
                { "title": "书7", "contentHash": "hashA", "format": "TXT" }
              ]
            }
            """.trimIndent(),
        )

        assertEquals(1, result.restoredBooks)
        val book = db.bookDao().getById(7)!!
        assertEquals("旧组", book.groupName)
        assertEquals("本地简介", book.description)
        assertEquals(1, db.chapterDao().getForBook(7).size)
        assertEquals(1, db.translationDao().getForBook(7, "ZH_HANS").size)
    }
}

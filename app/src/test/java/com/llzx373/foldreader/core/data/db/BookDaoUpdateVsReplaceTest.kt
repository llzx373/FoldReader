package com.llzx373.foldreader.core.data.db

import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * A2：书籍行更新的 REPLACE 危害钉样与定点 UPDATE 验证（真 Room 内存库）。
 *
 * `INSERT OR REPLACE` 命中已有行时是「删旧行 + 插新行」：CASCADE 子表整书清空，
 * NO_ACTION 子表因同语句内父行被重建而不报错（数据上原地复活）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BookDaoUpdateVsReplaceTest {

    private fun openDb(): FoldReaderDatabase =
        Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            FoldReaderDatabase::class.java,
        ).build()

    private fun book(id: Long, totalChars: Long = 1000) = BookEntity(
        id = id,
        title = "书$id",
        author = null,
        fileUri = "content://book/$id",
        contentHash = "hash$id",
        format = BookFormat.TXT,
        totalChars = totalChars,
        encoding = "UTF-8",
        importedAt = id,
        lastReadAt = null,
    )

    /** 一本书 + 两类子表：chapters/book_prefs/translations 是 CASCADE，reading_progress 是 NO_ACTION。 */
    private suspend fun seedWithChildren(db: FoldReaderDatabase, bookId: Long) {
        db.bookDao().upsert(book(bookId))
        db.chapterDao().upsertAll(
            listOf(ChapterEntity(bookId = bookId, chapterIndex = 0, title = "第一章", charStart = 0, charEnd = 500)),
        )
        db.bookPrefsDao().upsert(BookPrefsEntity(bookId = bookId, fontSizeSp = 24f))
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
        db.readingProgressDao().upsert(
            ReadingProgressEntity(
                bookId = bookId,
                charOffset = 42,
                chapterIndex = 0,
                totalReadingMillis = 1000,
                updatedAt = 10,
            ),
        )
    }

    @Test
    fun `反例——upsert 命中已有行时 CASCADE 子表被整书清空`() = runBlocking {
        val db = openDb()
        seedWithChildren(db, 1)

        db.bookDao().upsert(book(1, totalChars = 2000))

        assertEquals(2000L, db.bookDao().getById(1)!!.totalChars)
        assertTrue("chapters 被 REPLACE 级联清空", db.chapterDao().getForBook(1).isEmpty())
        assertNull("book_prefs 被 REPLACE 级联清空", db.bookPrefsDao().get(1))
        assertTrue("translations 被 REPLACE 级联清空", db.translationDao().getForBook(1, "ZH_HANS").isEmpty())
        // NO_ACTION 子表不报错也不丢：父行在同一语句内被重建，约束在语句层面没有违反
        val progress = db.readingProgressDao().get(1)
        assertNotNull("reading_progress（NO_ACTION）在 REPLACE 后应存活", progress)
        assertEquals(42L, progress!!.charOffset)
        db.close()
    }

    @Test
    fun `updateTotalChars 定点更新——子表全部存活且其余字段不动`() = runBlocking {
        val db = openDb()
        seedWithChildren(db, 1)
        db.bookDao().updateGroup(listOf(1), "科幻")

        db.bookDao().updateTotalChars(1, 2000)

        val updated = db.bookDao().getById(1)!!
        assertEquals(2000L, updated.totalChars)
        assertEquals("科幻", updated.groupName)
        assertEquals("hash1", updated.contentHash)
        assertEquals(1, db.chapterDao().getForBook(1).size)
        assertEquals(24f, db.bookPrefsDao().get(1)!!.fontSizeSp, 0.0001f)
        assertEquals(1, db.translationDao().getForBook(1, "ZH_HANS").size)
        assertEquals(42L, db.readingProgressDao().get(1)!!.charOffset)
        db.close()
    }
}

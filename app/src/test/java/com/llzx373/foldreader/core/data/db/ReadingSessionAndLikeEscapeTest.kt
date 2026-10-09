package com.llzx373.foldreader.core.data.db

import androidx.room.Room
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * D17：阅读时长原子累加（ON CONFLICT DO UPDATE）与批注 LIKE 前缀转义（真 Room 内存库）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ReadingSessionAndLikeEscapeTest {

    private fun openDb(): FoldReaderDatabase =
        Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            FoldReaderDatabase::class.java,
        ).build()

    private suspend fun seedBook(db: FoldReaderDatabase, id: Long) {
        db.bookDao().upsert(
            BookEntity(
                id = id,
                title = "书$id",
                author = null,
                fileUri = "content://book/$id",
                contentHash = "hash$id",
                format = BookFormat.TXT,
                totalChars = 1000,
                encoding = "UTF-8",
                importedAt = id,
                lastReadAt = null,
            ),
        )
    }

    @Test
    fun `并发累加当日时长不丢增量`() = runBlocking {
        val db = openDb()
        try {
            seedBook(db, 1)
            val day = 1_700_000_000_000L
            // 50 路并发各加 100ms：旧「读-改-写」口径会大量互相覆盖
            (1..50).map { async { db.readingSessionDao().addDuration(1, day, 100) } }.awaitAll()
            assertEquals(5000L, db.readingSessionDao().get(1, day)?.durationMs)
        } finally {
            db.close()
        }
    }

    @Test
    fun `累加跨日桶互不影响`() = runBlocking {
        val db = openDb()
        try {
            seedBook(db, 1)
            db.readingSessionDao().addDuration(1, 1000, 60)
            db.readingSessionDao().addDuration(1, 1000, 40)
            db.readingSessionDao().addDuration(1, 2000, 30)
            assertEquals(100L, db.readingSessionDao().get(1, 1000)?.durationMs)
            assertEquals(30L, db.readingSessionDao().get(1, 2000)?.durationMs)
        } finally {
            db.close()
        }
    }

    @Test
    fun `批注删除前缀里的 LIKE 通配符按字面匹配`() = runBlocking {
        val db = openDb()
        try {
            seedBook(db, 1)
            suspend fun insert(note: String) = db.annotationDao().insert(
                AnnotationEntity(
                    bookId = 1,
                    startCharOffset = 0,
                    endCharOffset = 1,
                    selectedText = "x",
                    color = 0,
                    note = note,
                    createdAt = 1,
                    updatedAt = 1,
                ),
            )
            insert("10% 折扣")
            insert("100 分") // 前缀「10%」不转义时 % 是通配符，会误删这条
            insert("其它批注")
            db.annotationDao().deleteByBookAndNotePrefix(1, "10%")
            val notes = db.annotationDao().observeAll().first().map { it.note }
            assertEquals(listOf("100 分", "其它批注"), notes)
        } finally {
            db.close()
        }
    }
}

package com.llzx373.foldreader.core.data.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

data class BookWithProgress(
    @Embedded val book: BookEntity,
    val charOffset: Long?,
)

@Dao
interface BookDao {

    @Query("SELECT * FROM books ORDER BY COALESCE(lastReadAt, importedAt) DESC")
    fun observeBookshelf(): Flow<List<BookEntity>>

    @Query(
        """
        SELECT books.*, reading_progress.charOffset AS charOffset
        FROM books LEFT JOIN reading_progress ON reading_progress.bookId = books.id
        ORDER BY COALESCE(books.lastReadAt, books.importedAt) DESC
        """,
    )
    fun observeBookshelfWithProgress(): Flow<List<BookWithProgress>>

    @Query("SELECT * FROM books WHERE id = :bookId")
    suspend fun getById(bookId: Long): BookEntity?

    @Query("SELECT * FROM books WHERE fileUri = :fileUri LIMIT 1")
    suspend fun getByFileUri(fileUri: String): BookEntity?

    @Query("SELECT * FROM books WHERE contentHash = :contentHash LIMIT 1")
    suspend fun getByContentHash(contentHash: String): BookEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(book: BookEntity): Long

    @Update
    suspend fun update(book: BookEntity)

    @Query("UPDATE books SET lastReadAt = :timestamp WHERE id = :bookId")
    suspend fun touchLastRead(bookId: Long, timestamp: Long)

    @Query("DELETE FROM books WHERE id IN (:bookIds)")
    suspend fun deleteByIds(bookIds: List<Long>)
}

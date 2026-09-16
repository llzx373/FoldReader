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

    // 书架排序改由 ViewModel 按用户选择客户端排序，DAO 只保证稳定顺序（id），
    // 避免 lastReadAt 更新导致从阅读页返回时列表重排。
    @Query("SELECT * FROM books ORDER BY id")
    fun observeBookshelf(): Flow<List<BookEntity>>

    @Query(
        """
        SELECT books.*, reading_progress.charOffset AS charOffset
        FROM books LEFT JOIN reading_progress ON reading_progress.bookId = books.id
        ORDER BY books.id
        """,
    )
    fun observeBookshelfWithProgress(): Flow<List<BookWithProgress>>

    @Query("SELECT DISTINCT groupName FROM books WHERE groupName IS NOT NULL ORDER BY groupName")
    fun observeGroupNames(): Flow<List<String>>

    @Query(
        """
        SELECT books.*, reading_progress.charOffset AS charOffset
        FROM books LEFT JOIN reading_progress ON reading_progress.bookId = books.id
        WHERE (:groupName IS NULL AND books.groupName IS NULL) OR books.groupName = :groupName
        ORDER BY books.id
        """,
    )
    fun observeBookshelfWithProgressInGroup(groupName: String?): Flow<List<BookWithProgress>>

    @Query("UPDATE books SET groupName = :groupName WHERE id IN (:bookIds)")
    suspend fun updateGroup(bookIds: List<Long>, groupName: String?)

    @Query("UPDATE books SET groupName = NULL WHERE groupName = :groupName")
    suspend fun clearGroup(groupName: String)

    @Query("SELECT * FROM books WHERE id = :bookId")
    suspend fun getById(bookId: Long): BookEntity?

    @Query("SELECT * FROM books WHERE id = :bookId")
    fun observeById(bookId: Long): Flow<BookEntity?>

    @Query("UPDATE books SET encoding = :encoding WHERE id = :bookId")
    suspend fun updateEncoding(bookId: Long, encoding: String)

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

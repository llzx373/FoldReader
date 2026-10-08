package com.llzx373.foldreader.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface WordEntryDao {

    /** 生词本列表：按收藏时间倒序（「按时间」视图的数据源）。 */
    @Query("SELECT * FROM vocabulary_entries ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<WordEntryEntity>>

    @Query("SELECT * FROM vocabulary_entries WHERE bookId = :bookId ORDER BY createdAt DESC")
    fun observeByBook(bookId: Long): Flow<List<WordEntryEntity>>

    /** 备份/导出用的整表快照。 */
    @Query("SELECT * FROM vocabulary_entries ORDER BY createdAt DESC")
    suspend fun getAll(): List<WordEntryEntity>

    @Insert
    suspend fun insert(entry: WordEntryEntity): Long

    @Query("DELETE FROM vocabulary_entries WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT COUNT(*) FROM vocabulary_entries")
    suspend fun count(): Int
}

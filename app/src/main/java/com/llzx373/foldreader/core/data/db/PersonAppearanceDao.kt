package com.llzx373.foldreader.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface PersonAppearanceDao {

    /** 按提及次数降序：出场面板默认把"主角"排在前面。 */
    @Query("SELECT * FROM person_appearances WHERE bookId = :bookId ORDER BY mentionCount DESC")
    fun observeForBook(bookId: Long): Flow<List<PersonAppearanceEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(appearances: List<PersonAppearanceEntity>)

    @Query("DELETE FROM person_appearances WHERE bookId = :bookId")
    suspend fun deleteForBook(bookId: Long)

    /** 覆盖式写入：先删后插必须在同一事务里，否则中途失败会留下半份索引。 */
    @Transaction
    suspend fun replaceForBook(bookId: Long, appearances: List<PersonAppearanceEntity>) {
        deleteForBook(bookId)
        upsertAll(appearances)
    }
}

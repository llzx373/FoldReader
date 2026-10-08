package com.llzx373.foldreader.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ReadingSessionDao {

    @Query("SELECT * FROM reading_sessions WHERE bookId = :bookId AND dayStartMs = :dayStartMs")
    suspend fun get(bookId: Long, dayStartMs: Long): ReadingSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: ReadingSessionEntity)

    @Query("SELECT * FROM reading_sessions WHERE dayStartMs >= :startMs AND dayStartMs <= :endMs")
    suspend fun getBetween(startMs: Long, endMs: Long): List<ReadingSessionEntity>

    @Query("SELECT COUNT(DISTINCT dayStartMs) FROM reading_sessions WHERE bookId = :bookId AND durationMs > 0")
    suspend fun countReadingDays(bookId: Long): Int

    @Query("SELECT * FROM reading_sessions")
    suspend fun getAll(): List<ReadingSessionEntity>

    /** M34：有阅读记录（时长 > 0）的全部日桶起点，供连续打卡计算（跨月不断档）。 */
    @Query("SELECT DISTINCT dayStartMs FROM reading_sessions WHERE durationMs > 0")
    suspend fun dayStartsWithReading(): List<Long>
}

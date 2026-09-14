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
}

package com.llzx373.foldreader.core.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** 按天分桶的阅读时长（轻量统计，写入端按增量 upsert）。 */
@Entity(
    tableName = "reading_sessions",
    primaryKeys = ["bookId", "dayStartMs"],
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("dayStartMs")],
)
data class ReadingSessionEntity(
    val bookId: Long,
    val dayStartMs: Long,
    val durationMs: Long,
)

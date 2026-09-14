package com.llzx373.foldreader.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

@Entity(
    tableName = "reading_progress",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ReadingProgressEntity(
    @PrimaryKey val bookId: Long,
    val charOffset: Long,
    val chapterIndex: Int,
    val totalReadingMillis: Long,
    /** 首次开始阅读时间（0 = 旧数据未知）。 */
    @ColumnInfo(defaultValue = "0") val firstReadAt: Long = 0,
    val updatedAt: Long,
)

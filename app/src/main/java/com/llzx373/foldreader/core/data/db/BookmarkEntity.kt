package com.llzx373.foldreader.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "bookmarks",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.NO_ACTION,
        ),
    ],
    // 查询是 WHERE bookId = ? ORDER BY createdAt DESC，复合索引让排序走索引而非临时 B-tree
    indices = [Index("bookId", "createdAt")],
)
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val charOffset: Long,
    val chapterIndex: Int,
    val snapshotText: String,
    @ColumnInfo(defaultValue = "") val label: String = "",
    val createdAt: Long,
)

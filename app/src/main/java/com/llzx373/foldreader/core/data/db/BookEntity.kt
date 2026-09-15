package com.llzx373.foldreader.core.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class BookFormat { TXT }

@Entity(
    tableName = "books",
    indices = [Index("fileUri"), Index("contentHash")],
)
data class BookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val author: String?,
    val fileUri: String,
    val contentHash: String,
    val format: BookFormat,
    val totalChars: Long,
    val encoding: String,
    val importedAt: Long,
    val lastReadAt: Long?,
    val groupName: String? = null,
    val cleanedFilePath: String? = null,
)

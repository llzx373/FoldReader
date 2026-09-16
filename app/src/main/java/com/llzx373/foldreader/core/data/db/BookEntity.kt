package com.llzx373.foldreader.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class BookFormat { TXT, EPUB, FB2 }

enum class BookSource { IMPORT, EXTERNAL }

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
    @ColumnInfo(defaultValue = "IMPORT") val source: BookSource = BookSource.IMPORT,
    /** 以下为 EPUB 等富元数据格式的扩展字段（TXT/FB2 留空）；subjects 多值以 \n 分隔。 */
    val description: String? = null,
    val publisher: String? = null,
    val language: String? = null,
    val pubDate: String? = null,
    val subjects: String? = null,
    val identifier: String? = null,
    val seriesName: String? = null,
    val seriesIndex: String? = null,
    /** 导入时提取的封面图片本地路径（filesDir/covers/<contentHash>.<ext>）。 */
    val coverPath: String? = null,
)

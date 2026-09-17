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
    /**
     * 内容就绪时间：EPUB/FB2 的整本压平已完成（或打开时顺带完成）。
     * null = 尚未压平，书架角标据此提示"待解析"。TXT 无压平步骤，也写这个字段以保持一致。
     *
     * 记在库里而不是每次去 stat converted/ 目录：一方面角标要能随预热完成**自动消失**，
     * 另一方面书架 Flow 在阅读期间会被进度更新反复触发，逐本查文件不划算。
     */
    val contentPreparedAt: Long? = null,
)

/**
 * 内容是否还需要后台压平：EPUB/FB2 且尚未就绪。
 * TXT 没有压平步骤（它的偏移索引在打开时边建边读），恒为 false。
 */
fun BookEntity.needsContentPreparation(): Boolean =
    format != BookFormat.TXT && contentPreparedAt == null

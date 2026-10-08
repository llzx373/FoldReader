package com.llzx373.foldreader.core.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 生词本条目（M28）：词条 + 释义 + 上下文例句 + 来源书与位置。
 * 随书级联删除；[source] 记录释义来源（本地词典名或「AI」）。
 */
@Entity(
    tableName = "vocabulary_entries",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("bookId"), Index("word")],
)
data class WordEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val word: String,
    val definition: String,
    /** 上下文例句（选区所在句，纯文本）。 */
    val contextSentence: String,
    /** 选区起点的原文字符偏移（位置来源）。 */
    val charOffset: Long,
    /** 释义来源：本地词典名或「AI」。 */
    val source: String,
    val createdAt: Long,
)

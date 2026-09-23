package com.llzx373.foldreader.core.data.db

import androidx.room.Entity
import androidx.room.ForeignKey

/**
 * 人物出场索引（M13.2）：每书每人物一行，记录首次出场位置与提及次数。
 * 由索引期扫描回填，随书级联删除。
 */
@Entity(
    tableName = "person_appearances",
    primaryKeys = ["bookId", "name"],
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class PersonAppearanceEntity(
    val bookId: Long,
    val name: String,
    val firstChapterIndex: Int,
    val firstCharOffset: Long,
    val mentionCount: Int,
)

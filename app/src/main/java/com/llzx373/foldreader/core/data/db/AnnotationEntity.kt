package com.llzx373.foldreader.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "annotations",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [Index("bookId")],
)
data class AnnotationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val startCharOffset: Long,
    val endCharOffset: Long,
    val selectedText: String,
    val color: Long,
    val note: String?,
    @ColumnInfo(defaultValue = STYLE_HIGHLIGHT) val style: String = STYLE_HIGHLIGHT,
    val createdAt: Long,
    val updatedAt: Long,
) {
    companion object {
        const val STYLE_HIGHLIGHT = "highlight"
        const val STYLE_UNDERLINE = "underline"
    }
}

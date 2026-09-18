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
    // 查询是 WHERE bookId = ? ORDER BY startCharOffset ASC，复合索引让排序走索引；
    // 页式标注按页查询/排序走第二个索引
    indices = [Index("bookId", "startCharOffset"), Index("bookId", "pageIndex")],
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
    /**
     * 页式（漫画 / PDF）锚点：页序号（0 基）；文本标注为 null。
     * 页式标注的 [startCharOffset]/[endCharOffset] 恒为 0——页之间没有共享的字符空间。
     */
    val pageIndex: Long? = null,
    /**
     * 页内区域，**归一化 0..1**（页图片左上为原点）。与书签同一套坐标语义，
     * 所以高亮/下划线在缩放与切换适配模式后依然贴合原文（详见 [BookmarkEntity.anchorX]）。
     */
    val regionX: Float? = null,
    val regionY: Float? = null,
    val regionW: Float? = null,
    val regionH: Float? = null,
) {
    /**
     * 打开这本书时应跳到的锚点：页式标注用页序号，文本标注用字符偏移。
     * 语义与 [BookmarkEntity.readerAnchor] 一致——两者单位不同却都是 Long，
     * 导航链路只传一个数、由 ReaderHost 按格式解释。
     */
    fun readerAnchor(): Long = pageIndex ?: startCharOffset

    companion object {
        const val STYLE_HIGHLIGHT = "highlight"
        const val STYLE_UNDERLINE = "underline"
    }
}

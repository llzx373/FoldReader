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
    // 查询是 WHERE bookId = ? ORDER BY createdAt DESC，复合索引让排序走索引而非临时 B-tree；
    // 页式书签按页查询/排序走第二个索引
    indices = [Index("bookId", "createdAt"), Index("bookId", "pageIndex")],
)
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val charOffset: Long,
    val chapterIndex: Int,
    val snapshotText: String,
    @ColumnInfo(defaultValue = "") val label: String = "",
    val createdAt: Long,
    /**
     * 页式（漫画 / PDF）锚点：页序号（0 基）；文本书签为 null。
     * 与 [charOffset] 是两套不可互换的坐标，各自只被自己那套阅读模式使用——
     * 页式保存时 [charOffset] 恒为 0，文本保存时这里恒为 null。
     */
    val pageIndex: Long? = null,
    /**
     * 页内锚点，**归一化 0..1**（页图片左上为原点）。
     *
     * 存归一化而不是像素：渲染尺寸随适配模式、缩放、窗口与分屏变化，存像素必然错位；
     * 归一化后绘制时乘当前绘制宽高即可自动跟随。
     * [anchorX]/[anchorY] 为 null = 只锚到页（不带页内位置）；[anchorW]/[anchorH] 为 null = 点书签。
     */
    val anchorX: Float? = null,
    val anchorY: Float? = null,
    val anchorW: Float? = null,
    val anchorH: Float? = null,
) {
    /**
     * 打开这本书时应跳到的锚点：页式书签用页序号，文本书签用字符偏移。
     *
     * 两者单位不同却都是 Long，导航链路只传一个数、由 ReaderHost 按格式解释，
     * 所以这里必须给对——否则页式书签会被当成偏移 0，永远落到第 1 页。
     */
    fun readerAnchor(): Long = pageIndex ?: charOffset
}

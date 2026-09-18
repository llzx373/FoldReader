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
            onDelete = ForeignKey.NO_ACTION,
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
    /** 累计已读字符数（去重进度统计用）。 */
    @ColumnInfo(defaultValue = "0") val charsReadTotal: Long = 0,
    /**
     * 漫画当前页（0 基）。文本用 [charOffset] 锚点，漫画没有字符偏移，
     * 位置就是页序号——不复用 charOffset 是为了不让两套语义在同名列上打架。
     */
    val comicPage: Int? = null,
    val updatedAt: Long,
) {
    /**
     * 文本模式保存前调用：把旧的**页式**锚点原样带过来。
     *
     * [ReadingProgressDao.upsert] 是 `INSERT OR REPLACE`（整行替换）而主键是 bookId，
     * 所以「本次没写的列」会被清空。文本与页式（漫画 / PDF）两种模式共用这一行，
     * 各自保存时都必须把**对方**的锚点带过来，否则来回切模式就会互相抹掉位置。
     */
    fun keepPagedAnchor(existing: ReadingProgressEntity?): ReadingProgressEntity =
        if (existing == null) this else copy(comicPage = existing.comicPage)

    /** 页式模式保存前调用：把旧的**文本**锚点（字符偏移 + 所属章节）带过来，与 [keepPagedAnchor] 对称。 */
    fun keepTextAnchor(existing: ReadingProgressEntity?): ReadingProgressEntity =
        if (existing == null) {
            this
        } else {
            copy(charOffset = existing.charOffset, chapterIndex = existing.chapterIndex)
        }
}

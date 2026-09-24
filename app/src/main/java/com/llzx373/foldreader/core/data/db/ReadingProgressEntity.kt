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
    /**
     * 译文模式的锚点（M19 视角 1）：**译本流**的字符偏移。
     *
     * 原/译是两套互不换算的坐标（译本即副本，见 docs/AI功能需求与实施.md 5.0），
     * 所以译文进度另起一列，与文本锚点、页式锚点三方对称共存、互不顶替。
     */
    val translationAnchor: Long? = null,
    val updatedAt: Long,
) {
    /**
     * 文本模式保存前调用：把旧的**页式**锚点与**译文**锚点原样带过来。
     *
     * [ReadingProgressDao.upsert] 是 `INSERT OR REPLACE`（整行替换）而主键是 bookId，
     * 所以「本次没写的列」会被清空。文本 / 页式（漫画 / PDF）/ 译文三种模式共用这一行，
     * 各自保存时都必须把**另外两方**的锚点带过来，否则来回切模式就会互相抹掉位置。
     */
    fun keepPagedAnchor(existing: ReadingProgressEntity?): ReadingProgressEntity =
        if (existing == null) {
            this
        } else {
            copy(comicPage = existing.comicPage, translationAnchor = existing.translationAnchor)
        }

    /** 页式模式保存前调用：把旧的**文本**锚点（字符偏移 + 所属章节）与**译文**锚点带过来，与 [keepPagedAnchor] 对称。 */
    fun keepTextAnchor(existing: ReadingProgressEntity?): ReadingProgressEntity =
        if (existing == null) {
            this
        } else {
            copy(
                charOffset = existing.charOffset,
                chapterIndex = existing.chapterIndex,
                translationAnchor = existing.translationAnchor,
            )
        }

    /** 译文模式保存前调用：把旧的**文本**锚点与**页式**锚点带过来，与前两个对称。 */
    fun keepTranslationAnchor(existing: ReadingProgressEntity?): ReadingProgressEntity =
        if (existing == null) {
            this
        } else {
            copy(
                charOffset = existing.charOffset,
                chapterIndex = existing.chapterIndex,
                comicPage = existing.comicPage,
            )
        }
}

package com.llzx373.foldreader.core.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 漫画页翻译台账（M22）：每书每目标语言每页一行，记录该页气泡翻译的状态。
 *
 * 与 `translations`（电子书单位台账）同一约定：译文不落库——OCR 缓存与气泡译文落
 * `filesDir/comic_translate/<bookId>/`（见 `core/translate/ComicTranslationStore`），
 * 这里只存状态机与元信息，供页状态显示（未译/翻译中/已译/失败）与整卷断点续译。
 * 随书级联删除。
 */
@Entity(
    tableName = "comic_page_translations",
    primaryKeys = ["bookId", "lang", "pageIndex"],
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ComicPageTranslationEntity(
    val bookId: Long,
    /** 目标语言（`AiTargetLang.name`，如 ZH_HANS）。 */
    val lang: String,
    /** 页序号（0 基，与漫画容器页序一致）。 */
    val pageIndex: Int,
    /** 状态：[STATUS_PENDING] / [STATUS_TRANSLATING] / [STATUS_DONE] / [STATUS_FAILED]。 */
    val status: String,
    /** 最近一次翻译所用模型（未译过为空串）。 */
    val model: String,
    /** 已译页的气泡数（未译 / 失败为 0）。 */
    val bubbleCount: Int,
    val updatedAt: Long,
) {
    companion object {
        const val STATUS_PENDING = "pending"
        const val STATUS_TRANSLATING = "translating"
        const val STATUS_DONE = "done"
        const val STATUS_FAILED = "failed"
    }
}

@Dao
interface ComicPageTranslationDao {

    /** 页状态列表（按页号升序，网格/菜单状态展示用）。 */
    @Query("SELECT * FROM comic_page_translations WHERE bookId = :bookId AND lang = :lang ORDER BY pageIndex")
    fun observeForBook(bookId: Long, lang: String): Flow<List<ComicPageTranslationEntity>>

    /** 该书该语言全部页行（断点续译：跳过 done 的判据）。 */
    @Query("SELECT * FROM comic_page_translations WHERE bookId = :bookId AND lang = :lang")
    suspend fun getForBook(bookId: Long, lang: String): List<ComicPageTranslationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(page: ComicPageTranslationEntity)

    /** 状态机迁移：translating → done / failed；done 时带回模型与气泡数。 */
    @Query(
        "UPDATE comic_page_translations SET status = :status, model = :model, " +
            "bubbleCount = :bubbleCount, updatedAt = :updatedAt " +
            "WHERE bookId = :bookId AND lang = :lang AND pageIndex = :pageIndex",
    )
    suspend fun updateStatus(
        bookId: Long,
        lang: String,
        pageIndex: Int,
        status: String,
        model: String,
        bubbleCount: Int,
        updatedAt: Long,
    )

    /** 单页作废（重译前）。 */
    @Query("DELETE FROM comic_page_translations WHERE bookId = :bookId AND lang = :lang AND pageIndex = :pageIndex")
    suspend fun deletePage(bookId: Long, lang: String, pageIndex: Int)

    /** 「清除全部 AI 数据」与删书前置：清掉该书全部语言的页台账。 */
    @Query("DELETE FROM comic_page_translations WHERE bookId = :bookId")
    suspend fun deleteForBook(bookId: Long)

    /** 设置页「清除全部 AI 数据」：清空全部书的漫画页台账。 */
    @Query("DELETE FROM comic_page_translations")
    suspend fun deleteAll()
}

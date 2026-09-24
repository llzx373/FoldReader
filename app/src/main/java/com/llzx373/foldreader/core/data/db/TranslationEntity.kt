package com.llzx373.foldreader.core.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 翻译单位台账（M19）：每书每目标语言每单位一行，记录该单位的翻译状态。
 *
 * 译文正文不落库——段落结构化结果落 `filesDir/translations/<bookId>/<lang>/`（见
 * `core/translate/TranslationStore`），这里只存状态机与元信息，供目录面板显示
 * 「未译 / 翻译中 / 已译 / 失败」与断点续译。随书级联删除。
 */
@Entity(
    tableName = "translations",
    primaryKeys = ["bookId", "lang", "unitIndex"],
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class TranslationEntity(
    val bookId: Long,
    /** 目标语言（`AiTargetLang.name`，如 ZH_HANS）。 */
    val lang: String,
    /** 单位类型：`chapter` / `block`（对应 `core/translate/UnitKind` 的小写名）。 */
    val unitKind: String,
    /** 单位号：该书该语言下单位的 0 基序号（与切块器产出的 [TranslationUnit.index] 一致）。 */
    val unitIndex: Int,
    /** 状态：[STATUS_PENDING] / [STATUS_TRANSLATING] / [STATUS_DONE] / [STATUS_FAILED]。 */
    val status: String,
    /** 最近一次翻译所用模型（未译过为空串）。 */
    val model: String,
    /** 已译单位的段落数（未译 / 失败为 0）。 */
    val paragraphCount: Int,
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
interface TranslationDao {

    /** 目录面板的单位状态列表（按单位号升序）。 */
    @Query("SELECT * FROM translations WHERE bookId = :bookId AND lang = :lang ORDER BY unitIndex")
    fun observeForBook(bookId: Long, lang: String): Flow<List<TranslationEntity>>

    /** 该书该语言全部单位行（断点续译：跳过 done 的判据）。 */
    @Query("SELECT * FROM translations WHERE bookId = :bookId AND lang = :lang")
    suspend fun getForBook(bookId: Long, lang: String): List<TranslationEntity>

    /** 某状态的单位数（M20 术语回填：前 3 个 done 单位触发一次回填的判据）。 */
    @Query("SELECT COUNT(*) FROM translations WHERE bookId = :bookId AND lang = :lang AND status = :status")
    suspend fun countByStatus(bookId: Long, lang: String, status: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(unit: TranslationEntity)

    /** 状态机迁移：translating → done / failed；done 时带回模型与段落数。 */
    @Query(
        "UPDATE translations SET status = :status, model = :model, " +
            "paragraphCount = :paragraphCount, updatedAt = :updatedAt " +
            "WHERE bookId = :bookId AND lang = :lang AND unitIndex = :unitIndex",
    )
    suspend fun updateStatus(
        bookId: Long,
        lang: String,
        unitIndex: Int,
        status: String,
        model: String,
        paragraphCount: Int,
        updatedAt: Long,
    )

    /** 「清除全部 AI 数据」与删书前置：清掉该书全部语言的台账。 */
    @Query("DELETE FROM translations WHERE bookId = :bookId")
    suspend fun deleteForBook(bookId: Long)

    /** 单个单位作废（重译前 / 切块边界失效时）。 */
    @Query("DELETE FROM translations WHERE bookId = :bookId AND lang = :lang AND unitIndex = :unitIndex")
    suspend fun deleteUnit(bookId: Long, lang: String, unitIndex: Int)

    /** 设置页「清除全部 AI 数据」：清空全部书的翻译台账。 */
    @Query("DELETE FROM translations")
    suspend fun deleteAll()
}

package com.llzx373.foldreader.core.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 章节摘要台账 + 内容表（M29）：每书每摘要语言每单位一行。
 *
 * 与 `translations` 同构（状态机 + 元信息），但摘要正文短（150~300 字），直接落库
 * 不另起文件存储——少一套目录清理，「清除全部 AI 数据」与删书级联一行 SQL 收尾。
 * 单位号与切块器产出的 `TranslationUnit.index` 同口径（切块边界复用 M19 的单位划分）。
 * 随书级联删除。
 */
@Entity(
    tableName = "chapter_summaries",
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
data class ChapterSummaryEntity(
    val bookId: Long,
    /** 摘要语言（`AiTargetLang.name`，如 ZH_HANS）。 */
    val lang: String,
    /** 单位号：该书该语言下单位的 0 基序号（与 `TranslationUnit.index` 一致）。 */
    val unitIndex: Int,
    /** 单位类型：`chapter` / `block`（对应 `core/translate/UnitKind` 的小写名）。 */
    val unitKind: String,
    /** 单位标题快照（目录面板展示 / 大纲聚合的标题来源，免回算单位清单）。 */
    val unitTitle: String,
    /** 状态：[STATUS_PENDING] / [STATUS_SUMMARIZING] / [STATUS_DONE] / [STATUS_FAILED]。 */
    val status: String,
    /** 摘要正文（done 时非空；其余状态为空串）。 */
    val summary: String,
    /** 最近一次摘要所用模型（未摘要过为空串）。 */
    val model: String,
    val updatedAt: Long,
) {
    companion object {
        const val STATUS_PENDING = "pending"
        const val STATUS_SUMMARIZING = "summarizing"
        const val STATUS_DONE = "done"
        const val STATUS_FAILED = "failed"
    }
}

@Dao
interface ChapterSummaryDao {

    /** 目录面板的摘要状态列表（按单位号升序）。 */
    @Query("SELECT * FROM chapter_summaries WHERE bookId = :bookId AND lang = :lang ORDER BY unitIndex")
    fun observeForBook(bookId: Long, lang: String): Flow<List<ChapterSummaryEntity>>

    /** 该书该语言全部单位行（断点续做：跳过 done 的判据）。 */
    @Query("SELECT * FROM chapter_summaries WHERE bookId = :bookId AND lang = :lang")
    suspend fun getForBook(bookId: Long, lang: String): List<ChapterSummaryEntity>

    /** 已完成的摘要（按单位号升序）：全书大纲聚合与问书上下文的数据源。 */
    @Query(
        "SELECT * FROM chapter_summaries WHERE bookId = :bookId AND lang = :lang " +
            "AND status = 'done' ORDER BY unitIndex",
    )
    suspend fun getDoneForBook(bookId: Long, lang: String): List<ChapterSummaryEntity>

    /** 全部书的已完成摘要（备份导出用；pending/failed 是过程态，不备份）。 */
    @Query("SELECT * FROM chapter_summaries WHERE status = 'done'")
    suspend fun getAllDone(): List<ChapterSummaryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(unit: ChapterSummaryEntity)

    /** 状态机迁移：summarizing → done / failed；done 时带回摘要正文与模型。 */
    @Query(
        "UPDATE chapter_summaries SET status = :status, summary = :summary, model = :model, " +
            "updatedAt = :updatedAt " +
            "WHERE bookId = :bookId AND lang = :lang AND unitIndex = :unitIndex",
    )
    suspend fun updateStatus(
        bookId: Long,
        lang: String,
        unitIndex: Int,
        status: String,
        summary: String,
        model: String,
        updatedAt: Long,
    )

    /** 「清除全部 AI 数据」：清空全部书的摘要台账与内容。删书走外键级联。 */
    @Query("DELETE FROM chapter_summaries")
    suspend fun deleteAll()
}

/**
 * 全书大纲（M29）：每书每摘要语言一行，由该书的摘要链聚合生成。
 * 随书级联删除。
 */
@Entity(
    tableName = "book_outlines",
    primaryKeys = ["bookId", "lang"],
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class BookOutlineEntity(
    val bookId: Long,
    /** 大纲语言（`AiTargetLang.name`）。 */
    val lang: String,
    /** 大纲正文。 */
    val outline: String,
    /** 聚合时纳入的摘要条数（摘要链变长后提示可重新生成）。 */
    val summaryCount: Int,
    val model: String,
    val updatedAt: Long,
)

@Dao
interface BookOutlineDao {

    @Query("SELECT * FROM book_outlines WHERE bookId = :bookId AND lang = :lang")
    fun observe(bookId: Long, lang: String): Flow<BookOutlineEntity?>

    @Query("SELECT * FROM book_outlines WHERE bookId = :bookId AND lang = :lang")
    suspend fun get(bookId: Long, lang: String): BookOutlineEntity?

    /** 全部书的大纲（备份导出用）。 */
    @Query("SELECT * FROM book_outlines")
    suspend fun getAll(): List<BookOutlineEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(outline: BookOutlineEntity)

    /** 「清除全部 AI 数据」：清空全部书的大纲。删书走外键级联。 */
    @Query("DELETE FROM book_outlines")
    suspend fun deleteAll()
}

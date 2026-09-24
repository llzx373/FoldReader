package com.llzx373.foldreader.core.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 术语表（M20，R6）：翻译时注入 prompt 的「原文 → 约定译法」对照。
 *
 * 三个层级同表（[scope]）：`global` 对所有书生效（ownerKey 恒空串）、
 * `book` 只对本书（ownerKey = bookId 字符串）、`series` 漫画系列共享（M22 写入，
 * 本版只在合并读取时支持）。注入优先级 书 > 系列 > 全局，同 source 高层级覆盖
 * 低层级（合并逻辑见 `core/translate/GlossaryRepository.kt`）。
 *
 * [origin]：`auto` = 人物索引 / 模型回填的候选，[confirmed] = false 时不参与注入，
 * 经术语表 UI 确认后才生效；`user` = 手动添加，入行即 confirmed。
 *
 * 不设书籍外键：global/series 行不属于任何书；单书行的清理由删书链路与
 * 「清除全部 AI 数据」按 (scope, ownerKey) 负责。
 */
@Entity(
    tableName = "glossary_terms",
    indices = [Index(value = ["scope", "ownerKey", "source"], unique = true)],
)
data class GlossaryTermEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 层级：[SCOPE_GLOBAL] / [SCOPE_SERIES] / [SCOPE_BOOK]。 */
    val scope: String,
    /** 属主键：global 恒 ""；book = bookId.toString()；series = seriesKey。 */
    val ownerKey: String,
    /** 原文词（注入时的匹配键）。 */
    val source: String,
    /** 约定译法；候选允许空串（人物候选只有原名，确认时补填）。 */
    val target: String,
    /** 来源：[ORIGIN_AUTO] / [ORIGIN_USER]。 */
    val origin: String,
    /** 是否已确认（只 confirmed 的行参与注入与备份意义上的"生效"）。 */
    val confirmed: Boolean,
) {
    companion object {
        const val SCOPE_GLOBAL = "global"
        const val SCOPE_SERIES = "series"
        const val SCOPE_BOOK = "book"
        const val ORIGIN_AUTO = "auto"
        const val ORIGIN_USER = "user"
    }
}

@Dao
interface GlossaryTermDao {

    /** 某层级（或某层级某属主）的全部行，按 source 排序稳定展示。 */
    @Query("SELECT * FROM glossary_terms WHERE scope = :scope AND ownerKey = :ownerKey ORDER BY source")
    fun observeFor(scope: String, ownerKey: String): Flow<List<GlossaryTermEntity>>

    /** 候选列表：未确认且已有译法的行（空 target 的人物候选也列出，确认时补填）。 */
    @Query("SELECT * FROM glossary_terms WHERE confirmed = 0 ORDER BY scope, ownerKey, source")
    fun observeUnconfirmed(): Flow<List<GlossaryTermEntity>>

    /** 备份导出 / 单书表选书清单用。 */
    @Query("SELECT * FROM glossary_terms ORDER BY scope, ownerKey, source")
    suspend fun getAll(): List<GlossaryTermEntity>

    /** 全表观察（单书表选书清单与 reactive 展示）。 */
    @Query("SELECT * FROM glossary_terms ORDER BY scope, ownerKey, source")
    fun observeAll(): Flow<List<GlossaryTermEntity>>

    /** 候选 upsert：同 (scope, ownerKey, source) 已存在时整行替换（唯一索引去重）。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(term: GlossaryTermEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(terms: List<GlossaryTermEntity>)

    /** 确认 / 取消确认；[target] 非 null 时一并补填译法（人物候选确认路径）。 */
    @Query("UPDATE glossary_terms SET confirmed = :confirmed, target = COALESCE(:target, target) WHERE id = :id")
    suspend fun setConfirmed(id: Long, confirmed: Boolean, target: String? = null)

    @Query("DELETE FROM glossary_terms WHERE id = :id")
    suspend fun delete(id: Long)

    /** 删书 / 清除该书术语：按 (scope, ownerKey) 定点清。 */
    @Query("DELETE FROM glossary_terms WHERE scope = :scope AND ownerKey = :ownerKey")
    suspend fun deleteFor(scope: String, ownerKey: String)

    /** 注入用：某层级某属主下已确认的行（合并优先级在仓库层做）。 */
    @Query("SELECT * FROM glossary_terms WHERE scope = :scope AND ownerKey = :ownerKey AND confirmed = 1")
    suspend fun confirmedFor(scope: String, ownerKey: String): List<GlossaryTermEntity>

    /** 「清除全部 AI 数据」：清空全部层级。 */
    @Query("DELETE FROM glossary_terms")
    suspend fun deleteAll()
}

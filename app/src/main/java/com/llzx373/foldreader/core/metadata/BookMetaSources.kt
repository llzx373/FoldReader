package com.llzx373.foldreader.core.metadata

/**
 * `books.metaSource` 列的编解码与写回决策（M17）。
 *
 * metaSource 是**逐字段**来源标记，编码为逗号分隔的 `字段:来源` 对，如 `"author:ai,genre:user"`：
 * - 来源只有两种：[SOURCE_AI]（AI 补全填入，UI 标「AI 生成」）与 [SOURCE_USER]（用户编辑过，锁定）；
 * - 没出现在串里的字段 = 无标记（导入期元数据、尚未补全），天然视为非 AI、未锁定。
 *
 * 设计取舍：不用「整书一把锁」的单标记——用户改过作者后，AI 仍应能补题材；
 * 也不用三列分别存来源——一列编码足够，且备份只需多导一个字段。
 * 两条写回路径共用这份语义：
 * - AI（[planAiMetadataWrite]）：只补「当前为空且未被用户锁定」的字段，已写上的打 ai 标；
 * - 用户（[planUserEdit]）：值发生变化的字段打 user 标，此后 AI 永不再写该字段。
 */
object BookMetaSources {
    const val FIELD_AUTHOR = "author"
    const val FIELD_SYNOPSIS = "desc"
    const val FIELD_GENRE = "genre"

    const val SOURCE_AI = "ai"
    const val SOURCE_USER = "user"

    /** 参与来源标记的全部字段（UI 徽标遍历用）。 */
    val FIELDS: List<String> = listOf(FIELD_AUTHOR, FIELD_SYNOPSIS, FIELD_GENRE)

    /** 解析 `"author:ai,genre:user"` → Map；畸形片段逐段丢弃，绝不抛异常。 */
    fun parse(metaSource: String): Map<String, String> =
        metaSource.split(',').mapNotNull { part ->
            val kv = part.trim().split(':')
            if (kv.size == 2 && kv[0] in FIELDS &&
                (kv[1] == SOURCE_AI || kv[1] == SOURCE_USER)
            ) {
                kv[0] to kv[1]
            } else {
                null
            }
        }.toMap()

    /** 编码 Map → 稳定字符串（按字段名排序，确定性输出便于比对与测试）。 */
    fun encode(sources: Map<String, String>): String =
        sources
            .filter { it.key in FIELDS && (it.value == SOURCE_AI || it.value == SOURCE_USER) }
            .entries.sortedBy { it.key }
            .joinToString(",") { "${it.key}:${it.value}" }

    fun sourceOf(metaSource: String, field: String): String? = parse(metaSource)[field]

    /** 该字段当前值是否 AI 填入（详情页据此打「AI 生成」徽标）。 */
    fun isAiGenerated(metaSource: String, field: String): Boolean =
        sourceOf(metaSource, field) == SOURCE_AI

    /** 该字段是否被用户锁定（用户编辑过即锁定，AI 永不再写）。 */
    fun isUserOwned(metaSource: String, field: String): Boolean =
        sourceOf(metaSource, field) == SOURCE_USER

    fun withSource(metaSource: String, field: String, source: String): String =
        encode(parse(metaSource) + (field to source))

    /**
     * AI 写回决策：只补「当前为空且未被用户锁定」的字段，返回 null 表示没有可补的。
     *
     * 已有值一律不动——包括此前 AI 自己填的：重跑补全不会覆盖旧值，避免反复改写。
     * 各字段 null = 本列不动（DAO 层 COALESCE 再兜一层）；[AiMetadataWrite.metaSource]
     * 为旧标记并入新 ai 标后的整串。
     */
    fun planAiMetadataWrite(
        current: BookMetaSnapshot,
        suggestionAuthor: String?,
        suggestionSynopsis: String?,
        suggestionGenreTag: String?,
    ): AiMetadataWrite? {
        var metaSource = current.metaSource
        val filled = mutableListOf<String>()
        var author: String? = null
        var synopsis: String? = null
        var genreTag: String? = null
        fun consider(field: String, currentValue: String?, suggested: String?, apply: () -> Unit) {
            if (suggested.isNullOrBlank()) return
            if (!currentValue.isNullOrBlank()) return
            if (isUserOwned(metaSource, field)) return
            metaSource = withSource(metaSource, field, SOURCE_AI)
            filled += field
            apply()
        }
        consider(FIELD_AUTHOR, current.author, suggestionAuthor?.trim()) { author = suggestionAuthor!!.trim() }
        consider(FIELD_SYNOPSIS, current.synopsis, suggestionSynopsis?.trim()) { synopsis = suggestionSynopsis!!.trim() }
        consider(FIELD_GENRE, current.genreTag, suggestionGenreTag?.trim()) { genreTag = suggestionGenreTag!!.trim() }
        if (filled.isEmpty()) return null
        return AiMetadataWrite(author, synopsis, genreTag, metaSource, filled)
    }

    /**
     * 用户编辑决策：三列无条件覆盖为编辑结果（空白归一为 null，即允许清空），
     * **值发生变化**的字段打 user 标（含清空——用户主动清掉的字段 AI 也不许回填）；
     * 没动的字段保留原标记（AI 填的还是标 ai）。
     */
    fun planUserEdit(
        current: BookMetaSnapshot,
        newAuthor: String?,
        newSynopsis: String?,
        newGenreTag: String?,
    ): UserMetadataWrite {
        val author = newAuthor?.trim()?.takeIf { it.isNotEmpty() }
        val synopsis = newSynopsis?.trim()?.takeIf { it.isNotEmpty() }
        val genreTag = newGenreTag?.trim()?.takeIf { it.isNotEmpty() }
        var metaSource = current.metaSource
        fun mark(field: String, old: String?, new: String?) {
            if (old?.takeIf { it.isNotBlank() } != new) {
                metaSource = withSource(metaSource, field, SOURCE_USER)
            }
        }
        mark(FIELD_AUTHOR, current.author, author)
        mark(FIELD_SYNOPSIS, current.synopsis, synopsis)
        mark(FIELD_GENRE, current.genreTag, genreTag)
        return UserMetadataWrite(author, synopsis, genreTag, metaSource)
    }
}

/** author / description / genreTag + metaSource 的当前值快照（写回决策的输入）。 */
data class BookMetaSnapshot(
    val author: String?,
    val synopsis: String?,
    val genreTag: String?,
    val metaSource: String,
)

/** AI 写回计划：各字段 null = 不动；[filledFields] 是本次实际补上的字段（供 UI 汇报与测试断言）。 */
data class AiMetadataWrite(
    val author: String?,
    val synopsis: String?,
    val genreTag: String?,
    val metaSource: String,
    val filledFields: List<String>,
)

/** 用户编辑写回计划：三列无条件覆盖 + 打完 user 标的 metaSource。 */
data class UserMetadataWrite(
    val author: String?,
    val synopsis: String?,
    val genreTag: String?,
    val metaSource: String,
)

package com.llzx373.foldreader.core.translate

import com.llzx373.foldreader.core.data.db.GlossaryTermDao
import com.llzx373.foldreader.core.data.db.GlossaryTermEntity

/**
 * 术语合并（M20，R6）：注入 prompt 前把三个层级的已确认术语并成一张对照表。
 *
 * 优先级 **书 > 系列 > 全局**：同 source 高层级覆盖低层级。返回顺序稳定
 * （先书后系列后全局，去重后保留首次出现），prompt 里按序平铺。
 */
fun mergeGlossary(
    book: List<Pair<String, String>>,
    series: List<Pair<String, String>>,
    global: List<Pair<String, String>>,
): List<Pair<String, String>> {
    val seen = HashSet<String>()
    val out = ArrayList<Pair<String, String>>(book.size + series.size + global.size)
    (book + series + global).forEach { (source, target) ->
        if (source.isBlank() || target.isBlank()) return@forEach
        if (seen.add(source)) out += source to target
    }
    return out
}

/**
 * 术语表仓库（M20）：各层级的读取合并与候选写入。
 *
 * ownerKey 口径：global = ""，book = bookId.toString()，series = seriesKey
 * （M20 不写 series 行，读取合并已支持——漫画系列表 M22 落地后直接生效）。
 */
class GlossaryRepository(private val dao: GlossaryTermDao) {

    /** 注入用：某书当前生效的术语对照（已确认、按优先级合并去重）。 */
    suspend fun mergedConfirmed(bookId: String, seriesKey: String? = null): List<Pair<String, String>> =
        mergeGlossary(
            book = dao.confirmedFor(GlossaryTermEntity.SCOPE_BOOK, bookId).pairs(),
            series = seriesKey?.let { dao.confirmedFor(GlossaryTermEntity.SCOPE_SERIES, it).pairs() }
                .orEmpty(),
            global = dao.confirmedFor(GlossaryTermEntity.SCOPE_GLOBAL, "").pairs(),
        )

    /**
     * 候选 upsert（人物索引 / 模型回填共用）：origin=auto、confirmed=false，
     * 同 (scope, ownerKey, source) 已存在时整行替换（唯一索引去重）。
     */
    suspend fun upsertCandidates(scope: String, ownerKey: String, terms: List<Pair<String, String>>) {
        if (terms.isEmpty()) return
        dao.upsertAll(
            terms.filter { it.first.isNotBlank() }.map { (source, target) ->
                GlossaryTermEntity(
                    scope = scope,
                    ownerKey = ownerKey,
                    source = source.trim(),
                    target = target.trim(),
                    origin = GlossaryTermEntity.ORIGIN_AUTO,
                    confirmed = false,
                )
            },
        )
    }

    private fun List<GlossaryTermEntity>.pairs(): List<Pair<String, String>> =
        map { it.source to it.target }
}

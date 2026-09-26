package com.llzx373.foldreader.core.metadata

/**
 * 题材标签固定枚举（M17）。
 *
 * AI 元数据补全只能从这份清单里选标签；规则版「按题材分组」把标签原样落成 `books.groupName`——
 * 分组的稳定性直接依赖这份清单，增删改都是行为变更（进 CHANGELOG）。
 * 标签存中文 label 本身（不是枚举 key）：分组名、详情页展示、备份导出的都是它。
 */
object GenreTags {
    val ALL: List<String> = listOf(
        "玄幻", "都市", "历史", "科幻", "言情",
        "武侠", "悬疑", "轻小说", "文学", "其他",
    )

    /** 归一化：命中枚举原样返回；未知标签（含空白）→ null——宁缺毋滥，不自造标签。 */
    fun normalize(label: String?): String? = label?.trim()?.takeIf { it in ALL }
}

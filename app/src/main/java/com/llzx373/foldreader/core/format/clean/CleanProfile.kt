package com.llzx373.foldreader.core.format.clean

/**
 * 清理档位。三档预设见 [CleanToggles.preset]；用户在设置页逐项改动后落到 [CUSTOM]。
 *
 * - [CONSERVATIVE]：只做无争议、不会误伤的字符与空白归一 + 整行噪音过滤。
 * - [STANDARD]：默认档。在保守档之上加段落重组、空行规整、章节修复、标点规整。
 * - [AGGRESSIVE]：在标准档之上再加繁简转换与重复标点折叠等改写幅度更大的规则。
 * - [CUSTOM]：逐项开关由用户决定（预设基线取标准档）。
 */
enum class CleanLevel { CONSERVATIVE, STANDARD, AGGRESSIVE, CUSTOM }

/**
 * 清洗规则的细粒度开关。
 *
 * 每一项都必须是**幂等**的（对已清理过的文本再跑一遍结果不变），否则「智能整理」重复执行会漂移。
 * [reflowParagraphs] 是唯一需要全文统计的开关（硬换行判定），其余均按行/按局部窗口决策。
 */
data class CleanToggles(
    /** 不可见字符、全角空格/制表符、全角数字字母、HTML 实体与标签。 */
    val unifyChars: Boolean = false,
    /** 行尾空白。 */
    val trimLines: Boolean = false,
    /** 行内连续空白折叠为一个。 */
    val collapseSpaces: Boolean = false,
    /** 段首缩进统一为两个全角空格；章节标题顶格。 */
    val canonicalIndent: Boolean = false,
    /** 整行噪音：网址/站点推广/防盗声明/字数统计/作者的话/论坛残留/装饰线 + 用户自定义正则。 */
    val filterNoise: Boolean = false,
    /** 行内噪音切除：句中夹带的 URL/域名与「（本章完）」类尾标。 */
    val exciseInlineNoise: Boolean = false,
    /** 星号/方框等遮蔽符号与缺字占位规整。 */
    val maskRuns: Boolean = false,
    /** 合并被硬换行拆开的段落。 */
    val reflowParagraphs: Boolean = false,
    /** 空行规整：段中空行删除、连续空行折叠。 */
    val collapseBlankLines: Boolean = false,
    /** 章节标题修复：行中标题提行、命名规范化。 */
    val repairChapters: Boolean = false,
    /** 重复章节标题去重、正文开头目录块删除。 */
    val dedupeChapterTitles: Boolean = false,
    /** 标点规范：省略号、破折号。 */
    val normalizePunctuation: Boolean = false,
    /** 重复标点折叠（`！！！` → `！`；`……` `——` 不动）。改写幅度大，只在激进档默认开。 */
    val normalizeRepeatedPunctuation: Boolean = false,
    /** 引号规整：引号内侧与引号内两汉字之间的空白。 */
    val normalizeQuotes: Boolean = false,
    /**
     * 繁体转简体（单字级映射，需要 `assets/ts_map.txt`）。
     *
     * **刻意不属于任何档位预设**：繁简是用户取向（有人读繁、有人读简），与「洗得多干净」无关，
     * 所以它只在导入对话框与设置页里单独开关。
     */
    val traditionalToSimplified: Boolean = false,
) {
    companion object {
        /** 全部关闭。 */
        val NONE = CleanToggles()

        fun preset(level: CleanLevel): CleanToggles = when (level) {
            CleanLevel.CONSERVATIVE -> CleanToggles(
                unifyChars = true,
                trimLines = true,
                collapseSpaces = true,
                filterNoise = true,
            )

            CleanLevel.STANDARD, CleanLevel.CUSTOM -> CleanToggles(
                unifyChars = true,
                trimLines = true,
                collapseSpaces = true,
                canonicalIndent = true,
                filterNoise = true,
                exciseInlineNoise = true,
                maskRuns = true,
                reflowParagraphs = true,
                collapseBlankLines = true,
                repairChapters = true,
                dedupeChapterTitles = true,
                normalizePunctuation = true,
                normalizeQuotes = true,
            )

            CleanLevel.AGGRESSIVE -> preset(CleanLevel.STANDARD).copy(
                normalizeRepeatedPunctuation = true,
            )
        }

        /**
         * 有序开关表。**序列化、设置页逐项展示、备份导出都走这一份**，
         * 免得三处各写一遍然后慢慢漂移。
         */
        val ENTRIES: List<Entry> = listOf(
            Entry(
                "unifyChars",
                "字符归一",
                "不可见字符、全角空格/制表符、全角数字字母、HTML 实体与标签",
                { it.unifyChars },
                { t, v -> t.copy(unifyChars = v) },
            ),
            Entry("trimLines", "去行尾空白", "行末尾的空格与制表符", { it.trimLines }, { t, v -> t.copy(trimLines = v) }),
            Entry(
                "collapseSpaces",
                "行内空白归一",
                "连续空白折叠为一个；夹在两个中文字符之间的空格删除",
                { it.collapseSpaces },
                { t, v -> t.copy(collapseSpaces = v) },
            ),
            Entry(
                "canonicalIndent",
                "段首缩进统一",
                "有缩进的段落统一成两个全角空格，章节标题顶格",
                { it.canonicalIndent },
                { t, v -> t.copy(canonicalIndent = v) },
            ),
            Entry(
                "filterNoise",
                "去广告/噪音行",
                "站点推广、防盗声明、字数统计、作者的话、论坛残留、装饰线",
                { it.filterNoise },
                { t, v -> t.copy(filterNoise = v) },
            ),
            Entry(
                "exciseInlineNoise",
                "行内噪音切除",
                "只切掉句中夹带的网址与防盗尾标，保住正文",
                { it.exciseInlineNoise },
                { t, v -> t.copy(exciseInlineNoise = v) },
            ),
            Entry(
                "maskRuns",
                "遮罩符号规整",
                "连续的星号/方框等遮蔽符号统一成一个 ＊＊",
                { it.maskRuns },
                { t, v -> t.copy(maskRuns = v) },
            ),
            Entry(
                "reflowParagraphs",
                "段落重组",
                "把被硬换行拆开的段落拼回去（诗行、短句列表不会被动）",
                { it.reflowParagraphs },
                { t, v -> t.copy(reflowParagraphs = v) },
            ),
            Entry(
                "collapseBlankLines",
                "空行规整",
                "删除段中空行；缩进分段的书删掉全部空行，空行分段的只留一个",
                { it.collapseBlankLines },
                { t, v -> t.copy(collapseBlankLines = v) },
            ),
            Entry(
                "repairChapters",
                "章节标题修复",
                "行中的标题提出来单独成行，`第 1 章` 这类写法规范化",
                { it.repairChapters },
                { t, v -> t.copy(repairChapters = v) },
            ),
            Entry(
                "dedupeChapterTitles",
                "标题去重",
                "紧挨着重复的标题删一个；正文开头的目录块整体删除",
                { it.dedupeChapterTitles },
                { t, v -> t.copy(dedupeChapterTitles = v) },
            ),
            Entry(
                "normalizePunctuation",
                "标点规范",
                "省略号（......、。。。）统一成 ……，破折号统一成 ——",
                { it.normalizePunctuation },
                { t, v -> t.copy(normalizePunctuation = v) },
            ),
            Entry(
                "normalizeQuotes",
                "引号规整",
                "引号内侧与引号内两汉字之间的空格删除",
                { it.normalizeQuotes },
                { t, v -> t.copy(normalizeQuotes = v) },
            ),
            Entry(
                "normalizeRepeatedPunctuation",
                "重复标点折叠",
                "`！！！` → `！`、`，，` → `，`；`……` 与 `——` 不动",
                { it.normalizeRepeatedPunctuation },
                { t, v -> t.copy(normalizeRepeatedPunctuation = v) },
            ),
            Entry(
                "traditionalToSimplified",
                "繁体转简体",
                "单字级映射（OpenCC 字表），不做词组级消歧",
                { it.traditionalToSimplified },
                { t, v -> t.copy(traditionalToSimplified = v) },
            ),
        )

        /** 序列化成等长的 `01` 串，长度即 [ENTRIES] 的条数；长度不符视为无效。 */
        fun encode(toggles: CleanToggles): String =
            buildString(ENTRIES.size) { ENTRIES.forEach { append(if (it.get(toggles)) '1' else '0') } }

        fun decode(raw: String?): CleanToggles? {
            if (raw == null || raw.length != ENTRIES.size) return null
            var result = NONE
            ENTRIES.forEachIndexed { index, entry ->
                result = entry.set(result, raw[index] == '1')
            }
            return result
        }
    }

    data class Entry(
        val key: String,
        val label: String,
        val hint: String,
        val get: (CleanToggles) -> Boolean,
        val set: (CleanToggles, Boolean) -> CleanToggles,
    )
}

/**
 * 一次清洗的完整配方。
 *
 * [adPatterns] 由调用方从全局偏好编译后注入（与旧的 `CleanOptions.adPatterns` 一致），
 * 便于 JVM 单测不依赖 DataStore。
 */
data class CleanProfile(
    val level: CleanLevel = CleanLevel.STANDARD,
    val toggles: CleanToggles = CleanToggles.preset(level),
    val adPatterns: List<Regex> = emptyList(),
) {
    /** 什么都不做：导入链路据此跳过副本物化（与旧 `CleanOptions.isNoop` 同义）。 */
    val isNoop: Boolean
        get() = toggles == CleanToggles.NONE && adPatterns.isEmpty()

    companion object {
        val NONE = CleanProfile(level = CleanLevel.CUSTOM, toggles = CleanToggles.NONE)
    }
}

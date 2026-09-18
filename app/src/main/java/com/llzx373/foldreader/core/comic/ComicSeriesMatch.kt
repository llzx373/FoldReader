package com.llzx373.foldreader.core.comic

/**
 * 「同系列」漫画的识别与排序。
 *
 * 漫画没有内嵌目录，前后卷切换只能靠文件名推断。这里全是纯函数：命名规则千奇百怪，
 * 判断依据必须能拿出来逐条单测，否则线上只会表现为「有时候能切、有时候不能」。
 *
 * 刻意的取舍：
 * - **不做模糊/相似度匹配**，只做「归一化后主干相等」。相似度会带来"看起来很聪明"的误判
 *   （把《Foo》《Foo 外传》乃至《Foo 2》之外的无关卷并到一起），而误判的代价是跳到一本不相干的书；
 * - 卷号识别不出来时（如「番外」「SP」）退化为名字自然序——数字会把它们排在有序卷之后，
 *   这通常正是想要的顺序。
 */

/** 一个「同系列」候选。[bookId] 为 null 表示文件在目录里但还没导入。 */
data class ComicSeriesCandidate(
    val name: String,
    val uri: String,
    val bookId: Long? = null,
    val isDirectory: Boolean = false,
)

/**
 * 归一化出「系列主干」：去扩展名 → 去括号标签 → 全角转半角 → 非字母数字（含中文）压成单空格。
 *
 * 注意主干**不含卷号**：`Foo 第01卷` 与 `Foo 第02卷` 的主干都是 `foo`，
 * 这样才能靠主干相等判断「同一系列」。
 */
fun comicSeriesStem(name: String): String {
    val base = comicBaseName(name)
    val noTags = base.replace(BRACKET_TAGS, " ")
    val folded = foldFullWidth(noTags)
    val withoutVolume = VOLUME_PATTERNS.fold(folded) { acc, regex -> regex.replace(acc, " ") }
    return withoutVolume
        .lowercase()
        .map { if (it.isLetterOrDigit()) it else ' ' }
        .joinToString("")
        .trim()
        .replace(WHITESPACE_RUN, " ")
}

/**
 * 从文件名里取卷号；识别不出返回 null。
 *
 * 覆盖中文（第 3 卷 / 第 3 话 / 第 3 册）与英文（v03 / vol.3 / volume 3 / ch.3 / c003）
 * 以及常见的尾部编号（` - 03`、`_03`、`(03)`）。中文数字按 1..99 之内的常见写法解析。
 */
fun comicSeriesVolume(name: String): Int? {
    val base = foldFullWidth(comicBaseName(name)).lowercase()
    for (regex in VOLUME_EXTRACTORS) {
        val m = regex.find(base) ?: continue
        val raw = m.groupValues.getOrNull(1).orEmpty()
        parseVolumeNumber(raw)?.let { return it }
    }
    return null
}

/**
 * 两个文件名是否属于同一系列。
 *
 * 判据只有「主干相等」——`Foo 第01卷` 与 `Foo 第02卷` 主干都是 `foo`；
 * 而 `Foo 外传` 的主干是 `foo 外传`，不会被并进来（宁可漏也不能错跳）。
 */
fun isSameComicSeries(a: String, b: String): Boolean {
    val sa = comicSeriesStem(a)
    val sb = comicSeriesStem(b)
    return sa.isNotEmpty() && sa == sb
}

/** 同系列排序：都有卷号按卷号；只有一边有卷号时，**有卷号的排在前面**（有序卷在前、番外在后）。 */
fun compareComicSeries(a: String, b: String): Int {
    val va = comicSeriesVolume(a)
    val vb = comicSeriesVolume(b)
    if (va != null && vb == null) return -1
    if (va == null && vb != null) return 1
    if (va != null && vb != null && va != vb) return va.compareTo(vb)
    return ComicPageOrdering.compareNatural(a, b)
}

/**
 * 合并两个来源并挑出同系列成员：
 * 同目录扫到的文件（可能未导入）与已入库的书（可能不在同一目录）。
 *
 * 同一物理文件只保留一条，且**库内那条优先**（它带着 bookId，点进去就能直接打开）。
 */
fun mergeComicSeries(
    current: ComicSeriesCandidate,
    directory: List<ComicSeriesCandidate>,
    library: List<ComicSeriesCandidate>,
): List<ComicSeriesCandidate> {
    val byUri = LinkedHashMap<String, ComicSeriesCandidate>()
    directory.forEach { byUri[it.uri] = it }
    // 库内覆盖同 uri 的目录条目：带上 bookId 才能直接跳转
    library.forEach { candidate ->
        val existing = byUri[candidate.uri]
        byUri[candidate.uri] = if (existing == null) {
            candidate
        } else {
            existing.copy(bookId = candidate.bookId, name = candidate.name.ifEmpty { existing.name })
        }
    }
    byUri[current.uri] = current

    return byUri.values
        .filter { it.uri == current.uri || isSameComicSeries(it.name, current.name) }
        .sortedWith { a, b -> compareComicSeries(a.name, b.name) }
}

/** 去掉扩展名的文件名（没有扩展名时原样返回）。 */
fun comicBaseName(name: String): String {
    val dot = name.lastIndexOf('.')
    // 前导点或没有点都当作没有扩展名；`.cbz` 这种隐藏文件也按无扩展名处理
    return if (dot <= 0) name else name.substring(0, dot)
}

/** 全角 ASCII（！-～）与全角空格折成半角。 */
internal fun foldFullWidth(text: String): String = text.map { ch ->
    when {
        ch.code in 0xFF01..0xFF5E -> (ch.code - 0xFEE0).toChar()
        ch.code == 0x3000 -> ' '
        else -> ch
    }
}.joinToString("")

/** 括号标签：`[汉化组]`、`【限定版】`、`(作者)`、`（全彩）`。 */
private val BRACKET_TAGS = Regex("[\\[［【(（][^\\]］】)）]*[\\]］】)）]")

private val WHITESPACE_RUN = Regex("\\s+")

/** 主干里要抹掉的卷号写法（抹掉后剩下的才是「同一系列」的判据）。 */
private val VOLUME_PATTERNS = listOf(
    // 巻 是日文异体（日漫常见），与中文的 卷 都要认
    Regex("第\\s*[0-9零一二三四五六七八九十百千两]+\\s*[卷巻章话話册冊集部篇回]"),
    Regex("(?:vol|volume|v|ch|chapter|c)\\.?\\s*\\d{1,4}(?![0-9])"),
    Regex("\\(\\s*\\d{1,4}\\s*\\)"),
    Regex("(?<![0-9])-\\s*\\d{1,4}(?![0-9])"),
    Regex("_\\s*\\d{1,4}(?![0-9])"),
    Regex("\\s\\d{1,4}(?![0-9])"),
)

/** 卷号提取器：顺序有意义，先试中文，再试英文前缀，最后试尾部编号。 */
private val VOLUME_EXTRACTORS = listOf(
    Regex("第\\s*([0-9零一二三四五六七八九十百千两]+)\\s*[卷巻章话話册冊集部篇回]"),
    Regex("(?:vol|volume)\\.?\\s*(\\d{1,4})(?![0-9])"),
    Regex("\\bv\\.?\\s*(\\d{1,4})(?![0-9])"),
    Regex("(?:ch|chapter|c)\\.?\\s*(\\d{1,4})(?![0-9])"),
    Regex("\\(\\s*(\\d{1,4})\\s*\\)"),
    Regex("(?<![0-9])-\\s*(\\d{1,4})(?![0-9])"),
    Regex("_\\s*(\\d{1,4})(?![0-9])"),
    Regex("\\s(\\d{1,4})(?![0-9])"),
)

/** 解析卷号：纯数字直接取；中文数字按常见的个/十/百组合解析。 */
internal fun parseVolumeNumber(raw: String): Int? {
    val text = raw.trim()
    if (text.isEmpty()) return null
    text.toIntOrNull()?.let { return it }

    var total = 0
    var section = 0
    var digit = 0
    var sawAny = false
    for (ch in text) {
        val d = CN_DIGITS[ch]
        if (d != null) {
            digit = d
            sawAny = true
            continue
        }
        val unit = CN_UNITS[ch] ?: return null
        sawAny = true
        section += (if (digit == 0) 1 else digit) * unit
        digit = 0
    }
    if (!sawAny) return null
    total += section + digit
    return total.takeIf { it > 0 }
}

private val CN_DIGITS: Map<Char, Int> = mapOf(
    '零' to 0, '〇' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
    '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9,
)

private val CN_UNITS: Map<Char, Int> = mapOf('十' to 10, '百' to 100, '千' to 1000)

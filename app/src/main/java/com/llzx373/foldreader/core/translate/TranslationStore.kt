package com.llzx373.foldreader.core.translate

import com.llzx373.foldreader.core.format.Chapter
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 一个已译单位的落盘内容（`unit_<index>.json`）。 */
@Serializable
data class TranslatedUnitContent(
    val title: String,
    val paragraphs: List<String>,
)

/** 单位清单的落盘包装（`units.json`）。 */
@Serializable
private data class UnitsManifest(val units: List<TranslationUnit>)

/**
 * 译本副本存储（M19，docs/AI功能需求与实施.md 5.0「译本即副本」）。
 *
 * 目录结构：`filesDir/translations/<bookId>/<lang>/`
 * - `units.json`：单位清单（切块器产出的 [TranslationUnit] 列表），流重写与占位对齐的依据；
 * - `unit_<index>.json`：每个已译单位的标题 + 段落数组（段落结构化落盘，供视角 3 直接使用）；
 * - `content.txt` + `content.toc`：全部单位按序拼接的译本流（未译单位写占位段
 *   [UNTRANSLATED_PLACEHOLDER]，保证单位对齐），TSV 目录格式与转义规则同
 *   `ConvertedBookStore.writeToc`，偏移为译本流字符偏移（半开区间）。
 *
 * 纯 JVM（只依赖 java.io 与 kotlinx.serialization），删除单位 / 重译单位后整体重写流。
 * 公开方法全部 `@Synchronized`：后台翻译与阅读侧读取可能并发，文件级串行即可。
 */
class TranslationStore(private val translationsDir: File) {

    private val json = Json { ignoreUnknownKeys = true }

    private fun langDir(bookId: Long, lang: String): File =
        File(File(translationsDir, bookId.toString()), lang)

    private fun unitsFile(bookId: Long, lang: String) = File(langDir(bookId, lang), "units.json")
    private fun unitFile(bookId: Long, lang: String, unitIndex: Int) =
        File(langDir(bookId, lang), "unit_$unitIndex.json")
    private fun contentFile(bookId: Long, lang: String) = File(langDir(bookId, lang), "content.txt")
    private fun tocFile(bookId: Long, lang: String) = File(langDir(bookId, lang), "content.toc")

    /**
     * 落单位清单：翻译发起方（切块后）先调一次，之后的 [saveUnit] / [deleteUnit]
     * 才能按「未译单位占位」重写译本流；同时初始化全占位的 content.txt / content.toc。
     */
    @Synchronized
    fun saveUnits(bookId: Long, lang: String, units: List<TranslationUnit>) {
        langDir(bookId, lang).mkdirs()
        unitsFile(bookId, lang).writeText(json.encodeToString(UnitsManifest(units)))
        rewriteStream(bookId, lang, units)
    }

    /** 读取单位清单；未落过或文件损坏返回 null。 */
    @Synchronized
    fun readUnits(bookId: Long, lang: String): List<TranslationUnit>? {
        val file = unitsFile(bookId, lang)
        if (!file.isFile) return null
        return runCatching {
            json.decodeFromString<UnitsManifest>(file.readText()).units
        }.getOrNull()
    }

    /**
     * 落一个已译单位（写 `unit_<index>.json`），并按单位清单重写 content.txt / content.toc。
     * 清单缺失（未 [saveUnits] 过）时只落单位 json、不重写流。
     */
    @Synchronized
    fun saveUnit(bookId: Long, lang: String, unit: TranslationUnit, paragraphs: List<String>) {
        langDir(bookId, lang).mkdirs()
        unitFile(bookId, lang, unit.index)
            .writeText(json.encodeToString(TranslatedUnitContent(unit.title, paragraphs)))
        readUnits(bookId, lang)?.let { rewriteStream(bookId, lang, it) }
    }

    /** 读取一个已译单位；未译 / 文件损坏返回 null。 */
    @Synchronized
    fun loadUnit(bookId: Long, lang: String, unitIndex: Int): TranslatedUnitContent? {
        val file = unitFile(bookId, lang, unitIndex)
        if (!file.isFile) return null
        return runCatching { json.decodeFromString<TranslatedUnitContent>(file.readText()) }.getOrNull()
    }

    /** 删除一个单位的译文（重译前 / 单位作废），删除后按清单重写译本流。 */
    @Synchronized
    fun deleteUnit(bookId: Long, lang: String, unitIndex: Int) {
        unitFile(bookId, lang, unitIndex).delete()
        readUnits(bookId, lang)?.let { rewriteStream(bookId, lang, it) }
    }

    /**
     * 拼装译本全文与译本章节列表（不落盘，供预览 / 测试 / 阅读侧直接使用）。
     *
     * 已译单位用其落盘标题与段落，未译单位写占位段——单位序号与 [units] 一一对齐。
     * 返回的 [Chapter] 偏移为译本流字符偏移（半开区间，单位间以一个换行分隔，
     * 分隔符属于前一单位之外、不计入任何单位区间）。
     */
    @Synchronized
    fun assemble(bookId: Long, lang: String, units: List<TranslationUnit>): Pair<String, List<Chapter>> {
        val text = StringBuilder()
        val chapters = ArrayList<Chapter>(units.size)
        for (unit in units) {
            if (text.isNotEmpty()) text.append('\n')
            val start = text.length
            val translated = loadUnit(bookId, lang, unit.index)
            text.append(translated?.paragraphs?.joinToString("\n") ?: UNTRANSLATED_PLACEHOLDER)
            chapters += Chapter(
                title = translated?.title ?: unit.title,
                charStart = start.toLong(),
                charEnd = text.length.toLong(),
            )
        }
        return text.toString() to chapters
    }

    /** 该书是否有任何语言的译本产物。 */
    fun hasAny(bookId: Long): Boolean =
        File(translationsDir, bookId.toString()).listFiles()
            ?.any { dir -> dir.isDirectory && dir.listFiles()?.any { it.name.startsWith("unit_") } == true }
            ?: false

    /** 该书该语言是否已有已译单位。 */
    fun hasAny(bookId: Long, lang: String): Boolean =
        langDir(bookId, lang).listFiles()?.any { it.name.startsWith("unit_") } ?: false

    /** 读取译本目录（content.toc）；未生成 / 损坏返回 null。 */
    @Synchronized
    fun readToc(bookId: Long, lang: String): List<Chapter>? {
        val file = tocFile(bookId, lang)
        if (!file.isFile) return null
        return runCatching {
            file.readLines(Charsets.UTF_8).map { line ->
                val parts = line.split('\t')
                require(parts.size >= 3) { "损坏的译本目录行" }
                Chapter(
                    title = unescapeTocField(parts.subList(2, parts.size).joinToString("\t")),
                    charStart = parts[0].toLong(),
                    charEnd = parts[1].toLong(),
                )
            }
        }.getOrNull()
    }

    /** 删除该书全部语言的译本目录（删书 / 「清除全部 AI 数据」）。 */
    @Synchronized
    fun deleteBook(bookId: Long) {
        File(translationsDir, bookId.toString()).deleteRecursively()
    }

    /** 按单位清单重写 content.txt 与 content.toc（未译单位占位入流）。 */
    private fun rewriteStream(bookId: Long, lang: String, units: List<TranslationUnit>) {
        val (text, chapters) = assemble(bookId, lang, units)
        contentFile(bookId, lang).writeText(text, Charsets.UTF_8)
        writeToc(tocFile(bookId, lang), chapters)
    }

    /** TSV 目录：charStart \t charEnd \t 转义标题，与 ConvertedBookStore.writeToc 同一格式。 */
    private fun writeToc(file: File, chapters: List<Chapter>) {
        file.bufferedWriter(Charsets.UTF_8).use { writer ->
            for (chapter in chapters) {
                writer.write(chapter.charStart.toString())
                writer.write('\t'.code)
                writer.write(chapter.charEnd.toString())
                writer.write('\t'.code)
                writer.write(escapeTocField(chapter.title))
                writer.write('\n'.code)
            }
        }
    }

    // 转义规则与 ConvertedBookStore 的 toc sidecar 保持一致（\t \n \r \\ 四码）
    private fun escapeTocField(s: String): String = buildString(s.length) {
        for (ch in s) {
            when (ch) {
                '\\' -> append("\\\\")
                '\t' -> append("\\t")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                else -> append(ch)
            }
        }
    }

    private fun unescapeTocField(s: String): String = buildString(s.length) {
        var i = 0
        while (i < s.length) {
            if (s[i] == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    '\\' -> append('\\')
                    't' -> append('\t')
                    'n' -> append('\n')
                    'r' -> append('\r')
                    else -> append(s[i + 1])
                }
                i += 2
            } else {
                append(s[i])
                i += 1
            }
        }
    }
}

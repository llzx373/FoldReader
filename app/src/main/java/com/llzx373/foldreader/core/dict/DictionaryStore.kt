package com.llzx373.foldreader.core.dict

import java.io.File
import java.io.InputStream

/** 一部已导入词典的摘要信息（列表展示用）。 */
data class DictInfo(
    /** 目录名（导入时安全化的词干），删除时按它定位。 */
    val id: String,
    val bookName: String,
    val wordCount: Long,
)

/**
 * 词典文件管理（M28）：词典统一放 `filesDir/dicts/<id>/`，每部词典一组
 * `<stem>.ifo` + `<stem>.idx` + `<stem>.dict`（`.dict.dz` 导入时解压成 `.dict`）。
 *
 * 纯 JVM 零 android import：SAF 的文件读取由调用方以 [openFile] 注入，本类只认流。
 */
class DictionaryStore(private val dictsDir: File) {

    /** 导入失败原因；message 直接面向用户。 */
    class ImportException(message: String) : Exception(message)

    /** 已导入词典列表（按 ifo 里的 bookname 排序）。 */
    fun list(): List<DictInfo> {
        val dirs = dictsDir.listFiles { f -> f.isDirectory } ?: return emptyList()
        return dirs.mapNotNull { dir ->
            val ifoFile = dir.listFiles { f -> f.extension == "ifo" }?.firstOrNull() ?: return@mapNotNull null
            val ifo = runCatching {
                if (ifoFile.length() > MAX_IFO_BYTES) null else StarDictIfoParser.parse(ifoFile.readText())
            }.getOrNull() ?: return@mapNotNull null
            DictInfo(id = dir.name, bookName = ifo.bookName, wordCount = ifo.wordCount)
        }.sortedBy { it.bookName }
    }

    fun hasDictionaries(): Boolean = list().isNotEmpty()

    /** 词典目录（装配查词用）；不存在返回 null。 */
    fun dirOf(id: String): File? = File(dictsDir, id).takeIf { it.isDirectory }

    fun delete(id: String): Boolean = dirOf(id)?.deleteRecursively() ?: false

    /**
     * 从一组同名文件导入一部词典：[stem] 是词干（文件名去扩展名），
     * [openFile] 按完整文件名（如 `oxford.idx`）给输入流，不存在返回 null。
     *
     * 需要 `<stem>.ifo` + `<stem>.idx` + `<stem>.dict` 或 `<stem>.dict.dz`；
     * MDict（.mdx/.mdd）明确不支持（格式多变体且可能加密），报清楚的错。
     */
    fun import(stem: String, openFile: (fileName: String) -> InputStream?): DictInfo {
        if (openFile("$stem.mdx") != null || openFile("$stem.mdd") != null) {
            throw ImportException("MDict 格式暂不支持（格式多变体且可能加密），请改用 StarDict 格式（.ifo + .idx + .dict）")
        }
        val id = sanitize(stem)
        if (id.isEmpty()) throw ImportException("词典文件名不合法")
        val target = File(dictsDir, id)
        target.deleteRecursively()
        target.mkdirs()
        try {
            copyRequired(openFile, "$stem.ifo", File(target, "$stem.ifo"))
            copyRequired(openFile, "$stem.idx", File(target, "$stem.idx"))
            val dictOut = File(target, "$stem.dict")
            val plain = openFile("$stem.dict")
            when {
                plain != null -> plain.use { input -> dictOut.outputStream().use { input.copyTo(it) } }
                else -> {
                    val dz = openFile("$stem.dict.dz")
                        ?: throw ImportException("缺少 $stem.dict（或 .dict.dz）")
                    dz.use { input -> dictOut.outputStream().use { inflateDictDz(input, it) } }
                }
            }
            // 落地后立刻解析一遍：坏词典当场报错，不留半成品
            val ifo = StarDictIfoParser.parse(File(target, "$stem.ifo").readText())
                ?: throw ImportException("$stem.ifo 不是合法的 StarDict 描述文件")
            val idxFile = File(target, "$stem.idx")
            if (idxFile.length() > MAX_IDX_BYTES) throw ImportException("索引文件过大（>${MAX_IDX_BYTES / (1024 * 1024)}MB）")
            val entries = idxFile.inputStream().use { StarDictIndex.parse(it, ifo.idxFileBits) }
            if (entries.isEmpty()) throw ImportException("索引为空或损坏")
            return DictInfo(id = id, bookName = ifo.bookName, wordCount = ifo.wordCount)
        } catch (e: ImportException) {
            target.deleteRecursively()
            throw e
        } catch (e: Exception) {
            target.deleteRecursively()
            throw ImportException("导入失败：${e.message ?: "文件读取异常"}")
        }
    }

    private fun copyRequired(
        openFile: (String) -> InputStream?,
        name: String,
        target: File,
    ) {
        val input = openFile(name) ?: throw ImportException("缺少 $name")
        input.use { source -> target.outputStream().use { source.copyTo(it) } }
    }

    companion object {
        private val UNSAFE = Regex("[^\\w\\-.一-鿿 ]")

        /** 词干安全化为目录名：去掉路径分隔与奇怪字符，空白收敛。 */
        internal fun sanitize(stem: String): String =
            stem.replace(UNSAFE, "_").trim().trim('.').take(64)

        /** 一组文件名里能凑齐 ifo+idx+(dict|dict.dz) 的词干（目录导入的候选发现）。 */
        fun findStems(fileNames: List<String>): List<String> {
            val lower = fileNames.map { it to it.lowercase() }
            val stems = lower.filter { it.second.endsWith(".ifo") }
                .map { it.first.dropLast(4) }
            return stems.filter { stem ->
                val has = { suffix: String ->
                    lower.any { it.second.equals(stem.lowercase() + suffix) }
                }
                has(".idx") && (has(".dict") || has(".dict.dz"))
            }
        }
    }
}

package com.llzx373.foldreader.core.dict

import java.io.Closeable
import java.io.File

/** 查词结果。 */
sealed interface LookupOutcome {
    /** 本地词典命中；[dictName] 是命中的词典名（多词典按导入顺序首个命中）。 */
    data class LocalHit(
        val word: String,
        val definition: String,
        val dictName: String,
    ) : LookupOutcome

    /** 本地未命中（含没装词典）；[aiAvailable] = 已配置 AI，可回落 AI 解释。 */
    data class Miss(val aiAvailable: Boolean) : LookupOutcome
}

/**
 * 查词服务（M28）：本地词典优先，未命中由调用方决定回落 AI（[LookupOutcome.Miss] 带出
 * [LookupOutcome.Miss.aiAvailable] 供 UI 决定是否显示「AI 解释」入口）。
 *
 * 本地查词零网络零确认。词典惰性装配（首次查词才建索引），实例线程安全。
 */
class DictionaryLookupService(
    private val dictsDir: File,
    /** AI 可用性判据（已启用 + 已配地址 + 已存 key）；注入以便纯 JVM 可测。 */
    private val aiAvailable: suspend () -> Boolean = { false },
) : Closeable {

    private val store = DictionaryStore(dictsDir)
    private val lock = Any()
    /** id → 已装配词典（惰性）；装配失败的词典记 null，不在每次查词时反复报错。 */
    private val opened = HashMap<String, StarDictDictionary?>()
    private var listed: List<DictInfo>? = null

    fun hasDictionaries(): Boolean = synchronized(lock) { dictInfos().isNotEmpty() }

    fun dictionaryCount(): Int = synchronized(lock) { dictInfos().size }

    /**
     * 查询词归一化：去首尾空白与常见引号/标点；纯空白或超长（>32 字符，更像句子）
     * 返回 null——调用方据此不提供查词入口。
     */
    fun normalize(text: String): String? {
        val cleaned = text.trim().trim('\'', '"', '“', '”', '‘', '’', '，', '。', '、', ',', '.', ';', ':', '；', '：', '?', '？', '!', '！')
        return cleaned.takeIf { it.isNotEmpty() && it.length <= MAX_WORD_CHARS }
    }

    suspend fun lookup(rawText: String): LookupOutcome {
        val word = normalize(rawText) ?: return LookupOutcome.Miss(aiAvailable())
        synchronized(lock) {
            dictInfos().forEach { info ->
                val dict = open(info) ?: return@forEach
                val hit = runCatching { dict.lookup(word) }.getOrNull()
                if (hit != null) {
                    return LookupOutcome.LocalHit(word = word, definition = hit, dictName = info.bookName)
                }
            }
        }
        return LookupOutcome.Miss(aiAvailable())
    }

    /** 词典增删后调用：清掉清单与已装配缓存，下次查词重新扫描。 */
    fun invalidate() {
        synchronized(lock) {
            opened.values.forEach { it?.close() }
            opened.clear()
            listed = null
        }
    }

    private fun dictInfos(): List<DictInfo> =
        listed ?: store.list().also { listed = it }

    private fun open(info: DictInfo): StarDictDictionary? {
        if (opened.containsKey(info.id)) return opened[info.id]
        val dict = runCatching {
            val dir = store.dirOf(info.id) ?: return null
            val stem = dir.listFiles { f -> f.extension == "ifo" }?.firstOrNull()
                ?.nameWithoutExtension ?: return null
            val ifoFile = File(dir, "$stem.ifo")
            if (ifoFile.length() > MAX_IFO_BYTES) return null
            val ifo = StarDictIfoParser.parse(ifoFile.readText()) ?: return null
            val idxFile = File(dir, "$stem.idx")
            if (idxFile.length() > MAX_IDX_BYTES) return null
            val entries = idxFile.inputStream().use { StarDictIndex.parse(it, ifo.idxFileBits) }
            if (entries.isEmpty()) return null
            StarDictDictionary(ifo, entries, RandomAccessDictData(File(dir, "$stem.dict")))
        }.getOrNull()
        opened[info.id] = dict
        return dict
    }

    override fun close() {
        synchronized(lock) {
            opened.values.forEach { it?.close() }
            opened.clear()
            listed = null
        }
    }

    companion object {
        /** 查词上限：超过按句子处理，不进词典。 */
        const val MAX_WORD_CHARS = 32
    }
}

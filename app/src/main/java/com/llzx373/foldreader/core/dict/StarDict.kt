package com.llzx373.foldreader.core.dict

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.zip.GZIPInputStream

/**
 * StarDict 纯 JVM 解析层（M28）：.ifo 元信息 + .idx 索引（流式建索引、二分查词）
 * + .dict 释义数据读取（.dict.dz 为 gzip，导入时解压成 .dict）。
 *
 * 零 android import、零网络——本地查词全程离线。
 * MDict（.mdx/.mdd）格式复杂且有加密变体，明确不支持，由导入层识别并报错。
 */

/** .ifo 头部信息。 */
data class StarDictIfo(
    val bookName: String,
    val wordCount: Long,
    /** 释义数据格式序列（m=纯文本 / g=pango / h=html / x=xdxf …）；未知一律按纯文本兜底。 */
    val sameTypeSequence: String = "m",
    /** idx 里偏移/长度字段位宽：32（默认）或 64。 */
    val idxFileBits: Int = 32,
)

object StarDictIfoParser {

    /** 解析 .ifo 文本；缺 magic / 缺 wordcount 视为非法词典，返回 null。 */
    fun parse(text: String): StarDictIfo? {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.firstOrNull() != "StarDict's dict ifo file") return null
        val fields = lines.drop(1).mapNotNull { line ->
            val eq = line.indexOf('=')
            if (eq <= 0) null else line.substring(0, eq) to line.substring(eq + 1)
        }.toMap()
        val wordCount = fields["wordcount"]?.toLongOrNull() ?: return null
        return StarDictIfo(
            bookName = fields["bookname"].orEmpty().ifBlank { "未命名词典" },
            wordCount = wordCount,
            sameTypeSequence = fields["sametypesequence"] ?: "m",
            idxFileBits = fields["idxfilebits"]?.toIntOrNull() ?: 32,
        )
    }
}

/** 一条索引项；[wordBytes] 是词目的 UTF-8 原文（排序与二分都按无符号字节序）。 */
data class StarDictEntry(
    val word: String,
    val wordBytes: ByteArray,
    val offset: Long,
    val size: Int,
) {
    override fun equals(other: Any?): Boolean =
        other is StarDictEntry && word == other.word && offset == other.offset && size == other.size

    override fun hashCode(): Int = word.hashCode() * 31 + offset.hashCode()
}

object StarDictIndex {

    /** 无符号字节序比较：StarDict 词目按 UTF-8 strcmp 排序，二分查词必须同口径。 */
    val BYTE_ORDER: Comparator<ByteArray> = Comparator { a, b ->
        val n = minOf(a.size, b.size)
        for (i in 0 until n) {
            val d = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (d != 0) return@Comparator d
        }
        a.size - b.size
    }

    /**
     * 流式解析 .idx：`词目\0 + 偏移 + 长度` 连续记录。整文件不一次性进内存——
     * 逐字节读词目虽朴素，但 .idx 通常只有几 MB，一次解析后常驻的只有条目数组。
     * 产出按词目字节序排序（多数词典本来就有序，排序是为不信任文件顺序兜底）。
     */
    fun parse(input: InputStream, idxFileBits: Int = 32): List<StarDictEntry> {
        require(idxFileBits == 32 || idxFileBits == 64) { "idxfilebits 只支持 32/64" }
        val entries = ArrayList<StarDictEntry>()
        val wordBuf = ByteArrayOutputStream(64)
        val num = ByteArray(if (idxFileBits == 64) 8 else 4)
        while (true) {
            wordBuf.reset()
            var c = input.read()
            if (c < 0) break
            while (c > 0) {
                wordBuf.write(c)
                c = input.read()
            }
            if (wordBuf.size() == 0) continue
            val offset = readUint(input, num) ?: break
            val size = readUint(input, num) ?: break
            if (size <= 0 || size > Int.MAX_VALUE) break
            val bytes = wordBuf.toByteArray()
            entries += StarDictEntry(
                word = String(bytes, Charsets.UTF_8),
                wordBytes = bytes,
                offset = offset,
                size = size.toInt(),
            )
        }
        entries.sortWith { a, b -> BYTE_ORDER.compare(a.wordBytes, b.wordBytes) }
        return entries
    }

    private fun readUint(input: InputStream, buf: ByteArray): Long? {
        var read = 0
        while (read < buf.size) {
            val n = input.read(buf, read, buf.size - read)
            if (n < 0) return null
            read += n
        }
        var v = 0L
        for (b in buf) v = (v shl 8) or (b.toLong() and 0xFF)
        return v
    }
}

/** .dict 数据源的随机访问抽象（测试可注内存实现）。 */
fun interface DictDataSource {
    fun read(offset: Long, size: Int): ByteArray
}

/** RandomAccessFile 版数据源：只按命中区间 seek 读取，整本 .dict 不进内存。 */
class RandomAccessDictData(file: File) : DictDataSource, Closeable {
    private val raf = RandomAccessFile(file, "r")

    @Synchronized
    override fun read(offset: Long, size: Int): ByteArray {
        raf.seek(offset)
        val buf = ByteArray(size)
        raf.readFully(buf)
        return buf
    }

    override fun close() = raf.close()
}

/** 一本已装配的 StarDict 词典：索引常驻内存，释义按命中区间现读。 */
class StarDictDictionary(
    val ifo: StarDictIfo,
    val entries: List<StarDictEntry>,
    private val data: DictDataSource,
) : Closeable {

    /**
     * 查词：精确命中 → 全小写 → 首字母大写，三档依次尝试（覆盖英语大小写变体）。
     * 同一词目的多条释义（索引中相邻同词）按顺序拼接。未命中返回 null。
     */
    fun lookup(word: String): String? {
        val trimmed = word.trim()
        if (trimmed.isEmpty()) return null
        val hit = lookupExact(trimmed)
            ?: trimmed.lowercase().takeIf { it != trimmed }?.let(::lookupExact)
            ?: trimmed.replaceFirstChar { it.uppercase() }
                .takeIf { it != trimmed }?.let(::lookupExact)
        return hit
    }

    private fun lookupExact(word: String): String? {
        val key = word.toByteArray(Charsets.UTF_8)
        // 按词目 UTF-8 无符号字节序二分（与建索引的排序口径一致）
        var low = 0
        var high = entries.size - 1
        var index = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val cmp = StarDictIndex.BYTE_ORDER.compare(entries[mid].wordBytes, key)
            when {
                cmp < 0 -> low = mid + 1
                cmp > 0 -> high = mid - 1
                else -> {
                    index = mid
                    break
                }
            }
        }
        if (index < 0) return null
        // 同词目可能有多条（连续排列）：回退到第一条，顺次拼接
        while (index > 0 &&
            StarDictIndex.BYTE_ORDER.compare(entries[index - 1].wordBytes, key) == 0
        ) {
            index--
        }
        val parts = ArrayList<String>()
        while (index < entries.size &&
            StarDictIndex.BYTE_ORDER.compare(entries[index].wordBytes, key) == 0
        ) {
            val e = entries[index]
            parts += StarDictMarkup.toPlainText(
                String(data.read(e.offset, e.size), Charsets.UTF_8),
                ifo.sameTypeSequence,
            )
            index++
        }
        return parts.joinToString("\n\n").trim().takeIf { it.isNotEmpty() }
    }

    override fun close() {
        (data as? Closeable)?.close()
    }
}

/** 释义文本的最小清理：按 sametypesequence 去标记，未知格式按纯文本。 */
object StarDictMarkup {

    private val TAG = Regex("<[^>]+>")
    private val ENTITIES = mapOf(
        "&amp;" to "&", "&lt;" to "<", "&gt;" to ">",
        "&quot;" to "\"", "&#39;" to "'", "&apos;" to "'", "&nbsp;" to " ",
    )

    /**
     * m（纯文本）原样返回；g/h/x 等含标记的格式剥掉标签并解码常见实体。
     * 不做排版渲染——卡片上是纯文本展示，释义换行保留。
     */
    fun toPlainText(raw: String, sameTypeSequence: String): String {
        val seq = sameTypeSequence.lowercase()
        val needsStrip = seq.any { it == 'h' || it == 'x' || it == 'g' } || raw.contains('<')
        if (!needsStrip) return raw
        var out = raw.replace(TAG, "")
        ENTITIES.forEach { (k, v) -> out = out.replace(k, v) }
        return out
    }
}

/** .dict.dz（gzip）流式解压成 .dict；返回解压后的字节数。 */
fun inflateDictDz(input: InputStream, output: OutputStream): Long {
    var total = 0L
    GZIPInputStream(input).use { gz ->
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = gz.read(buf)
            if (n < 0) break
            output.write(buf, 0, n)
            total += n
        }
    }
    return total
}

private const val MB = 1024 * 1024

/** .ifo 单文件上限（防御：正常不到 1KB）。 */
internal const val MAX_IFO_BYTES = 64 * 1024

/** .idx 索引上限（防御性，正常大词典几 MB~几十 MB）。 */
internal const val MAX_IDX_BYTES = 256 * MB

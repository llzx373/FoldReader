package com.llzx373.foldreader.core.format

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.SeekableByteChannel
import java.util.UUID

internal data class FlattenedBook(
    val file: File,
    val chapters: List<Chapter>,
    /** 本次是否新压平（false = 命中缓存）。 */
    val fresh: Boolean,
    /** 锚点表：「zip 内路径」→ 该项文本起点；「路径#id」→ 元素首个文本起点。 */
    val anchors: Map<String, Long>,
    /** 纸书页码（EPUB page-list 解析结果；无 page-list 为空列表）。 */
    val pageLabels: List<PageLabel>,
    /** 样式/结构 span（EPUB 压平规范 v3 起记录；无样式书为空列表）。 */
    val spans: List<TextSpan>,
)

/** 压平产物：章节 + 锚点表 + 纸书页码 + span（FB2 等无导航/样式增强的格式后三项留空）。 */
internal data class FlattenContent(
    val chapters: List<Chapter>,
    val anchors: Map<String, Long> = emptyMap(),
    val pageLabels: List<PageLabel> = emptyList(),
    val spans: List<TextSpan> = emptyList(),
)

/**
 * 非 TXT 格式共用的压平缓存：`convertedDir/<contentHash>.txt`（UTF-8 纯文本）
 * + `<contentHash>.toc` sidecar（章节 charStart/charEnd/title）
 * + `<contentHash>.anchors` / `<contentHash>.pages` sidecar（锚点表 / 纸书页码，可空内容但必须存在）
 * + `<contentHash>.spans` sidecar（样式/结构 span，可空内容但必须存在）
 * + `<contentHash>.version` sidecar（压平规范版本号）。
 * 内嵌图片抽取到 `<contentHash>.images/`（zip 路径打平为文件名）。
 * contentHash 为原书采样哈希（与导入去重一致），缓存有效性 = 目标文件存在且非空 + 四个 sidecar 均可读
 * + 版本号等于 [FLATTEN_VERSION]；旧缓存缺 sidecar 或版本不匹配时自动重压平升级。
 */
internal class ConvertedBookStore(private val convertedDir: File) {

    /**
     * sidecar 解析结果短时备忘：打开一本书会多次问同一 hash（内容、纸书页码、样式 span、章节），
     * 每次都把 4 个 sidecar 全量 readLines 解析一遍是纯浪费。容量小、按访问序淘汰。
     */
    private val memo = object : LinkedHashMap<String, FlattenedBook>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FlattenedBook>): Boolean =
            size > MEMO_SIZE
    }

    @Synchronized
    fun cached(hash: String): FlattenedBook? {
        memo[hash]?.let { book ->
            // 备忘省掉的是 sidecar 解析，不是有效性判定：产物被删书清掉、
            // sidecar 缺失、版本号被降级，都必须照常判失效。
            if (cacheFilesValid(hash)) return book
            memo.remove(hash)
            return null
        }
        return readCached(hash)?.also { memo[hash] = it }
    }

    /** 廉价校验（几次 stat + 读几字节版本号），用于备忘命中的场合。 */
    private fun cacheFilesValid(hash: String): Boolean {
        val target = File(convertedDir, "$hash.txt")
        if (!target.isFile || target.length() == 0L) return false
        for (suffix in SIDECAR_SUFFIXES) {
            if (!File(convertedDir, "$hash$suffix").isFile) return false
        }
        return readVersion(hash) == FLATTEN_VERSION
    }

    private fun readVersion(hash: String): Int? =
        File(convertedDir, "$hash.version").takeIf { it.isFile }
            ?.let { runCatching { it.readText(Charsets.UTF_8).trim().toInt() }.getOrNull() }

    private fun readCached(hash: String): FlattenedBook? {
        val target = File(convertedDir, "$hash.txt")
        if (!target.isFile || target.length() == 0L) return null
        if (readVersion(hash) != FLATTEN_VERSION) return null
        val chapters = readToc(File(convertedDir, "$hash.toc")) ?: return null
        val anchors = readAnchors(File(convertedDir, "$hash.anchors")) ?: return null
        val pages = readPages(File(convertedDir, "$hash.pages")) ?: return null
        val spans = readSpans(File(convertedDir, "$hash.spans")) ?: return null
        return FlattenedBook(target, chapters, fresh = false, anchors = anchors, pageLabels = pages, spans = spans)
    }

    /** [flatten] 把源压平写入给定临时文件并返回产物；成功后原子改名 + 写 sidecar。 */
    fun store(hash: String, flatten: (out: File) -> FlattenContent): FlattenedBook {
        convertedDir.mkdirs()
        val target = File(convertedDir, "$hash.txt")
        val tocFile = File(convertedDir, "$hash.toc")
        val anchorsFile = File(convertedDir, "$hash.anchors")
        val pagesFile = File(convertedDir, "$hash.pages")
        val tmpTxt = newTempFile(".txt")
        try {
            val content = flatten(tmpTxt)
            writeToc(tocFile, content.chapters)
            writeAnchors(anchorsFile, content.anchors)
            writePages(pagesFile, content.pageLabels)
            writeSpans(File(convertedDir, "$hash.spans"), content.spans)
            File(convertedDir, "$hash.version").writeText(FLATTEN_VERSION.toString(), Charsets.UTF_8)
            if (target.exists() && !target.delete()) {
                throw IOException("压平缓存写入失败: ${target.absolutePath}")
            }
            if (!tmpTxt.renameTo(target)) {
                throw IOException("压平缓存写入失败: ${target.absolutePath}")
            }
            val stored = FlattenedBook(
                target,
                content.chapters,
                fresh = true,
                anchors = content.anchors,
                pageLabels = content.pageLabels,
                spans = content.spans,
            )
            // 备忘里存 fresh=false 的等价实例：fresh 只表示「本次调用刚压平」，
            // 后续 cached() 命中不能谎报为刚压平（否则会重复回填章节）。
            memoize(hash, stored.copy(fresh = false))
            return stored
        } finally {
            tmpTxt.delete()
        }
    }

    @Synchronized
    private fun memoize(hash: String, book: FlattenedBook) {
        memo[hash] = book
    }

    private val flattenLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()

    /**
     * 同一 hash 的压平串行化。
     *
     * 后台预热队列与阅读器可能同时发现缓存缺失（导入后马上点开就是这个时序），
     * 并发压同一本书会白做一遍——而那正是用户正在等首屏的时刻。
     * 调用方应在拿到锁**之后**再查一次缓存：等锁期间别人可能已经压好了。
     */
    fun <T> withFlattenLock(hash: String, block: () -> T): T =
        synchronized(flattenLocks.computeIfAbsent(hash) { Any() }) { block() }

    /** 内嵌图片目录：`<hash>.images/`。 */
    fun imagesDir(hash: String): File = File(convertedDir, "$hash.images")

    /** zip 内路径打平为图片文件名（`a/b/c.png` → `a_b_c.png`）。 */
    fun imageFileName(zipPath: String): String = zipPath.replace('/', '_')

    fun imageFile(hash: String, zipPath: String): File = File(imagesDir(hash), imageFileName(zipPath))

    fun newTempFile(suffix: String): File {
        convertedDir.mkdirs()
        return File(convertedDir, ".tmp-${UUID.randomUUID()}$suffix")
    }

    fun contentHash(file: File): String =
        RandomAccessFile(file, "r").use { raf ->
            ContentHasher.hash(raf.length()) { offset, length -> readAt(raf.channel, offset, length) }
        }

    fun contentHash(channel: SeekableByteChannel): String =
        ContentHasher.hash(channel.size()) { offset, length -> readAt(channel, offset, length) }

    fun readAt(channel: SeekableByteChannel, offset: Long, length: Int): ByteArray {
        channel.position(offset)
        val buffer = ByteBuffer.allocate(length)
        while (buffer.hasRemaining() && channel.read(buffer) >= 0) Unit
        return buffer.array().copyOf(buffer.position())
    }

    fun copyChannel(channel: SeekableByteChannel, target: File) {
        channel.position(0)
        Channels.newInputStream(channel).use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
    }

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

    /** anchors TSV：转义键（路径#id）+ 偏移。 */
    private fun writeAnchors(file: File, anchors: Map<String, Long>) {
        file.bufferedWriter(Charsets.UTF_8).use { writer ->
            for ((key, offset) in anchors) {
                writer.write(escapeTocField(key))
                writer.write('\t'.code)
                writer.write(offset.toString())
                writer.write('\n'.code)
            }
        }
    }

    private fun readAnchors(file: File): Map<String, Long>? {
        if (!file.isFile) return null
        return runCatching {
            file.readLines(Charsets.UTF_8).associate { line ->
                val parts = line.split('\t')
                require(parts.size >= 2) { "损坏的锚点缓存行" }
                unescapeTocField(parts.subList(0, parts.size - 1).joinToString("\t")) to
                    parts.last().toLong()
            }
        }.getOrNull()
    }

    /** pages TSV：偏移 + 转义页码 label。 */
    private fun writePages(file: File, pages: List<PageLabel>) {
        file.bufferedWriter(Charsets.UTF_8).use { writer ->
            for (page in pages) {
                writer.write(page.charOffset.toString())
                writer.write('\t'.code)
                writer.write(escapeTocField(page.label))
                writer.write('\n'.code)
            }
        }
    }

    private fun readPages(file: File): List<PageLabel>? {
        if (!file.isFile) return null
        return runCatching {
            file.readLines(Charsets.UTF_8).map { line ->
                val parts = line.split('\t')
                require(parts.size >= 2) { "损坏的页码缓存行" }
                PageLabel(
                    label = unescapeTocField(parts.subList(1, parts.size).joinToString("\t")),
                    charOffset = parts[0].toLong(),
                )
            }
        }.getOrNull()
    }

    /** spans TSV：类型 + 区间 + 转义 payload/alt + 原始尺寸。 */
    private fun writeSpans(file: File, spans: List<TextSpan>) {
        file.bufferedWriter(Charsets.UTF_8).use { writer ->
            for (span in spans) {
                writer.write(span.type.name)
                writer.write('\t'.code)
                writer.write(span.start.toString())
                writer.write('\t'.code)
                writer.write(span.end.toString())
                writer.write('\t'.code)
                writer.write(escapeTocField(span.payload.orEmpty()))
                writer.write('\t'.code)
                writer.write(escapeTocField(span.alt.orEmpty()))
                writer.write('\t'.code)
                writer.write(span.width.toString())
                writer.write('\t'.code)
                writer.write(span.height.toString())
                writer.write('\n'.code)
            }
        }
    }

    private fun readSpans(file: File): List<TextSpan>? {
        if (!file.isFile) return null
        return runCatching {
            file.readLines(Charsets.UTF_8).map { line ->
                val parts = line.split('\t')
                require(parts.size >= 7) { "损坏的 span 缓存行" }
                TextSpan(
                    type = TextSpanType.valueOf(parts[0]),
                    start = parts[1].toLong(),
                    end = parts[2].toLong(),
                    payload = unescapeTocField(parts[3]).takeIf { it.isNotEmpty() },
                    alt = unescapeTocField(parts[4]).takeIf { it.isNotEmpty() },
                    width = parts[5].toInt(),
                    height = parts[6].toInt(),
                )
            }
        }.getOrNull()
    }

    private fun readToc(file: File): List<Chapter>? {
        if (!file.isFile) return null
        return runCatching {
            file.readLines(Charsets.UTF_8).map { line ->
                val parts = line.split('\t')
                require(parts.size >= 3) { "损坏的章节缓存行" }
                Chapter(
                    title = unescapeTocField(parts.subList(2, parts.size).joinToString("\t")),
                    charStart = parts[0].toLong(),
                    charEnd = parts[1].toLong(),
                )
            }
        }.getOrNull()
    }

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
                i++
            }
        }
    }

    companion object {
        /**
         * 压平规范版本：压平输出规则变更时递增，旧缓存自动重压平。
         * v2：ruby/表格/列表结构化；v3：img 占位块（U+FFFC）+ 样式/链接/图片 span 记录。
         */
        const val FLATTEN_VERSION = 3

        /** sidecar 解析结果备忘容量：同一时刻只有一本书在阅读，2 条足够。 */
        const val MEMO_SIZE = 2

        /** 压平产物必须齐备的 sidecar 后缀（缺任何一个都视为缓存失效）。 */
        val SIDECAR_SUFFIXES = listOf(".toc", ".anchors", ".pages", ".spans", ".version")
    }
}

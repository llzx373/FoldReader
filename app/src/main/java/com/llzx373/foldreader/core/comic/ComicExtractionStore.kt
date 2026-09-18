package com.llzx373.foldreader.core.comic

import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** 解压出来的一页：原始条目名 + 已落盘的文件（文件名由解压器自定，仅需唯一）。 */
typealias ExtractedPage = Pair<String, File>

/**
 * 漫画本地存储，两个用途不同、生命周期也不同的目录放在同一棵树下：
 *
 * - `cache/<hash>/pages/`：**可回收的解压缓存**。tar/rar 没有中央目录只能顺序读，
 *   所以首开一次性解压到本地；缓存被 GC 或版本失效都能重来。
 * - `local/<hash>/pages/`：用户显式选择的**复制到本地**（脱离 SAF 授权，源被删也能读），
 *   只在删书时清理。
 *
 * 页文件名一律为 `%06d_原名`，顺序由文件名自描述，不需要额外索引文件。
 * 版本号写进标记文件：解压规范变了就整体失效重来，不写迁移。
 */
class ComicExtractionStore(private val comicsDir: File) {

    private val cacheRoot = File(comicsDir, "cache")
    private val localRoot = File(comicsDir, "local")
    private val locks = ConcurrentHashMap<String, Any>()

    private fun cacheDir(hash: String) = File(cacheRoot, hash)
    private fun localDir(hash: String) = File(localRoot, hash)

    /** 解压缓存的页目录。 */
    fun cachePagesDir(hash: String): File = File(cacheDir(hash), "pages")

    /** 缩略图磁盘缓存目录（与 pages 同生命周期，可随缓存一起回收）。 */
    fun thumbsDir(hash: String): File = File(cacheDir(hash), "thumbs")

    /** 复制到本地的页目录。 */
    fun localPagesDir(hash: String): File = File(localDir(hash), "pages")

    /** 解压缓存命中时返回有序页文件；未解压或版本不符返回 null。 */
    fun cachedPages(hash: String): List<File>? {
        if (readMarker(File(cacheDir(hash), MARKER)) != VERSION) return null
        return listPages(cachePagesDir(hash))
    }

    /** 本地副本命中时返回有序页文件。 */
    fun localPages(hash: String): List<File>? {
        if (readMarker(File(localDir(hash), MARKER)) != VERSION) return null
        return listPages(localPagesDir(hash))
    }

    /**
     * 确保解压缓存就绪并返回有序页文件。
     * [extract] 把页写进给定目录，返回 (原始条目名, 文件) 列表；排序只由原始名决定。
     */
    fun ensureExtracted(hash: String, extract: (targetDir: File) -> List<ExtractedPage>): List<File> =
        withLock(hash) {
            cachedPages(hash)?.let { return@withLock it }
            val dir = cachePagesDir(hash)
            dir.deleteRecursively()
            dir.mkdirs()
            val pages = finalizePages(dir, extract(dir))
            File(cacheDir(hash), MARKER).writeText(VERSION.toString(), Charsets.UTF_8)
            pages
        }

    /**
     * 生成「复制到本地」的持久副本并返回有序页文件。
     * 已存在且版本一致时直接复用（重复点击不重复解包）。
     */
    fun ensureLocalCopy(hash: String, extract: (targetDir: File) -> List<ExtractedPage>): List<File> =
        withLock(hash) {
            localPages(hash)?.let { return@withLock it }
            val dir = localPagesDir(hash)
            dir.deleteRecursively()
            dir.mkdirs()
            val pages = finalizePages(dir, extract(dir))
            File(localDir(hash), MARKER).writeText(VERSION.toString(), Charsets.UTF_8)
            pages
        }

    /**
     * 同一 hash 的落盘串行化：后台预热与用户点开可能同时发现缓存缺失，
     * 并发解压同一本会白做一遍——而那正是用户在等首屏的时刻。
     * 调用方应在拿到锁**之后**再查一次缓存。
     */
    fun <T> withLock(hash: String, block: () -> T): T =
        synchronized(locks.computeIfAbsent(hash) { Any() }) { block() }

    /** 删书：缓存与本地副本一并清掉。 */
    fun deleteAll(hash: String) {
        cacheDir(hash).deleteRecursively()
        localDir(hash).deleteRecursively()
    }

    fun deleteLocalCopy(hash: String) {
        localDir(hash).deleteRecursively()
    }

    /** 缩略图整体失效（页数或页序变化时用）。 */
    fun deleteThumbs(hash: String) {
        thumbsDir(hash).deleteRecursively()
    }

    /**
     * 回收解压缓存：不在书架上的 hash 直接删。本地副本（`local/`）不动——
     * 那是用户显式选择的持久数据，只由「删除本地副本」或删书清理。
     */
    fun sweep(liveHashes: Set<String>): Int {
        val dirs = cacheRoot.listFiles { f -> f.isDirectory } ?: return 0
        var deleted = 0
        for (dir in dirs) {
            if (dir.name in liveHashes) continue
            if (dir.deleteRecursively()) deleted++
        }
        return deleted
    }

    /** 把条目名转成安全的文件名（含子目录路径时打平）。 */
    fun pageFileName(name: String): String =
        name.replace('/', '_').replace('\\', '_')
            .map { if (it.isISOControl() || it in ILLEGAL_CHARS) '_' else it }
            .joinToString("")

    /**
     * 解压产物收尾：按原始条目名的自然序更名为 `%06d_原名`。
     * 顺序取自原始名（而不是写盘时的文件名），因此不会被打平撞名影响。
     */
    private fun finalizePages(dir: File, extracted: List<ExtractedPage>): List<File> {
        if (extracted.isEmpty()) {
            dir.deleteRecursively()
            throw IOException("容器内没有可显示的图片")
        }
        val orderedInput = extracted.sortedWith { a, b ->
            ComicPageOrdering.compareNatural(a.first, b.first)
        }
        val ordered = ArrayList<File>(orderedInput.size)
        for ((index, page) in orderedInput.withIndex()) {
            val (originalName, file) = page
            val target = File(dir, "%06d_%s".format(index, pageFileName(originalName)))
            if (file == target) {
                ordered += file
                continue
            }
            if (target.exists()) target.delete()
            // 改名失败（个别文件系统限制）不该让整本读不了：原地保留，
            // 缓存命中路径按名排序可能错位，但本次会话用的是内存里的正确顺序。
            ordered += if (file.renameTo(target)) target else file
        }
        val kept = ordered.toHashSet()
        dir.listFiles { f -> f.isFile }?.forEach { if (it !in kept) it.delete() }
        return ordered
    }

    private fun listPages(dir: File): List<File>? {
        val files = dir.listFiles { f -> f.isFile } ?: return null
        if (files.isEmpty()) return null
        return files.sortedWith { a, b -> ComicPageOrdering.compareNatural(a.name, b.name) }
    }

    private fun readMarker(file: File): Int? =
        if (file.isFile) runCatching { file.readText(Charsets.UTF_8).trim().toInt() }.getOrNull() else null

    companion object {
        /** 解压产物规范版本：命名/过滤规则变更时递增，旧缓存整体失效重来。 */
        const val VERSION = 1

        private const val MARKER = ".extract-version"

        /** 文件名里必须替换掉的字符。 */
        private val ILLEGAL_CHARS = charArrayOf(':', '*', '?', '"', '<', '>', '|')
    }
}

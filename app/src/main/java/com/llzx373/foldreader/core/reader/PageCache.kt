package com.llzx373.foldreader.core.reader

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile

class PageCache<K, V>(private val maxSize: Int) {

    private val map = object : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>): Boolean = size > maxSize
    }

    @Synchronized
    fun get(key: K): V? = map[key]

    @Synchronized
    fun put(key: K, value: V) {
        map[key] = value
    }

    @Synchronized
    fun clear() = map.clear()

    @get:Synchronized
    val size: Int get() = map.size
}

interface PageDiskCache {
    fun load(key: PaginatorKey, charCount: Long): LongArray?

    fun save(key: PaginatorKey, charCount: Long, bounds: LongArray)

    /**
     * 增量追加：磁盘上已有 [persistedCount] 条、且头部与 [allBounds] 的前提一致时，
     * 只把新增的尾部写进去（后台全书分页每 64 页落盘一次，整份重写会退化成 O(n²) 写入）。
     * 返回 false 表示无法增量，调用方应回落 [save]。
     */
    fun append(key: PaginatorKey, charCount: Long, allBounds: LongArray, persistedCount: Int): Boolean

    /** 删除某本书的全部页边界文件：改一次版式就多一份（文件名含版式指纹）。 */
    fun deleteForBook(bookId: Long)
}

internal fun LayoutConfig.diskKeyString(): String = listOf(
    fontSizeSp, lineSpacingMultiplier, letterSpacingEm, paragraphSpacingEm,
    marginLeftDp, marginTopDp, marginRightDp, marginBottomDp,
    firstLineIndentChars, autoIndentEnabled, normalizeWhitespaceEnabled, maxLineChars, alignment, fontKey,
).joinToString("|")

/**
 * 页边界索引磁盘缓存：每个 key 一个文件，存各页起始偏移（第一页恒为 0，
 * 页 i 覆盖 [bounds[i], bounds[i+1])，最后一页到 charCount）。
 * 头部含魔数/版本与完整 key 校验，任何不符即弃（删文件返回 null）。
 *
 * 头部布局由 [headerBytes] 唯一决定，[save]/[append]/[read] 共用同一份序列化，
 * 保证增量追加写出的字节与整份写入完全一致。
 */
class FilePageDiskCache(private val dir: File) : PageDiskCache {

    /**
     * 同一份边界文件的读写必须串行：`save` 走「写临时文件 + rename」，`append` 就地追加，
     * `load` 直接读，而 [Paginator] 会在退出阅读器、后台全书分页、改版式等多处并发落盘。
     * 不加锁时两个线程会同时写同一个 `.tmp`、或与 rename 交错，把边界文件写坏
     * （下次打开头部校验不过只能丢掉缓存重排）。
     */
    private val ioLock = Any()

    override fun load(key: PaginatorKey, charCount: Long): LongArray? = synchronized(ioLock) {
        val file = fileFor(key)
        if (!file.isFile) return@synchronized null
        val result = runCatching { read(file, key, charCount) }.getOrNull()
        if (result == null) file.delete()
        result
    }

    override fun save(key: PaginatorKey, charCount: Long, bounds: LongArray) {
        if (bounds.isEmpty() || bounds[0] != 0L) return
        synchronized(ioLock) {
            dir.mkdirs()
            val tmp = File(dir, fileFor(key).name + ".tmp")
            runCatching {
                DataOutputStream(tmp.outputStream().buffered()).use { out ->
                    out.write(headerBytes(key))
                    out.writeLong(charCount)
                    out.writeInt(bounds.size)
                    bounds.forEach { out.writeLong(it) }
                }
                val target = fileFor(key)
                target.delete()
                tmp.renameTo(target)
            }.onFailure { tmp.delete() }
        }
    }

    override fun append(
        key: PaginatorKey,
        charCount: Long,
        allBounds: LongArray,
        persistedCount: Int,
    ): Boolean {
        if (allBounds.isEmpty() || allBounds[0] != 0L) return false
        if (persistedCount <= 0 || persistedCount > allBounds.size) return false
        synchronized(ioLock) {
            val file = fileFor(key)
            if (!file.isFile) return false
            val header = headerBytes(key)
            val countOffset = header.size.toLong() + Long.SIZE_BYTES
            val dataOffset = countOffset + Int.SIZE_BYTES
            return runCatching {
                RandomAccessFile(file, "rw").use { raf ->
                    if (raf.length() < dataOffset) return@use false
                    val onDiskHeader = ByteArray(header.size)
                    raf.seek(0)
                    raf.readFully(onDiskHeader)
                    if (!onDiskHeader.contentEquals(header)) return@use false
                    if (raf.readLong() != charCount) return@use false
                    if (raf.readInt() != persistedCount) return@use false
                    // 校验通过且没有新增：磁盘即最新，无需写
                    if (persistedCount == allBounds.size) return@use true
                    // 顺序讲究：先写数据、再更新条数、最后截断。
                    // 中途崩溃时文件里仍是旧条数，读取方只认条数内的字节，内容依旧自洽。
                    raf.seek(dataOffset + persistedCount * Long.SIZE_BYTES)
                    for (i in persistedCount until allBounds.size) raf.writeLong(allBounds[i])
                    raf.seek(countOffset)
                    raf.writeInt(allBounds.size)
                    raf.setLength(dataOffset + allBounds.size * Long.SIZE_BYTES)
                    true
                }
            }.getOrElse { false }
        }
    }

    override fun deleteForBook(bookId: Long) {
        synchronized(ioLock) {
            dir.listFiles { f -> f.isFile && boundsBookIdOf(f.name) == bookId }?.forEach { it.delete() }
        }
    }

    /** 头部字节：魔数 / 版本 / 完整 key（书号、几何、开孔规避、排版参数）。 */
    private fun headerBytes(key: PaginatorKey): ByteArray {
        val out = ByteArrayOutputStream(HEADER_ESTIMATED_BYTES)
        DataOutputStream(out).use { d ->
            d.write(MAGIC)
            d.writeInt(VERSION)
            d.writeLong(key.bookId)
            d.writeInt(key.widthPx)
            d.writeInt(key.heightPx)
            d.writeFloat(key.density)
            d.writeFloat(key.scaledDensity)
            d.writeInt(key.avoidance.oddTopLines)
            d.writeInt(key.avoidance.evenBottomLines)
            d.writeUTF(key.config.diskKeyString())
        }
        return out.toByteArray()
    }

    private fun read(file: File, key: PaginatorKey, charCount: Long): LongArray? {
        DataInputStream(file.inputStream().buffered()).use { inp ->
            val magic = ByteArray(MAGIC.size)
            inp.readFully(magic)
            if (!magic.contentEquals(MAGIC)) return null
            if (inp.readInt() != VERSION) return null
            if (inp.readLong() != key.bookId) return null
            if (inp.readInt() != key.widthPx) return null
            if (inp.readInt() != key.heightPx) return null
            if (inp.readFloat() != key.density) return null
            if (inp.readFloat() != key.scaledDensity) return null
            if (inp.readInt() != key.avoidance.oddTopLines) return null
            if (inp.readInt() != key.avoidance.evenBottomLines) return null
            if (inp.readUTF() != key.config.diskKeyString()) return null
            if (inp.readLong() != charCount) return null
            val count = inp.readInt()
            if (count <= 0 || count > charCount) return null
            val bounds = LongArray(count) { inp.readLong() }
            if (bounds[0] != 0L) return null
            for (i in 1 until count) {
                if (bounds[i] <= bounds[i - 1] || bounds[i] >= charCount) return null
            }
            return bounds
        }
    }

    private fun fileFor(key: PaginatorKey): File {
        val hash = (
            listOf(
                key.bookId, key.widthPx, key.heightPx, key.density, key.scaledDensity,
                key.avoidance.oddTopLines, key.avoidance.evenBottomLines,
            ).joinToString("|") + "|" + key.config.diskKeyString()
            ).hashCode()
        return File(dir, "$FILE_PREFIX${key.bookId}_$hash$FILE_SUFFIX")
    }

    private companion object {
        val MAGIC = byteArrayOf('F'.code.toByte(), 'R'.code.toByte(), 'P'.code.toByte(), 'B'.code.toByte())
        const val VERSION = 3
        const val HEADER_ESTIMATED_BYTES = 256
    }
}

/** 页边界缓存文件名前缀 / 后缀（[PageBoundsGc] 也按它们识别文件）。 */
internal const val FILE_PREFIX = "bounds_"
internal const val FILE_SUFFIX = ".bin"

/**
 * 从 `bounds_<bookId>_<hash>.bin` 解析书号；格式不符返回 null。
 * 注意版式哈希可能为负，故必须先剥后缀再按第一个下划线切。
 */
internal fun boundsBookIdOf(fileName: String): Long? {
    if (!fileName.startsWith(FILE_PREFIX) || !fileName.endsWith(FILE_SUFFIX)) return null
    val body = fileName.substring(FILE_PREFIX.length, fileName.length - FILE_SUFFIX.length)
    val separator = body.indexOf('_')
    if (separator <= 0) return null
    return body.substring(0, separator).toLongOrNull()
}

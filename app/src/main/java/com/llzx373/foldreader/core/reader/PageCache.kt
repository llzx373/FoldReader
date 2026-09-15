package com.llzx373.foldreader.core.reader

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

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
}

internal fun LayoutConfig.diskKeyString(): String = listOf(
    fontSizeSp, lineSpacingMultiplier, letterSpacingEm, paragraphSpacingEm,
    marginLeftDp, marginTopDp, marginRightDp, marginBottomDp,
    firstLineIndentChars, autoIndentEnabled, maxLineChars, alignment, fontKey,
).joinToString("|")

/**
 * 页边界索引磁盘缓存：每个 key 一个文件，存各页起始偏移（第一页恒为 0，
 * 页 i 覆盖 [bounds[i], bounds[i+1])，最后一页到 charCount）。
 * 头部含魔数/版本与完整 key 校验，任何不符即弃（删文件返回 null）。
 */
class FilePageDiskCache(private val dir: File) : PageDiskCache {

    override fun load(key: PaginatorKey, charCount: Long): LongArray? {
        val file = fileFor(key)
        if (!file.isFile) return null
        val result = runCatching { read(file, key, charCount) }.getOrNull()
        if (result == null) file.delete()
        return result
    }

    override fun save(key: PaginatorKey, charCount: Long, bounds: LongArray) {
        if (bounds.isEmpty() || bounds[0] != 0L) return
        dir.mkdirs()
        val tmp = File(dir, fileFor(key).name + ".tmp")
        runCatching {
            DataOutputStream(tmp.outputStream().buffered()).use { out ->
                out.write(MAGIC)
                out.writeInt(VERSION)
                out.writeLong(key.bookId)
                out.writeInt(key.widthPx)
                out.writeInt(key.heightPx)
                out.writeFloat(key.density)
                out.writeFloat(key.scaledDensity)
                out.writeBoolean(key.rightDrop)
                out.writeUTF(key.config.diskKeyString())
                out.writeLong(charCount)
                out.writeInt(bounds.size)
                bounds.forEach { out.writeLong(it) }
            }
            val target = fileFor(key)
            target.delete()
            tmp.renameTo(target)
        }.onFailure { tmp.delete() }
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
            if (inp.readBoolean() != key.rightDrop) return null
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
                key.rightDrop,
            ).joinToString("|") + "|" + key.config.diskKeyString()
            ).hashCode()
        return File(dir, "bounds_${key.bookId}_$hash.bin")
    }

    private companion object {
        val MAGIC = byteArrayOf('F'.code.toByte(), 'R'.code.toByte(), 'P'.code.toByte(), 'B'.code.toByte())
        const val VERSION = 2
    }
}

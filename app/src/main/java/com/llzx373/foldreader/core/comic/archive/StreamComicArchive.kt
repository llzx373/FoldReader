package com.llzx373.foldreader.core.comic.archive

import com.llzx373.foldreader.core.comic.ComicArchive
import com.llzx373.foldreader.core.comic.ComicContainer
import com.llzx373.foldreader.core.comic.ComicImageSizing
import com.llzx373.foldreader.core.comic.ComicPage
import com.llzx373.foldreader.core.comic.ComicPageSource
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * 页原始字节的 LRU（按字节计量）。
 *
 * 缩放会按新目标尺寸重新解码同一页，若每次都重新解压，放大一次就要重解压一遍；
 * 但也按字节封顶——单页几十 MB 时宁可丢缓存也不能把内存吃穿。
 */
internal class PageByteCache(private val maxBytes: Int = DEFAULT_MAX_BYTES) {

    private val entries = LinkedHashMap<Int, ByteArray>(8, 0.75f, true)
    private var bytes = 0

    @Synchronized
    fun get(index: Int): ByteArray? = entries[index]

    @Synchronized
    fun put(index: Int, data: ByteArray) {
        if (data.size > maxBytes) return
        entries.put(index, data)?.let { bytes -= it.size }
        bytes += data.size
        val iterator = entries.entries.iterator()
        while (bytes > maxBytes && iterator.hasNext()) {
            bytes -= iterator.next().value.size
            iterator.remove()
        }
    }

    @Synchronized
    fun clear() {
        entries.clear()
        bytes = 0
    }

    companion object {
        const val DEFAULT_MAX_BYTES = 24 * 1024 * 1024
    }
}

/**
 * 通用漫画容器实现：只要求「给定页来源能拿到输入流」。
 *
 * zip（中央目录随机读条目）、解压后的本地目录、SAF 目录（逐个文档）
 * 都是同一个形状，差别只在 [openStream] 与关闭动作，没必要各写一份取字节/探测尺寸的逻辑。
 */
internal class StreamComicArchive(
    override val container: ComicContainer,
    override val pages: List<ComicPage>,
    private val openStream: (ComicPageSource) -> InputStream,
    private val onClose: () -> Unit = {},
) : ComicArchive {

    private val cache = PageByteCache()
    private val lock = Any()

    override fun readPage(index: Int): ByteArray {
        require(index in pages.indices) { "页序号越界: $index / ${pages.size}" }
        cache.get(index)?.let { return it }
        // 整个「开流 + 读完」过程串行化：ZipFile 之类的容器对象不是线程安全的
        val bytes = synchronized(lock) {
            openStream(pages[index].source).use { readCapped(it, ComicArchive.MAX_PAGE_BYTES) }
        }
        cache.put(index, bytes)
        return bytes
    }

    override fun pageSize(index: Int): IntArray? {
        require(index in pages.indices) { "页序号越界: $index / ${pages.size}" }
        cache.get(index)?.let { ComicImageSizing.probe(it)?.let { size -> return size } }
        // 只读头几十 KB 就够判尺寸，不必整页解压
        val prefix = synchronized(lock) {
            openStream(pages[index].source).use { readPrefix(it, ComicArchive.HEADER_PROBE_BYTES) }
        }
        return ComicImageSizing.probe(prefix)
    }

    override fun close() {
        cache.clear()
        onClose()
    }

    private fun readPrefix(stream: InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream(minOf(limit, PREFIX_BUFFER))
        val buffer = ByteArray(PREFIX_BUFFER)
        while (out.size() < limit) {
            val n = stream.read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private fun readCapped(stream: InputStream, cap: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(COPY_BUFFER)
        var total = 0
        while (true) {
            val n = stream.read(buffer)
            if (n < 0) break
            total += n
            if (total > cap) throw IOException("单页数据超过 ${cap / (1024 * 1024)}MB，疑似损坏")
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private companion object {
        const val PREFIX_BUFFER = 8 * 1024
        const val COPY_BUFFER = 64 * 1024
    }
}

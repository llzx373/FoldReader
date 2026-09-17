package com.llzx373.foldreader.core.format.txt

import com.llzx373.foldreader.core.format.BookContent
import com.llzx373.foldreader.core.format.OffsetIndex
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.charset.Charset
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

class TxtBookContent(
    private val channel: SeekableByteChannel,
    val charset: Charset,
    private val offsetIndex: OffsetIndex,
    val indexProgress: StateFlow<Float>? = null,
    private val onClose: (() -> Unit)? = null,
) : BookContent, Closeable {

    private val lock = Any()

    /** 解码器与字节暂存缓冲按实例复用：翻页热路径上不必每次 read 都重新分配。 */
    private val decoder: CharsetDecoder = charset.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    private var scratch = ByteArray(0)

    /**
     * 已解码块区间缓存：key = (起始块 << 32) | 结束块，value = 该区间解码后的文本。
     * 分页读相邻段落、搜索对同一块反复取上下文时命中率很高。
     * 访问序 LRU（LinkedHashMap accessOrder），整体在 [lock] 下访问。
     */
    private val blockCache = object : LinkedHashMap<Long, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, String>): Boolean =
            size > BLOCK_CACHE_SIZE
    }

    override val charCount: Long get() = offsetIndex.totalChars

    override val isCharCountFinal: Boolean get() = offsetIndex.isComplete

    override suspend fun awaitCharsAbove(offset: Long) = offsetIndex.awaitTotalCharsAbove(offset)

    override suspend fun read(range: LongRange): String = withContext(Dispatchers.IO) {
        val start = range.first.coerceAtLeast(0L)
        val requestedEnd = if (range.last == Long.MAX_VALUE) Long.MAX_VALUE else range.last + 1
        if (requestedEnd <= start) return@withContext ""
        offsetIndex.awaitTotalCharsAbove(start)
        if (start >= offsetIndex.totalChars) return@withContext ""
        offsetIndex.awaitTotalCharsAbove(requestedEnd - 1)
        val endExclusive = requestedEnd.coerceAtMost(offsetIndex.totalChars)
        if (start >= endExclusive) return@withContext ""
        val startBlock = offsetIndex.blockOfChar(start)
        val endBlock = offsetIndex.blockOfChar(endExclusive - 1)
        val text = decodeBlocks(startBlock, endBlock)
        val from = (start - offsetIndex.charStartOfBlock(startBlock)).toInt()
        text.substring(from, from + (endExclusive - start).toInt())
    }

    /** 解码 [startBlock, endBlock] 覆盖的字节区间；命中缓存则完全不碰 channel。 */
    private fun decodeBlocks(startBlock: Int, endBlock: Int): String = synchronized(lock) {
        val key = (startBlock.toLong() shl 32) or (endBlock.toLong() and 0xFFFFFFFFL)
        blockCache[key]?.let { return it }
        val decoded = decodeBytes(
            offsetIndex.byteOffsetOfBlock(startBlock),
            offsetIndex.byteOffsetOfBlock(endBlock + 1),
        )
        blockCache[key] = decoded
        decoded
    }

    private fun decodeBytes(byteStart: Long, byteEnd: Long): String {
        val length = (byteEnd - byteStart).toInt()
        if (length <= 0) return ""
        if (scratch.size < length) scratch = ByteArray(length)
        channel.position(byteStart)
        val buffer = ByteBuffer.wrap(scratch, 0, length)
        while (buffer.hasRemaining() && channel.read(buffer) >= 0) Unit
        buffer.flip()
        // 与 TxtIndexer 容错策略一致：坏字节替换为 U+FFFD，避免翻页期抛异常
        return decoder.decode(buffer).toString()
    }

    override fun close() {
        onClose?.invoke()
        channel.close()
    }

    private companion object {
        /** 块区间缓存条数：典型一次读取覆盖 1~2 个块（块 = 4096 字符），8 条约 64~128KB。 */
        const val BLOCK_CACHE_SIZE = 8
    }
}

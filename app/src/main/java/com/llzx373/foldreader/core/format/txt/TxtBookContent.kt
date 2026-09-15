package com.llzx373.foldreader.core.format.txt

import com.llzx373.foldreader.core.format.BookContent
import com.llzx373.foldreader.core.format.OffsetIndex
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.charset.Charset
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

    override val charCount: Long get() = offsetIndex.totalChars

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
        val byteStart = offsetIndex.byteOffsetOfBlock(startBlock)
        val byteEnd = offsetIndex.byteOffsetOfBlock(endBlock + 1)
        val text = synchronized(lock) {
            channel.position(byteStart)
            val buffer = ByteBuffer.allocate((byteEnd - byteStart).toInt())
            while (buffer.hasRemaining() && channel.read(buffer) >= 0) Unit
            buffer.flip()
            // 与 TxtIndexer 容错策略一致：坏字节替换为 U+FFFD，避免翻页期抛异常
            charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
                .decode(buffer).toString()
        }
        val from = (start - offsetIndex.charStartOfBlock(startBlock)).toInt()
        text.substring(from, from + (endExclusive - start).toInt())
    }

    override fun close() {
        onClose?.invoke()
        channel.close()
    }
}

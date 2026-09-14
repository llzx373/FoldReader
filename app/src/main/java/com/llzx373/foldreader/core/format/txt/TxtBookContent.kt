package com.llzx373.foldreader.core.format.txt

import com.llzx373.foldreader.core.format.BookContent
import com.llzx373.foldreader.core.format.OffsetIndex
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.charset.Charset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TxtBookContent(
    private val channel: SeekableByteChannel,
    val charset: Charset,
    private val offsetIndex: OffsetIndex,
    override val charCount: Long,
) : BookContent, Closeable {

    private val lock = Any()

    override suspend fun read(range: LongRange): String = withContext(Dispatchers.IO) {
        val start = range.first.coerceIn(0L, charCount)
        val endExclusive = (range.last + 1).coerceIn(0L, charCount)
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
            charset.decode(buffer).toString()
        }
        val from = (start - offsetIndex.charStartOfBlock(startBlock)).toInt()
        text.substring(from, from + (endExclusive - start).toInt())
    }

    override fun close() {
        channel.close()
    }
}

package com.llzx373.foldreader.core.format.txt

import com.llzx373.foldreader.core.format.Chapter
import com.llzx373.foldreader.core.format.ChapterScanner
import com.llzx373.foldreader.core.format.OffsetIndex
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

class TxtIndex(
    val charset: Charset,
    val charCount: Long,
    val offsetIndex: OffsetIndex,
    val chapters: List<Chapter>,
)

object TxtIndexer {

    private const val READ_CHUNK_BYTES = 256 * 1024

    fun index(
        channel: SeekableByteChannel,
        charset: Charset,
        bomLength: Int = 0,
        blockChars: Int = OffsetIndex.DEFAULT_BLOCK_CHARS,
    ): TxtIndex {
        channel.position(bomLength.toLong())
        val decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        val inBuf = ByteBuffer.allocate(READ_CHUNK_BYTES)
        inBuf.limit(0)
        val outBuf = CharBuffer.allocate(blockChars)
        val offsets = OffsetIndex(blockChars = blockChars, initialByteOffset = bomLength.toLong())
        val scanner = ChapterScanner()
        var totalRead = bomLength.toLong()
        var eof = false
        var endOfInputSent = false

        fun emitBlock() {
            val count = outBuf.position()
            outBuf.flip()
            scanner.feed(outBuf.toString())
            outBuf.clear()
            offsets.appendBlock(count, endByteOffset = totalRead - inBuf.remaining())
        }

        while (true) {
            if (!endOfInputSent) {
                if (eof) {
                    // 文件尾：尾部不完整字节按 REPLACE 收尾
                    decoder.decode(inBuf, outBuf, true)
                    endOfInputSent = true
                } else if (!inBuf.hasRemaining()) {
                    inBuf.clear()
                    val n = channel.read(inBuf)
                    inBuf.flip()
                    if (n < 0) eof = true else totalRead += n
                    continue
                } else {
                    val positionBefore = inBuf.position()
                    val result = decoder.decode(inBuf, outBuf, false)
                    if (result.isOverflow && outBuf.position() < blockChars) {
                        emitBlock()
                        continue
                    }
                    if (result.isUnderflow && inBuf.position() == positionBefore) {
                        // inBuf 尾部是不完整的多字节字符：压实并补读下一块
                        inBuf.compact()
                        val n = channel.read(inBuf)
                        inBuf.flip()
                        if (n < 0) eof = true else totalRead += n
                        continue
                    }
                }
            } else {
                val result = decoder.flush(outBuf)
                if (result.isUnderflow) {
                    if (outBuf.position() > 0) emitBlock()
                    break
                }
                if (result.isOverflow && outBuf.position() < blockChars) {
                    emitBlock()
                    continue
                }
            }
            if (outBuf.position() == blockChars) emitBlock()
        }
        offsets.markComplete()
        return TxtIndex(
            charset = charset,
            charCount = offsets.totalChars,
            offsetIndex = offsets,
            chapters = scanner.finish(),
        )
    }
}

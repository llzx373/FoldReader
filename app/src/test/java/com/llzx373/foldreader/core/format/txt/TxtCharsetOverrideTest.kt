package com.llzx373.foldreader.core.format.txt

import com.llzx373.foldreader.core.data.db.FakeOffsetIndexDao
import com.llzx373.foldreader.core.data.db.RoomOffsetIndexStore
import com.llzx373.foldreader.core.format.EncodingDetector
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.charset.Charset
import java.nio.file.StandardOpenOption
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TxtCharsetOverrideTest {

    private val gbk = Charset.forName("GBK")

    private fun tempFile(bytes: ByteArray): File {
        val file = File.createTempFile("foldreader-override", ".txt")
        file.deleteOnExit()
        file.writeBytes(bytes)
        return file
    }

    @Test
    fun `override 与自动检测不一致时以 override 为准`() {
        val sample = "第一章 这段文字会被识别为 GBK，因为有大量高位字节。".toByteArray(gbk)

        assertEquals(gbk, effectiveCharset(sample, charsetOverride = null))
        assertEquals(Charsets.UTF_8, effectiveCharset(sample, Charsets.UTF_8))
        assertEquals(gbk, effectiveCharset(sample, gbk))
    }

    @Test
    fun `override 路径仍跳过 BOM 前缀字节`() = runBlocking {
        val text = "第一章 带 BOM 的文本。\n正文内容。"
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            text.toByteArray(Charsets.UTF_8)
        val file = tempFile(bytes)

        val channel = FileChannel.open(file.toPath(), StandardOpenOption.READ)
        val sample = ByteArray(EncodingDetector.SAMPLE_SIZE).let { buf ->
            channel.position(0)
            val n = channel.read(java.nio.ByteBuffer.wrap(buf))
            buf.copyOf(n.coerceAtLeast(0))
        }
        val charset = effectiveCharset(sample, Charsets.UTF_8)
        val bom = EncodingDetector.bomLengthOf(sample)
        assertEquals(3, bom)

        val index = TxtIndexer.index(channel, charset, bomLength = bom)
        channel.close()
        assertEquals(text.length.toLong(), index.charCount)

        val content = TxtBookContent(RandomAccessFile(file, "r").channel, charset, index.offsetIndex)
        assertEquals(text, content.read(0L..text.length - 1L))
        content.close()
    }

    @Test
    fun `索引按 override 后的 charsetName 校验 不一致即失效`() = runBlocking {
        val text = "第一章 编码切换后旧索引应失效。".repeat(500)
        val file = tempFile(text.toByteArray(gbk))
        val snapshot = FileChannel.open(file.toPath(), StandardOpenOption.READ).use { channel ->
            TxtIndexer.index(channel, gbk).offsetIndex.snapshot()
        }
        val store = RoomOffsetIndexStore(FakeOffsetIndexDao())
        store.saveValid("3", snapshot, file.length(), "hash-x", gbk.name())

        assertNull(store.loadValid("3", file.length(), "hash-x", Charsets.UTF_8.name()))
        assertNotNull(store.loadValid("3", file.length(), "hash-x", gbk.name()))
        Unit
    }
}

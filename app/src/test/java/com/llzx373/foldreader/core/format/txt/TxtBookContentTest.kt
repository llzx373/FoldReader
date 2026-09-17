package com.llzx373.foldreader.core.format.txt

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.SeekableByteChannel
import java.nio.charset.Charset
import java.nio.file.StandardOpenOption
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 统计底层 channel 的 read 次数，用来验证块解码缓存确实省掉了重复 IO。 */
private class CountingChannel(private val delegate: SeekableByteChannel) : SeekableByteChannel {
    var reads = 0
        private set

    override fun read(dst: ByteBuffer): Int {
        reads++
        return delegate.read(dst)
    }

    override fun write(src: ByteBuffer): Int = delegate.write(src)
    override fun position(): Long = delegate.position()
    override fun position(newPosition: Long): SeekableByteChannel {
        delegate.position(newPosition)
        return this
    }

    override fun size(): Long = delegate.size()
    override fun truncate(size: Long): SeekableByteChannel = delegate.truncate(size)
    override fun isOpen(): Boolean = delegate.isOpen()
    override fun close() = delegate.close()
}

class TxtBookContentTest {

    private val gbk = Charset.forName("GBK")

    private fun createContent(
        text: String,
        charset: Charset,
        bom: ByteArray = byteArrayOf(),
        blockChars: Int = 16,
    ): TxtBookContent {
        val file = File.createTempFile("foldreader-txt", ".txt")
        file.deleteOnExit()
        file.writeBytes(bom + text.toByteArray(charset))
        val index = TxtIndexer.index(
            channel = FileChannel.open(file.toPath(), StandardOpenOption.READ),
            charset = charset,
            bomLength = bom.size,
            blockChars = blockChars,
        )
        return TxtBookContent(
            channel = RandomAccessFile(file, "r").channel,
            charset = charset,
            offsetIndex = index.offsetIndex,
        )
    }

    @Test
    fun `GBK 文本跨块窗口读取不错字`() = runBlocking {
        val text = "第一章 窗口化读取测试，多字节字符边界。".repeat(100)
        val content = createContent(text, gbk, blockChars = 16)

        assertEquals(text.length.toLong(), content.charCount)
        assertEquals(text.substring(13, 113), content.read(13L..112L))
        assertEquals(text, content.read(0L..text.length - 1L))
    }

    @Test
    fun `单字符读取与首尾边界`() = runBlocking {
        val text = "床前明月光，疑是地上霜。举头望明月，低头思故乡。"
        val content = createContent(text, gbk, blockChars = 4)

        assertEquals("床", content.read(0L..0L))
        assertEquals("。", content.read(text.length - 1L..text.length - 1L))
        assertEquals("月光", content.read(3L..4L))
        assertEquals("明月", content.read(15L..16L))
    }

    @Test
    fun `越界范围被钳制`() = runBlocking {
        val text = "短短一篇文。"
        val content = createContent(text, gbk, blockChars = 4)
        val n = text.length.toLong()

        assertEquals(text, content.read(0L..n + 100))
        assertEquals(text.takeLast(3), content.read(n - 3..n + 100))
        assertEquals("", content.read(n..n + 10))
        assertEquals("", content.read(5L..4L))
    }

    @Test
    fun `UTF-8 带 BOM 文本读取`() = runBlocking {
        val text = "序章 UTF-8 编码的文本，含 emoji 😀 与英文 abc。".repeat(50)
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val content = createContent(text, Charsets.UTF_8, bom = bom, blockChars = 32)

        assertEquals(text.length.toLong(), content.charCount)
        assertEquals(text, content.read(0L..text.length - 1L))
        assertEquals(text.substring(100, 200), content.read(100L..199L))
    }

    @Test
    fun `UTF-16LE 带 BOM 文本读取`() = runBlocking {
        val text = "第一章 UTF-16 小端编码的正文内容。".repeat(80)
        val bom = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        val content = createContent(text, Charsets.UTF_16LE, bom = bom, blockChars = 16)

        assertEquals(text.length.toLong(), content.charCount)
        assertEquals(text, content.read(0L..text.length - 1L))
        assertEquals(text.substring(7, 77), content.read(7L..76L))
    }

    @Test
    fun `代理对字符跨块切割不错字`() = runBlocking {
        val text = "😀第章🙂中文𠀀混排".repeat(120)
        val content = createContent(text, Charsets.UTF_8, blockChars = 7)

        assertEquals(text.length.toLong(), content.charCount)
        assertEquals(text, content.read(0L..text.length - 1L))
        assertEquals(text.substring(3, 103), content.read(3L..102L))
        assertEquals(text.substring(1000, 1100), content.read(1000L..1099L))
    }

    @Test
    fun `多字节字符跨 256KB 读块边界不死循环不错字`() = runBlocking {
        // 262143 个单字节 + 一个双字节字符，使其两字节恰好落在 256KB 读块边界两侧
        val text = "a".repeat(262143) + "床" + "b".repeat(1000)
        val content = createContent(text, gbk)

        assertEquals(text.length.toLong(), content.charCount)
        assertEquals(text, content.read(0L..text.length - 1L))
        assertEquals("床", content.read(262143L..262143L))
    }

    @Test
    fun `含坏字节窗口读取不抛异常且与索引容错一致`() = runBlocking {
        val prefix = "第一章 正常内容。"
        val suffix = "第二章 后续内容。"
        val file = File.createTempFile("foldreader-badbytes", ".txt")
        file.deleteOnExit()
        file.writeBytes(
            prefix.toByteArray(gbk) +
                byteArrayOf(0xFF.toByte(), 0x81.toByte(), 0x30.toByte()) +
                suffix.toByteArray(gbk),
        )
        val index = TxtIndexer.index(
            channel = FileChannel.open(file.toPath(), StandardOpenOption.READ),
            charset = gbk,
            blockChars = 8,
        )
        val content = TxtBookContent(
            channel = RandomAccessFile(file, "r").channel,
            charset = gbk,
            offsetIndex = index.offsetIndex,
        )

        val text = content.read(0L..content.charCount - 1)
        assertEquals(content.charCount, text.length.toLong())
        assertTrue(text.startsWith(prefix))
        assertTrue(text.endsWith(suffix))
    }

    @Test
    fun `文件末尾不完整多字节字符按替换符收尾不丢字符`() = runBlocking {
        val text = "床前明月光，疑是地上霜。"
        val file = File.createTempFile("foldreader-eof", ".txt")
        file.deleteOnExit()
        file.writeBytes(text.toByteArray(gbk) + byteArrayOf(0x81.toByte()))
        val index = TxtIndexer.index(
            channel = FileChannel.open(file.toPath(), StandardOpenOption.READ),
            charset = gbk,
            blockChars = 4,
        )
        val content = TxtBookContent(
            channel = RandomAccessFile(file, "r").channel,
            charset = gbk,
            offsetIndex = index.offsetIndex,
        )

        assertEquals(text.length + 1L, index.charCount)
        assertEquals(text + "�", content.read(0L..index.charCount - 1))
    }

    @Test
    fun `索引快照可恢复且读取一致`() = runBlocking {
        val text = "第二章 快照恢复测试。".repeat(60)
        val file = File.createTempFile("foldreader-snapshot", ".txt")
        file.deleteOnExit()
        file.writeBytes(text.toByteArray(gbk))
        val index = TxtIndexer.index(
            channel = FileChannel.open(file.toPath(), StandardOpenOption.READ),
            charset = gbk,
            blockChars = 16,
        )
        val restored = com.llzx373.foldreader.core.format.OffsetIndex.restore(
            index.offsetIndex.snapshot()
        )
        val content = TxtBookContent(
            channel = RandomAccessFile(file, "r").channel,
            charset = gbk,
            offsetIndex = restored,
        )

        assertEquals(text, content.read(0L..text.length - 1L))
    }

    // ---- 块解码缓存 ----

    private fun countingContent(
        text: String,
        charset: Charset,
        blockChars: Int = 16,
    ): Pair<TxtBookContent, CountingChannel> {
        val file = File.createTempFile("foldreader-blockcache", ".txt")
        file.deleteOnExit()
        file.writeBytes(text.toByteArray(charset))
        val index = TxtIndexer.index(
            channel = FileChannel.open(file.toPath(), StandardOpenOption.READ),
            charset = charset,
            blockChars = blockChars,
        )
        val channel = CountingChannel(RandomAccessFile(file, "r").channel)
        return TxtBookContent(channel, charset, index.offsetIndex) to channel
    }

    @Test
    fun `重复读取同一区间只解码一次`() = runBlocking {
        val text = "第一章 块缓存测试内容。".repeat(60)
        val (content, channel) = countingContent(text, gbk)

        val first = content.read(0L..15L)
        val readsAfterFirst = channel.reads
        val second = content.read(0L..15L)

        assertEquals(first, second)
        assertEquals(text.substring(0, 16), second)
        // 第二次完全命中缓存：不再触碰 channel
        assertEquals(readsAfterFirst, channel.reads)
        assertTrue(channel.reads > 0)
    }

    @Test
    fun `回访已读区间不产生新的 channel 读取`() = runBlocking {
        val text = "第二章 相邻块与回访测试。".repeat(80)
        val (content, channel) = countingContent(text, gbk)

        content.read(0L..15L)
        content.read(16L..31L)
        content.read(32L..47L)
        val readsAfterSweep = channel.reads

        // 回访前三个区间：全部命中缓存
        assertEquals(text.substring(0, 16), content.read(0L..15L))
        assertEquals(text.substring(16, 32), content.read(16L..31L))
        assertEquals(text.substring(32, 48), content.read(32L..47L))
        assertEquals(readsAfterSweep, channel.reads)
    }

    @Test
    fun `缓存命中与未命中结果逐字符一致`() = runBlocking {
        val text = "第三章 乱序读取一致性与多字节字符混排 ABC 123。".repeat(9)
        val cold = countingContent(text, gbk).first
        val (warm, _) = countingContent(text, gbk)

        // 先制造一批缓存条目，再乱序回访：结果必须与全新的实例完全一致
        repeat(6) { i -> warm.read((i * 16).toLong() until (i * 16 + 16).toLong()) }
        val probes = listOf(0L, 16L, 32L, 48L, 64L, 80L, 11L, 33L, 7L)
        for (start in probes) {
            val end = (start + 15).coerceAtMost(text.length - 1L)
            assertEquals(cold.read(start..end), warm.read(start..end))
        }
    }

    @Test
    fun `超出容量的区间被淘汰后仍能重新解码`() = runBlocking {
        val text = "第四章 缓存淘汰测试内容。".repeat(120)
        val (content, channel) = countingContent(text, gbk, blockChars = 16)
        val lastStart = (text.length - 16).toLong()

        // 连读 12 个互不相同的区间，必然挤掉最早的条目（缓存上限 8）
        repeat(12) { i -> content.read((i * 16).toLong() until (i * 16 + 16).toLong()) }
        val readsAfterSweep = channel.reads
        // 回访最早那个区间：已被淘汰，需要重新解码
        assertEquals(text.substring(0, 16), content.read(0L..15L))
        assertTrue("淘汰后应重新读取 channel", channel.reads > readsAfterSweep)
        // 末尾区间仍然正确
        assertEquals(text.substring(lastStart.toInt()), content.read(lastStart..text.length - 1L))
    }
}

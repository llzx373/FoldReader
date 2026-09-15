package com.llzx373.foldreader.core.format.txt

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.charset.Charset
import java.nio.file.StandardOpenOption
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
}

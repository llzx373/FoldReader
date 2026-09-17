package com.llzx373.foldreader.core.data.db

import com.llzx373.foldreader.core.format.OffsetIndex
import com.llzx373.foldreader.core.format.OffsetIndexBlock
import com.llzx373.foldreader.core.format.OffsetIndexSnapshot
import com.llzx373.foldreader.core.format.txt.TxtBookContent
import com.llzx373.foldreader.core.format.txt.TxtIndexer
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.charset.Charset
import java.nio.file.StandardOpenOption
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OffsetIndexStoreTest {

    private val gbk = Charset.forName("GBK")
    private val key = "7"
    private val contentHash = "hash-abc"
    private val charsetName = "GBK"

    /** 从快照取出第 [block] 块的落盘形态（字节起点 + 字符起点）。 */
    private fun blockAt(snapshot: OffsetIndexSnapshot, block: Int) = OffsetIndexBlock(
        chunkIndex = block,
        byteOffset = snapshot.blockByteOffsets[block],
        charStart = snapshot.blockCharStarts[block],
    )

    private fun indexedFile(text: String): Pair<File, OffsetIndexSnapshot> {
        val file = File.createTempFile("foldreader-store", ".txt")
        file.deleteOnExit()
        file.writeBytes(text.toByteArray(gbk))
        val snapshot = FileChannel.open(file.toPath(), StandardOpenOption.READ).use { channel ->
            TxtIndexer.index(channel, gbk).offsetIndex.snapshot()
        }
        return file to snapshot
    }

    @Test
    fun `持久化写入后可读取恢复且快照一致`() = runBlocking {
        val (file, snapshot) = indexedFile("第一章 持久化测试。".repeat(1000))
        val store = RoomOffsetIndexStore(FakeOffsetIndexDao())

        store.saveValid(key, snapshot, file.length(), contentHash, charsetName)
        val restored = store.load(key)

        assertNotNull(restored)
        restored!!
        assertEquals(snapshot.blockChars, restored.blockChars)
        assertEquals(snapshot.totalChars, restored.totalChars)
        assertArrayEquals(snapshot.blockCharStarts, restored.blockCharStarts)
        assertArrayEquals(snapshot.blockByteOffsets, restored.blockByteOffsets)
    }

    @Test
    fun `内容哈希不符判定失效`() = runBlocking {
        val (file, snapshot) = indexedFile("第一章 哈希校验。".repeat(600))
        val store = RoomOffsetIndexStore(FakeOffsetIndexDao())
        store.saveValid(key, snapshot, file.length(), contentHash, charsetName)

        assertNull(store.loadValid(key, file.length(), "other-hash", charsetName))
        assertNotNull(store.loadValid(key, file.length(), contentHash, charsetName))
    }

    @Test
    fun `文件长度不符判定失效`() = runBlocking {
        val (file, snapshot) = indexedFile("第一章 长度校验。".repeat(600))
        val store = RoomOffsetIndexStore(FakeOffsetIndexDao())
        store.saveValid(key, snapshot, file.length(), contentHash, charsetName)

        assertNull(store.loadValid(key, file.length() + 1, contentHash, charsetName))
        assertNull(store.loadValid(key, file.length() - 1, contentHash, charsetName))
        assertNotNull(store.loadValid(key, file.length(), contentHash, charsetName))
    }

    @Test
    fun `编码不符判定失效`() = runBlocking {
        val (file, snapshot) = indexedFile("第一章 编码校验。".repeat(600))
        val store = RoomOffsetIndexStore(FakeOffsetIndexDao())
        store.saveValid(key, snapshot, file.length(), contentHash, charsetName)

        assertNull(store.loadValid(key, file.length(), contentHash, "UTF-8"))
    }

    @Test
    fun `未完成索引判定失效`() = runBlocking {
        val dao = FakeOffsetIndexDao()
        val store = RoomOffsetIndexStore(dao)
        val (file, snapshot) = indexedFile("第一章 未完成。".repeat(600))

        store.begin(key)
        store.appendBlocks(key, listOf(blockAt(snapshot, 0)))
        assertNull(store.load(key))
        assertNull(store.loadValid(key, file.length(), contentHash, charsetName))

        dao.upsertMeta(
            OffsetIndexMetaEntity(
                bookId = key.toLong(),
                fileLength = file.length(),
                contentHash = contentHash,
                charsetName = charsetName,
                totalChars = snapshot.totalChars,
                completed = false,
            ),
        )
        assertNull(store.loadValid(key, file.length(), contentHash, charsetName))
    }

    @Test
    fun `增量批量落盘完成后可恢复`() = runBlocking {
        val (file, snapshot) = indexedFile("第一章 增量落盘。".repeat(1000))
        val store = RoomOffsetIndexStore(FakeOffsetIndexDao())
        val blockCount = snapshot.blockCharStarts.size - 1

        store.begin(key)
        val blocks = (0 until blockCount).map { blockAt(snapshot, it) }
        val half = blocks.size / 2
        store.appendBlocks(key, blocks.subList(0, half))
        store.appendBlocks(key, blocks.subList(half, blocks.size))
        assertNull(store.load(key))
        store.complete(key, file.length(), contentHash, charsetName, snapshot.totalChars)

        val restored = store.loadValid(key, file.length(), contentHash, charsetName)
        assertNotNull(restored)
        restored!!
        assertArrayEquals(snapshot.blockCharStarts, restored.blockCharStarts)
        assertArrayEquals(snapshot.blockByteOffsets, restored.blockByteOffsets)
    }

    @Test
    fun `块号不连续或偏移倒退判定失效`() = runBlocking {
        val dao = FakeOffsetIndexDao()
        val store = RoomOffsetIndexStore(dao)
        val meta = OffsetIndexMetaEntity(
            bookId = key.toLong(),
            fileLength = 10000L,
            contentHash = contentHash,
            charsetName = charsetName,
            totalChars = 6000L,
            completed = true,
        )

        // 块号不连续（缺 chunkIndex=1）；起点本身是自洽的，只有连续性检查能拦下来
        dao.upsertAll(
            listOf(
                OffsetIndexEntity(key.toLong(), 0, 0L, 0L),
                OffsetIndexEntity(key.toLong(), 2, 5000L, 4096L),
            ),
        )
        dao.upsertMeta(meta)
        assertNull(store.load(key))

        store.begin(key)
        // 字节偏移倒退；字符起点自洽，只有字节单调性检查能拦下来
        dao.upsertAll(
            listOf(
                OffsetIndexEntity(key.toLong(), 0, 5000L, 0L),
                OffsetIndexEntity(key.toLong(), 1, 4000L, 4096L),
            ),
        )
        dao.upsertMeta(meta)
        assertNull(store.load(key))
    }

    @Test
    fun `恢复后的索引窗口读取内容正确`() = runBlocking {
        val text = "第一章 恢复读取校验，窗口内容必须与原文一致。".repeat(500)
        val (file, snapshot) = indexedFile(text)
        val store = RoomOffsetIndexStore(FakeOffsetIndexDao())
        store.saveValid(key, snapshot, file.length(), contentHash, charsetName)

        val restored = store.loadValid(key, file.length(), contentHash, charsetName)
        assertNotNull(restored)
        val content = TxtBookContent(
            channel = RandomAccessFile(file, "r").channel,
            charset = gbk,
            offsetIndex = OffsetIndex.restore(restored!!),
        )

        assertEquals(text.length.toLong(), content.charCount)
        assertEquals(text, content.read(0L..text.length - 1L))
        assertEquals(text.substring(1000, 5000), content.read(1000L..4999L))
        content.close()
    }

    /**
     * 代理对跨块：`TxtIndexer` 的输出缓冲只剩 1 个槽位、而下一个字符是需要 2 槽的增补字符
     * （emoji、CJK 扩展 B 等）时，会以「不足 blockChars」的块提交，起点变成 4095 / 8190 / …
     *
     * 增补字符在 UTF-8 里是不可分割的 4 字节，这种边界上**不存在**合法字节偏移——
     * 非均匀是数据模型的必然结果，只能把真实字符起点一并持久化。
     * 按 `i * blockChars` 推算会整体错位：第 k 块错 k 个字符，靠后位置还会越界。
     */
    @Test
    fun `代理对跨块的非均匀索引也能忠实往返`() = runBlocking {
        val (file, snapshot, text) = surrogateIndexedFile()

        // 前提：确实触发了非均匀块，否则这条用例没在测目标场景
        assertEquals("应触发不足 blockChars 的块", 4095L, snapshot.blockCharStarts[1])

        val store = RoomOffsetIndexStore(FakeOffsetIndexDao())
        store.saveValid(key, snapshot, file.length(), contentHash, "UTF-8")
        val restored = store.loadValid(key, file.length(), contentHash, "UTF-8")

        assertNotNull(restored)
        assertArrayEquals(snapshot.blockCharStarts, restored!!.blockCharStarts)
        assertArrayEquals(snapshot.blockByteOffsets, restored.blockByteOffsets)

        // 用户可见的后果：恢复索引读出的文本必须与原始索引逐字符一致
        val original = TxtBookContent(
            channel = RandomAccessFile(file, "r").channel,
            charset = Charsets.UTF_8,
            offsetIndex = OffsetIndex.restore(snapshot),
        )
        val roundTripped = TxtBookContent(
            channel = RandomAccessFile(file, "r").channel,
            charset = Charsets.UTF_8,
            offsetIndex = OffsetIndex.restore(restored),
        )
        assertEquals(text.length.toLong(), roundTripped.charCount)
        assertEquals(original.read(0L..text.length - 1L), roundTripped.read(0L..text.length - 1L))
        assertEquals(text.substring(4080, 4120), roundTripped.read(4080L..4119L))
        original.close()
        roundTripped.close()
    }

    @Test
    fun `增量落盘的非均匀索引同样忠实往返`() = runBlocking {
        // live 路径是逐块 append 的，字符起点必须一路带上
        val (file, snapshot, _) = surrogateIndexedFile()
        val store = RoomOffsetIndexStore(FakeOffsetIndexDao())
        val blockCount = snapshot.blockCharStarts.size - 1

        store.begin(key)
        val blocks = (0 until blockCount).map { blockAt(snapshot, it) }
        val half = blocks.size / 2
        store.appendBlocks(key, blocks.subList(0, half))
        store.appendBlocks(key, blocks.subList(half, blocks.size))
        store.complete(key, file.length(), contentHash, "UTF-8", snapshot.totalChars)

        val restored = store.loadValid(key, file.length(), contentHash, "UTF-8")
        assertNotNull(restored)
        assertArrayEquals(snapshot.blockCharStarts, restored!!.blockCharStarts)
    }

    @Test
    fun `块起点序列不自洽的快照判为损坏`() = runBlocking {
        val meta = OffsetIndexMetaEntity(
            bookId = key.toLong(),
            fileLength = 100_000L,
            contentHash = contentHash,
            charsetName = charsetName,
            totalChars = 10_000L,
            completed = true,
        )
        suspend fun loadWith(vararg entries: OffsetIndexEntity): OffsetIndexSnapshot? {
            val dao = FakeOffsetIndexDao()
            dao.upsertAll(entries.toList())
            dao.upsertMeta(meta)
            return RoomOffsetIndexStore(dao).load(key)
        }

        // 块号连续但字符起点倒退
        assertNull(
            loadWith(
                OffsetIndexEntity(key.toLong(), 0, 0L, 0L),
                OffsetIndexEntity(key.toLong(), 1, 5_000L, 3_000L),
                OffsetIndexEntity(key.toLong(), 2, 9_000L, 2_000L),
            ),
        )
        // 单块字符跨度超过 blockChars
        assertNull(
            loadWith(
                OffsetIndexEntity(key.toLong(), 0, 0L, 0L),
                OffsetIndexEntity(key.toLong(), 1, 5_000L, 5_000L),
            ),
        )
        // 首块字符起点不为 0
        assertNull(
            loadWith(
                OffsetIndexEntity(key.toLong(), 0, 0L, 7L),
                OffsetIndexEntity(key.toLong(), 1, 5_000L, 5_000L),
            ),
        )
        // 末块跨度超过 blockChars（totalChars 10_000，块起点 6_000 → 跨度 4_000 尚可；
        // 起点 1_000 则跨度 9_000 超限）
        assertNull(
            loadWith(
                OffsetIndexEntity(key.toLong(), 0, 0L, 0L),
                OffsetIndexEntity(key.toLong(), 1, 5_000L, 1_000L),
            ),
        )
    }

    @Test
    fun `均匀块的快照仍能正常往返`() = runBlocking {
        val text = "第一章 均匀块往返。".repeat(1000)
        val (file, snapshot) = indexedFile(text)

        val store = RoomOffsetIndexStore(FakeOffsetIndexDao())
        store.saveValid(key, snapshot, file.length(), contentHash, charsetName)
        val restored = store.loadValid(key, file.length(), contentHash, charsetName)

        assertNotNull(restored)
        assertArrayEquals(snapshot.blockCharStarts, restored!!.blockCharStarts)
        assertArrayEquals(snapshot.blockByteOffsets, restored.blockByteOffsets)
    }

    /** 构造「增补字符正好跨 4096 块边界」的文件与其索引快照。 */
    private fun surrogateIndexedFile(): Triple<File, OffsetIndexSnapshot, String> {
        // 增补字符占第 4095、4096 两个 char，正好把块边界切在代理对中间
        val text = "a".repeat(4095) + "😀" + "b".repeat(9000)
        val file = File.createTempFile("foldreader-surrogate", ".txt")
        file.deleteOnExit()
        file.writeBytes(text.toByteArray(Charsets.UTF_8))
        val snapshot = FileChannel.open(file.toPath(), StandardOpenOption.READ).use { channel ->
            TxtIndexer.index(channel, Charsets.UTF_8).offsetIndex.snapshot()
        }
        return Triple(file, snapshot, text)
    }
}

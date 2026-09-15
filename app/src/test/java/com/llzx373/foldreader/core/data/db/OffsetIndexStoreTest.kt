package com.llzx373.foldreader.core.data.db

import com.llzx373.foldreader.core.format.OffsetIndex
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
import org.junit.Test

class OffsetIndexStoreTest {

    private val gbk = Charset.forName("GBK")
    private val key = "7"
    private val contentHash = "hash-abc"
    private val charsetName = "GBK"

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
        store.appendBlocks(key, listOf(0 to snapshot.blockByteOffsets[0]))
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
        val blocks = (0 until blockCount).map { it to snapshot.blockByteOffsets[it] }
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

        dao.upsertAll(
            listOf(
                OffsetIndexEntity(key.toLong(), 0, 0L),
                OffsetIndexEntity(key.toLong(), 2, 5000L),
            ),
        )
        dao.upsertMeta(meta)
        assertNull(store.load(key))

        store.begin(key)
        dao.upsertAll(
            listOf(
                OffsetIndexEntity(key.toLong(), 0, 5000L),
                OffsetIndexEntity(key.toLong(), 1, 4000L),
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
}

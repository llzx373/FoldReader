package com.llzx373.foldreader.core.format.txt

import com.llzx373.foldreader.core.format.OffsetIndex
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.charset.Charset
import java.nio.file.StandardOpenOption
import java.util.concurrent.Semaphore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class IncrementalIndexTest {

    private val gbk = Charset.forName("GBK")

    private fun tempFile(text: String): File {
        val file = File.createTempFile("foldreader-incremental", ".txt")
        file.deleteOnExit()
        file.writeBytes(text.toByteArray(gbk))
        return file
    }

    @Test
    fun `首窗在索引未完成时即可读取且内容正确`() = runBlocking {
        val text = "第一章 边建边读测试，索引进度不阻塞阅读。\n".repeat(4000)
        val file = tempFile(text)
        val shared = OffsetIndex(blockChars = 64)
        val throttle = Semaphore(0)
        val content = TxtBookContent(RandomAccessFile(file, "r").channel, gbk, shared)
        val indexing = launch(Dispatchers.IO) {
            FileChannel.open(file.toPath(), StandardOpenOption.READ).use { channel ->
                TxtIndexer.indexInto(channel, gbk, shared) { _, _, _ -> throttle.acquire() }
            }
        }
        try {
            throttle.release(8)
            shared.awaitTotalCharsAbove(511)
            assertFalse(shared.isComplete)

            val result = withTimeout(5_000) { content.read(0L..199L) }
            assertEquals(text.substring(0, 200), result)
        } finally {
            throttle.release(1_000_000)
        }
        indexing.join()
        assertTrue(shared.isComplete)
        assertEquals(text.length.toLong(), content.charCount)
        assertEquals(text, content.read(0L..text.length - 1L))
        content.close()
    }

    @Test
    fun `读取超出已建索引挂起 索引推进后返回`() = runBlocking {
        val text = "正".repeat(200) + "第二章 目标内容标记ABC。" + "末".repeat(200)
        val file = tempFile(text)
        val shared = OffsetIndex(blockChars = 16)
        val throttle = Semaphore(0)
        val content = TxtBookContent(RandomAccessFile(file, "r").channel, gbk, shared)
        val indexing = launch(Dispatchers.IO) {
            FileChannel.open(file.toPath(), StandardOpenOption.READ).use { channel ->
                TxtIndexer.indexInto(channel, gbk, shared) { _, _, _ -> throttle.acquire() }
            }
        }
        try {
            throttle.release(4)
            shared.awaitTotalCharsAbove(63)

            val deferred = async { content.read(200L..213L) }
            delay(200)
            assertFalse(deferred.isCompleted)

            throttle.release(1_000_000)
            assertEquals("第二章 目标内容标记ABC。", withTimeout(5_000) { deferred.await() })
        } finally {
            throttle.release(1_000_000)
        }
        indexing.join()
        content.close()
    }

    @Test
    fun `索引中止后已覆盖位置仍可读 未覆盖位置抛出`() = runBlocking {
        val text = "正".repeat(1000)
        val file = tempFile(text)
        val shared = OffsetIndex(blockChars = 16)
        val throttle = Semaphore(0)
        val content = TxtBookContent(RandomAccessFile(file, "r").channel, gbk, shared)
        val indexing = launch(Dispatchers.IO) {
            try {
                FileChannel.open(file.toPath(), StandardOpenOption.READ).use { channel ->
                    TxtIndexer.indexInto(channel, gbk, shared) { _, _, _ ->
                        if (!isActive) throw CancellationException()
                        throttle.acquire()
                    }
                }
            } catch (t: Throwable) {
                shared.abort(t)
                throw t
            }
        }
        throttle.release(4)
        shared.awaitTotalCharsAbove(63)
        assertEquals(text.substring(0, 32), content.read(0L..31L))

        indexing.cancel()
        throttle.release(1)
        indexing.join()

        assertEquals(text.substring(0, 32), content.read(0L..31L))
        try {
            content.read(500L..510L)
            fail("索引中止后读取未覆盖位置应抛出")
        } catch (_: CancellationException) {
        }
        content.close()
    }
}

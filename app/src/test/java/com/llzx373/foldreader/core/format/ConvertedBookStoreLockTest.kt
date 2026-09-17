package com.llzx373.foldreader.core.format

import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 同一 hash 的压平串行化。
 *
 * 后台预热队列与阅读器可能同时发现缓存缺失（"导入后马上点开"就是这个时序），
 * 并发压同一本书会白做一遍 —— 而那正是用户正在等首屏的时刻。
 */
class ConvertedBookStoreLockTest {

    private fun tempDir(): File = Files.createTempDirectory("converted-store-lock").toFile()

    /** 调用方约定：拿到锁之后必须再查一次缓存，等锁期间别人可能已经压好了。 */
    private fun ConvertedBookStore.flattenOnce(hash: String, runs: AtomicInteger): FlattenedBook =
        withFlattenLock(hash) {
            cached(hash) ?: store(hash) { out ->
                runs.incrementAndGet()
                out.writeText("正文", Charsets.UTF_8)
                FlattenContent(emptyList())
            }
        }

    @Test
    fun `同一 hash 的并发压平只执行一次`() = runBlocking {
        val store = ConvertedBookStore(tempDir())
        val runs = AtomicInteger()

        val results = (1..8).map {
            async(Dispatchers.IO) { store.flattenOnce("hash", runs) }
        }.awaitAll()

        assertEquals("并发压平应只落一次", 1, runs.get())
        assertEquals("后到者应命中先到者压好的产物", 1, results.map { it.file }.distinct().size)
    }

    @Test
    fun `不同 hash 各自压平互不影响`() = runBlocking {
        val store = ConvertedBookStore(tempDir())
        val runs = AtomicInteger()

        (1..4).map { i ->
            async(Dispatchers.IO) { store.flattenOnce("hash$i", runs) }
        }.awaitAll()

        assertEquals(4, runs.get())
    }

    @Test
    fun `已有缓存时不重复压平`() = runBlocking {
        val store = ConvertedBookStore(tempDir())
        val runs = AtomicInteger()

        store.flattenOnce("hash", runs)
        store.flattenOnce("hash", runs)

        assertEquals(1, runs.get())
    }
}

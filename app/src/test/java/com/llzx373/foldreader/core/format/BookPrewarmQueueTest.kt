package com.llzx373.foldreader.core.format

import android.net.Uri
import com.llzx373.foldreader.core.data.db.BookFormat
import java.nio.charset.Charset
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 预热队列：串行、失败静默、只有成功的才回写「已就绪」。
 * 用 Robolectric 只是为了 `Uri.parse` —— 队列本身只依赖它做 Uri 包装。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class BookPrewarmQueueTest {

    /** 记录 prewarm 调用；[fail] 为 true 时模拟压平失败。 */
    private class FakeParser(private val fail: Boolean = false) : BookParser {
        val warmed = ConcurrentLinkedQueue<String>()

        override suspend fun parseMeta(uri: Uri): BookMeta = error("未使用")

        override suspend fun parseChapters(uri: Uri, charsetOverride: Charset?): List<Chapter> =
            error("未使用")

        override suspend fun openContent(uri: Uri, charsetOverride: Charset?): BookContent =
            error("未使用")

        override suspend fun prewarm(uri: Uri) {
            warmed += uri.toString()
            if (fail) throw IllegalStateException("模拟压平失败")
        }
    }

    private fun bookUri(id: Long) = "content://books/$id"

    /**
     * 消费协程是常驻循环，不能挂在测试 job 上（runTest 会一直等它）。
     * 用独立作用域 + 测试调度器：既能被 advanceUntilIdle 确定性驱动，又能显式取消。
     */
    private fun TestScope.newQueue(
        parserFor: (BookFormat) -> BookParser?,
        onPrepared: suspend (Long) -> Unit = {},
    ): Pair<BookPrewarmQueue, CoroutineScope> {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val queue = BookPrewarmQueue(
            scope = scope,
            parserFor = parserFor,
            onPrepared = onPrepared,
            betweenJobsDelayMs = 0,
        )
        return queue to scope
    }

    @Test
    fun `成功的预热才回写就绪标记`() = runTest {
        val parser = FakeParser()
        val prepared = mutableListOf<Long>()
        val (queue, scope) = newQueue({ parser }, { prepared += it })
        try {
            queue.enqueue(1L, bookUri(1), BookFormat.EPUB)
            queue.enqueue(2L, bookUri(2), BookFormat.FB2)
            advanceUntilIdle()

            assertEquals(listOf(bookUri(1), bookUri(2)), parser.warmed.toList())
            assertEquals(listOf(1L, 2L), prepared)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `压平失败不回写就绪标记`() = runTest {
        val parser = FakeParser(fail = true)
        val prepared = mutableListOf<Long>()
        val (queue, scope) = newQueue({ parser }, { prepared += it })
        try {
            queue.enqueue(1L, bookUri(1), BookFormat.EPUB)
            advanceUntilIdle()

            assertEquals("仍应尝试过", 1, parser.warmed.size)
            assertTrue("失败的预热绝不能标记为已就绪", prepared.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `TXT 不入队`() = runTest {
        val parser = FakeParser()
        val (queue, scope) = newQueue({ parser })
        try {
            queue.enqueue(1L, bookUri(1), BookFormat.TXT)
            queue.enqueue(2L, bookUri(2), BookFormat.EPUB)
            advanceUntilIdle()

            assertEquals("TXT 没有压平步骤", listOf(bookUri(2)), parser.warmed.toList())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `没有对应解析器时静默跳过`() = runTest {
        val prepared = mutableListOf<Long>()
        val (queue, scope) = newQueue({ null }, { prepared += it })
        try {
            queue.enqueue(1L, bookUri(1), BookFormat.EPUB)
            advanceUntilIdle()

            assertTrue(prepared.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `入队不等解析完成`() = runTest {
        val parser = FakeParser()
        val (queue, scope) = newQueue({ parser })
        try {
            // 导入路径会直接调 enqueue，不能因此卡住
            repeat(50) { queue.enqueue(it.toLong(), bookUri(it.toLong()), BookFormat.EPUB) }
            assertEquals("enqueue 本身不应触发任何解析", 0, parser.warmed.size)

            advanceUntilIdle()
            assertEquals("排队的最终都会被处理", 50, parser.warmed.size)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `串行处理不并发`() = runTest {
        val concurrent = java.util.concurrent.atomic.AtomicInteger()
        var maxConcurrent = 0
        val parser = object : BookParser {
            override suspend fun parseMeta(uri: Uri): BookMeta = error("未使用")
            override suspend fun parseChapters(uri: Uri, charsetOverride: Charset?): List<Chapter> =
                error("未使用")
            override suspend fun openContent(uri: Uri, charsetOverride: Charset?): BookContent =
                error("未使用")
            override suspend fun prewarm(uri: Uri) {
                maxConcurrent = maxOf(maxConcurrent, concurrent.incrementAndGet())
                kotlinx.coroutines.yield()
                concurrent.decrementAndGet()
            }
        }
        val (queue, scope) = newQueue({ parser })
        try {
            repeat(5) { queue.enqueue(it.toLong(), bookUri(it.toLong()), BookFormat.EPUB) }
            advanceUntilIdle()

            assertEquals("批量导入不能同时开多个解析", 1, maxConcurrent)
        } finally {
            scope.cancel()
        }
    }
}

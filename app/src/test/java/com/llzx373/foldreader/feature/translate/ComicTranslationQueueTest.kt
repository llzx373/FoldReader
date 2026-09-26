package com.llzx373.foldreader.feature.translate

import com.llzx373.foldreader.core.ai.AiTargetLang
import com.llzx373.foldreader.core.data.db.ComicPageTranslationDao
import com.llzx373.foldreader.core.data.db.ComicPageTranslationEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 漫画整卷翻译队列的调度规则：逐页串行 / 断点续译 / 失败退避重试 / 取消 / 暂停恢复。
 * 引擎与台账全用假实现，调度时序用 [CompletableDeferred] 门闩锁死。
 */
class ComicTranslationQueueTest {

    private class FakePageDao : ComicPageTranslationDao {
        val rows = LinkedHashMap<Triple<Long, String, Int>, ComicPageTranslationEntity>()

        fun seedDone(bookId: Long, lang: String, pageIndex: Int) {
            rows[Triple(bookId, lang, pageIndex)] = ComicPageTranslationEntity(
                bookId = bookId, lang = lang, pageIndex = pageIndex,
                status = ComicPageTranslationEntity.STATUS_DONE, model = "m", bubbleCount = 1,
                updatedAt = 1L,
            )
        }

        override fun observeForBook(bookId: Long, lang: String): Flow<List<ComicPageTranslationEntity>> =
            flowOf(rows.values.filter { it.bookId == bookId && it.lang == lang })

        override suspend fun getForBook(bookId: Long, lang: String): List<ComicPageTranslationEntity> =
            rows.values.filter { it.bookId == bookId && it.lang == lang }

        override suspend fun upsert(page: ComicPageTranslationEntity) {
            rows[Triple(page.bookId, page.lang, page.pageIndex)] = page
        }

        override suspend fun updateStatus(
            bookId: Long,
            lang: String,
            pageIndex: Int,
            status: String,
            model: String,
            bubbleCount: Int,
            updatedAt: Long,
        ) = Unit

        override suspend fun deletePage(bookId: Long, lang: String, pageIndex: Int) = Unit
        override suspend fun deleteForBook(bookId: Long) = Unit
        override suspend fun deleteAll() = Unit
    }

    /** 记录调用顺序、可按页编排成败、可用门闩卡住某次调用的假翻译调用。 */
    private class FakeTranslate {
        val calls = mutableListOf<Pair<Long, Int>>() // bookId to pageIndex
        val failures = mutableSetOf<Pair<Long, Int>>() // 恒败页
        var gate: CompletableDeferred<Unit>? = null // 非空时每次调用前等待放行

        suspend fun translate(
            bookId: Long,
            bookTitle: String,
            pageIndex: Int,
            lang: AiTargetLang,
        ): Result<Int> {
            gate?.await()
            calls += bookId to pageIndex
            val key = bookId to pageIndex
            return if (key in failures) {
                Result.failure(IllegalStateException("恒败"))
            } else {
                Result.success(1)
            }
        }
    }

    // 队列的消费协程跑在注入 scope 上且随 Channel 常驻——必须独立于 runBlocking 的 Job，
    // 否则断言结束后 runBlocking 等子协程永远返回不了（worker 挂死）。
    private val queueScopes = mutableListOf<kotlinx.coroutines.CoroutineScope>()

    @org.junit.After
    fun tearDown() {
        queueScopes.forEach { it.cancel() }
        queueScopes.clear()
    }

    private fun newQueue(
        @Suppress("UNUSED_PARAMETER") owner: kotlinx.coroutines.CoroutineScope,
        dao: FakePageDao,
        books: Map<Long, Pair<String, Int>>,
        translate: FakeTranslate,
    ): ComicTranslationQueue {
        val scope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default,
        )
        queueScopes += scope
        return ComicTranslationQueue(
            scope = scope,
            bookFor = { books[it] },
            pageDao = dao,
            translatePageCall = translate::translate,
            betweenPagesDelayMs = 1L,
            maxPageRetries = 2,
            retryBaseDelayMs = 5L,
            pausePollMs = 5L,
        )
    }

    /** 等某书的进度满足条件（超时即失败，别把测试挂死）。 */
    private suspend fun awaitProgress(
        queue: ComicTranslationQueue,
        bookId: Long,
        timeoutMs: Long = 5_000L,
        predicate: (ComicTranslationQueue.BookProgress?) -> Boolean,
    ): ComicTranslationQueue.BookProgress? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val p = queue.progress.value[bookId]
            if (predicate(p)) return p
            kotlinx.coroutines.delay(5)
        }
        return queue.progress.value[bookId]
    }

    @Test
    fun `入队后逐页翻译并按顺序落进度终态 DONE`() = runBlocking {
        val translate = FakeTranslate()
        val queue = newQueue(
            this, FakePageDao(),
            mapOf(7L to ("漫画" to 3)),
            translate,
        )

        queue.enqueueBook(7L, AiTargetLang.ZH_HANS)
        val done = awaitProgress(queue, 7L) { it?.status == ComicTranslationQueue.Status.DONE }

        assertEquals(listOf(0, 1, 2), translate.calls.map { it.second })
        assertEquals(3, done?.total)
        assertEquals(3, done?.done)
        assertEquals(0, done?.failedPages)
    }

    @Test
    fun `断点续译跳过 done 页`() = runBlocking {
        val dao = FakePageDao()
        dao.seedDone(7L, "ZH_HANS", 1)
        val translate = FakeTranslate()
        val queue = newQueue(this, dao, mapOf(7L to ("漫画" to 3)), translate)

        queue.enqueueBook(7L, AiTargetLang.ZH_HANS)
        val done = awaitProgress(queue, 7L) { it?.status == ComicTranslationQueue.Status.DONE }

        assertEquals(listOf(0, 2), translate.calls.map { it.second })
        assertEquals(3, done?.total)
        assertEquals(3, done?.done)
    }

    @Test
    fun `单页失败退避重试后记失败继续后续页`() = runBlocking {
        val translate = FakeTranslate()
        translate.failures += 7L to 1 // 恒败：重试 2 次后放弃，继续下一页
        val queue = newQueue(this, FakePageDao(), mapOf(7L to ("漫画" to 3)), translate)

        queue.enqueueBook(7L, AiTargetLang.ZH_HANS)
        val done = awaitProgress(queue, 7L) { it?.status == ComicTranslationQueue.Status.DONE }

        // 页 1 调用 3 次（首次 + 2 次重试），页 0/2 各一次
        assertEquals(listOf(0, 1, 1, 1, 2), translate.calls.map { it.second })
        assertEquals(2, done?.done)
        assertEquals(1, done?.failedPages)
    }

    @Test
    fun `取消进行中的书：当前页被中断且不再处理后续页`() = runBlocking {
        val translate = FakeTranslate()
        val gate = CompletableDeferred<Unit>()
        translate.gate = gate
        val queue = newQueue(this, FakePageDao(), mapOf(7L to ("漫画" to 3)), translate)

        queue.enqueueBook(7L, AiTargetLang.ZH_HANS)
        queue.cancel(7L)
        gate.complete(Unit) // 放行可能被卡住的第一页（取消应让其中断/结果被丢弃）
        kotlinx.coroutines.delay(300) // 给消费循环足够的调度窗口

        assertTrue(
            "取消后不应翻完全部页：${translate.calls}",
            translate.calls.size < 3,
        )
        assertTrue(
            "状态不应是 DONE：${queue.progress.value[7L]}",
            queue.progress.value[7L]?.status != ComicTranslationQueue.Status.DONE,
        )
    }

    @Test
    fun `暂停期间不推进页恢复后继续`() = runBlocking {
        val translate = FakeTranslate()
        val gate = CompletableDeferred<Unit>()
        translate.gate = gate
        val queue = newQueue(this, FakePageDao(), mapOf(7L to ("漫画" to 2)), translate)

        queue.enqueueBook(7L, AiTargetLang.ZH_HANS)
        queue.pause()
        gate.complete(Unit)
        // 暂停中：给足时间也不应翻完（第一页放行后暂停生效于下一页前）
        kotlinx.coroutines.delay(100)
        val pausedCalls = translate.calls.size
        queue.resume()
        awaitProgress(queue, 7L) { it?.status == ComicTranslationQueue.Status.DONE }

        assertTrue(pausedCalls <= 1)
        assertEquals(2, translate.calls.size)
    }
}

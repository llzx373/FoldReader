package com.llzx373.foldreader.feature.summary

import com.llzx373.foldreader.core.ai.AiTargetLang
import com.llzx373.foldreader.core.data.db.ChapterSummaryDao
import com.llzx373.foldreader.core.data.db.ChapterSummaryEntity
import com.llzx373.foldreader.core.translate.TranslationUnit
import com.llzx373.foldreader.core.translate.UnitKind
import com.llzx373.foldreader.feature.translate.BookTranslationSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 全书摘要预生成队列的调度规则：串行 / 断点续做 / 失败退避重试 / 暂停恢复 / 取消。
 * 引擎与内容源全用假实现（骨架同 BookTranslationQueueTest）。
 */
class BookSummaryQueueTest {

    private class FakeSummaryDao : ChapterSummaryDao {
        val rows = LinkedHashMap<Triple<Long, String, Int>, ChapterSummaryEntity>()

        fun seedDone(bookId: Long, lang: String, unitIndex: Int) {
            rows[Triple(bookId, lang, unitIndex)] = ChapterSummaryEntity(
                bookId = bookId, lang = lang, unitIndex = unitIndex, unitKind = "chapter",
                unitTitle = "第${unitIndex + 1}章", status = ChapterSummaryEntity.STATUS_DONE,
                summary = "摘要", model = "m", updatedAt = 1L,
            )
        }

        override fun observeForBook(bookId: Long, lang: String): Flow<List<ChapterSummaryEntity>> =
            flowOf(rows.values.filter { it.bookId == bookId && it.lang == lang })

        override suspend fun getForBook(bookId: Long, lang: String): List<ChapterSummaryEntity> =
            rows.values.filter { it.bookId == bookId && it.lang == lang }

        override suspend fun getDoneForBook(bookId: Long, lang: String): List<ChapterSummaryEntity> =
            rows.values.filter {
                it.bookId == bookId && it.lang == lang && it.status == ChapterSummaryEntity.STATUS_DONE
            }

        override suspend fun getAllDone(): List<ChapterSummaryEntity> =
            rows.values.filter { it.status == ChapterSummaryEntity.STATUS_DONE }

        override suspend fun upsert(unit: ChapterSummaryEntity) {
            rows[Triple(unit.bookId, unit.lang, unit.unitIndex)] = unit
        }

        override suspend fun updateStatus(
            bookId: Long,
            lang: String,
            unitIndex: Int,
            status: String,
            summary: String,
            model: String,
            updatedAt: Long,
        ) = Unit

        override suspend fun deleteAll() = rows.clear()
    }

    private class FakeSource(
        override val bookTitle: String,
        private val unitCount: Int,
    ) : BookTranslationSource {
        val unitsList = (0 until unitCount).map {
            TranslationUnit(it, UnitKind.CHAPTER, "第${it + 1}章", 0, 10)
        }

        override suspend fun units(lang: AiTargetLang): List<TranslationUnit> = unitsList
        override suspend fun readUnitText(unit: TranslationUnit): String = "文本 ${unit.index}"
        override fun close() = Unit
    }

    /** 记录调用顺序、可按单位编排成败、可用门闩卡住某个单位的假摘要调用。 */
    private class FakeSummarize {
        val calls = mutableListOf<Pair<Long, Int>>() // bookId to unitIndex
        val failures = mutableSetOf<Pair<Long, Int>>() // 恒败单位
        val failOnce = mutableSetOf<Pair<Long, Int>>() // 第一次败、重试成
        private val attempts = mutableMapOf<Pair<Long, Int>, Int>()
        var gate: CompletableDeferred<Unit>? = null // 非空时每次调用前等待放行

        suspend fun summarize(
            bookId: Long,
            bookTitle: String,
            unit: TranslationUnit,
            unitText: String,
            lang: AiTargetLang,
        ): Result<Int> {
            gate?.await()
            calls += bookId to unit.index
            val key = bookId to unit.index
            val attempt = (attempts[key] ?: 0) + 1
            attempts[key] = attempt
            return when {
                key in failures -> Result.failure(IllegalStateException("恒败"))
                key in failOnce && attempt == 1 -> Result.failure(IllegalStateException("首败"))
                else -> Result.success(10)
            }
        }
    }

    // 队列的消费协程跑在注入 scope 上且随 Channel 常驻——必须独立于 runBlocking 的 Job
    private val queueScopes = mutableListOf<kotlinx.coroutines.CoroutineScope>()

    @org.junit.After
    fun tearDown() {
        queueScopes.forEach { it.cancel() }
        queueScopes.clear()
    }

    private fun newQueue(
        dao: FakeSummaryDao,
        sources: Map<Long, BookTranslationSource>,
        summarize: FakeSummarize,
    ): BookSummaryQueue {
        val scope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default,
        )
        queueScopes += scope
        return BookSummaryQueue(
            scope = scope,
            sourceFor = { sources[it] },
            summaryDao = dao,
            summarizeUnitCall = summarize::summarize,
            betweenUnitsDelayMs = 1L,
            maxUnitRetries = 2,
            retryBaseDelayMs = 5L,
            pausePollMs = 5L,
        )
    }

    /** 等某书的进度满足条件（超时即失败，别把测试挂死）。 */
    private suspend fun awaitProgress(
        queue: BookSummaryQueue,
        bookId: Long,
        timeoutMs: Long = 5_000L,
        predicate: (BookSummaryQueue.BookProgress?) -> Boolean,
    ): BookSummaryQueue.BookProgress? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val p = queue.progress.value[bookId]
            if (predicate(p)) return p
            kotlinx.coroutines.delay(5)
        }
        return queue.progress.value[bookId]
    }

    @Test
    fun `断点续做跳过 done 单位`() = runBlocking {
        val dao = FakeSummaryDao()
        dao.seedDone(7L, "ZH_HANS", 0)
        val summarize = FakeSummarize()
        val queue = newQueue(dao, mapOf(7L to FakeSource("书", 3)), summarize)

        queue.enqueueBook(7L, AiTargetLang.ZH_HANS)
        val done = awaitProgress(queue, 7L) { it?.status == BookSummaryQueue.Status.DONE }

        assertEquals(listOf(1, 2), summarize.calls.map { it.second })
        assertEquals(3, done?.total)
        assertEquals(3, done?.done)
    }

    @Test
    fun `两本书串行处理`() = runBlocking {
        val summarize = FakeSummarize()
        val queue = newQueue(
            FakeSummaryDao(),
            mapOf(7L to FakeSource("甲", 2), 8L to FakeSource("乙", 2)),
            summarize,
        )

        queue.enqueueBook(7L, AiTargetLang.ZH_HANS)
        queue.enqueueBook(8L, AiTargetLang.ZH_HANS)
        awaitProgress(queue, 8L) { it?.status == BookSummaryQueue.Status.DONE }

        assertEquals(listOf(7L to 0, 7L to 1, 8L to 0, 8L to 1), summarize.calls)
    }

    @Test
    fun `失败退避重试后成功与恒败记失败继续`() = runBlocking {
        val summarize = FakeSummarize()
        summarize.failOnce += 7L to 0
        summarize.failures += 7L to 1
        val queue = newQueue(FakeSummaryDao(), mapOf(7L to FakeSource("书", 3)), summarize)

        queue.enqueueBook(7L, AiTargetLang.ZH_HANS)
        val done = awaitProgress(queue, 7L) { it?.status == BookSummaryQueue.Status.DONE }

        // 单位 0 调用 2 次（首败 + 重试成），单位 1 调用 3 次（首次 + 2 次重试），单位 2 一次
        assertEquals(listOf(0, 0, 1, 1, 1, 2), summarize.calls.map { it.second })
        assertEquals(2, done?.done)
        assertEquals(1, done?.failedUnits)
    }

    @Test
    fun `取消进行中的书：当前单位被中断且不再处理后续单位`() = runBlocking {
        val summarize = FakeSummarize()
        val gate = CompletableDeferred<Unit>()
        summarize.gate = gate
        val queue = newQueue(FakeSummaryDao(), mapOf(7L to FakeSource("书", 3)), summarize)

        queue.enqueueBook(7L, AiTargetLang.ZH_HANS)
        queue.cancel(7L)
        gate.complete(Unit)
        kotlinx.coroutines.delay(300)

        assertTrue(
            "取消后不应摘要完全部单位：${summarize.calls}",
            summarize.calls.size < 3,
        )
        assertTrue(
            "状态不应是 DONE：${queue.progress.value[7L]}",
            queue.progress.value[7L]?.status != BookSummaryQueue.Status.DONE,
        )
    }

    @Test
    fun `暂停期间不处理新单位恢复后继续`() = runBlocking {
        val summarize = FakeSummarize()
        val gate = CompletableDeferred<Unit>()
        summarize.gate = gate
        val queue = newQueue(FakeSummaryDao(), mapOf(7L to FakeSource("书", 2)), summarize)

        queue.enqueueBook(7L, AiTargetLang.ZH_HANS)
        queue.pause()
        gate.complete(Unit)
        kotlinx.coroutines.delay(100)
        val pausedCalls = summarize.calls.size
        queue.resume()
        awaitProgress(queue, 7L) { it?.status == BookSummaryQueue.Status.DONE }

        assertTrue(pausedCalls <= 1)
        assertEquals(2, summarize.calls.size)
    }
}

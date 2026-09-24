package com.llzx373.foldreader.feature.translate

import com.llzx373.foldreader.core.ai.AiTargetLang
import com.llzx373.foldreader.core.data.db.TranslationDao
import com.llzx373.foldreader.core.data.db.TranslationEntity
import com.llzx373.foldreader.core.translate.TranslationUnit
import com.llzx373.foldreader.core.translate.UnitKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 全书翻译队列的调度规则：串行 / 断点续译 / 失败退避重试 / 插队（R5）/ 取消。
 * 引擎与内容源全用假实现，调度时序用 [CompletableDeferred] 门闩锁死。
 */
class BookTranslationQueueTest {

    private class FakeTranslationDao : TranslationDao {
        val rows = LinkedHashMap<Triple<Long, String, Int>, TranslationEntity>()

        fun seedDone(bookId: Long, lang: String, unitIndex: Int) {
            rows[Triple(bookId, lang, unitIndex)] = TranslationEntity(
                bookId = bookId, lang = lang, unitKind = "chapter", unitIndex = unitIndex,
                status = TranslationEntity.STATUS_DONE, model = "m", paragraphCount = 1,
                updatedAt = 1L,
            )
        }

        override fun observeForBook(bookId: Long, lang: String): Flow<List<TranslationEntity>> =
            flowOf(rows.values.filter { it.bookId == bookId && it.lang == lang })

        override suspend fun getForBook(bookId: Long, lang: String): List<TranslationEntity> =
            rows.values.filter { it.bookId == bookId && it.lang == lang }

        override suspend fun countByStatus(bookId: Long, lang: String, status: String): Int =
            rows.values.count { it.bookId == bookId && it.lang == lang && it.status == status }

        override suspend fun upsert(unit: TranslationEntity) {
            rows[Triple(unit.bookId, unit.lang, unit.unitIndex)] = unit
        }

        override suspend fun updateStatus(
            bookId: Long,
            lang: String,
            unitIndex: Int,
            status: String,
            model: String,
            paragraphCount: Int,
            updatedAt: Long,
        ) = Unit

        override suspend fun deleteForBook(bookId: Long) = Unit
        override suspend fun deleteUnit(bookId: Long, lang: String, unitIndex: Int) = Unit
        override suspend fun deleteAll() = Unit
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

    /** 记录调用顺序、可按单位编排成败、可用门闩卡住某个单位的假翻译调用。 */
    private class FakeTranslate {
        val calls = mutableListOf<Pair<Long, Int>>() // bookId to unitIndex
        val failures = mutableSetOf<Pair<Long, Int>>() // 恒败单位
        val failOnce = mutableSetOf<Pair<Long, Int>>() // 第一次败、重试成
        private val attempts = mutableMapOf<Pair<Long, Int>, Int>()
        var gate: CompletableDeferred<Unit>? = null // 非空时每次调用前等待放行

        suspend fun translate(
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
                else -> Result.success(1)
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
        dao: FakeTranslationDao,
        sources: Map<Long, BookTranslationSource>,
        translate: FakeTranslate,
        retryBaseDelayMs: Long = 5L,
    ): BookTranslationQueue {
        val scope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default,
        )
        queueScopes += scope
        return BookTranslationQueue(
        scope = scope,
        sourceFor = { sources[it] },
        translationDao = dao,
        translateUnitCall = translate::translate,
        currentLang = { AiTargetLang.ZH_HANS },
        betweenUnitsDelayMs = 1L,
        maxUnitRetries = 2,
        retryBaseDelayMs = retryBaseDelayMs,
        pausePollMs = 5L,
    )
    }

    /** 等某书的进度满足条件（超时即失败，别把测试挂死）。 */
    private suspend fun awaitProgress(
        queue: BookTranslationQueue,
        bookId: Long,
        timeoutMs: Long = 5_000L,
        predicate: (BookTranslationQueue.BookProgress?) -> Boolean,
    ): BookTranslationQueue.BookProgress? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val p = queue.progress.value[bookId]
            if (predicate(p)) return p
            kotlinx.coroutines.delay(5)
        }
        return queue.progress.value[bookId]
    }

    @Test
    fun `断点续译跳过 done 单位`() = runBlocking {
        val dao = FakeTranslationDao()
        dao.seedDone(7L, "ZH_HANS", 0)
        val translate = FakeTranslate()
        val queue = newQueue(this, dao, mapOf(7L to FakeSource("书", 3)), translate)

        queue.enqueueBook(7L, AiTargetLang.ZH_HANS)
        val done = awaitProgress(queue, 7L) { it?.status == BookTranslationQueue.Status.DONE }

        assertEquals(listOf(1, 2), translate.calls.map { it.second })
        assertEquals(3, done?.total)
        assertEquals(3, done?.done)
    }

    @Test
    fun `两本书串行处理`() = runBlocking {
        val translate = FakeTranslate()
        val queue = newQueue(
            this, FakeTranslationDao(),
            mapOf(7L to FakeSource("甲", 2), 8L to FakeSource("乙", 2)),
            translate,
        )

        queue.enqueueBook(7L, AiTargetLang.ZH_HANS)
        queue.enqueueBook(8L, AiTargetLang.ZH_HANS)
        awaitProgress(queue, 8L) { it?.status == BookTranslationQueue.Status.DONE }

        assertEquals(listOf(7L to 0, 7L to 1, 8L to 0, 8L to 1), translate.calls)
    }

    @Test
    fun `失败退避重试后成功与恒败记失败继续`() = runBlocking {
        val translate = FakeTranslate()
        translate.failOnce += 7L to 0 // 第一次失败，退避后重试成功
        translate.failures += 7L to 1  // 恒败：重试 2 次后放弃，继续下一单位
        val queue = newQueue(this, FakeTranslationDao(), mapOf(7L to FakeSource("书", 3)), translate)

        queue.enqueueBook(7L, AiTargetLang.ZH_HANS)
        val done = awaitProgress(queue, 7L) { it?.status == BookTranslationQueue.Status.DONE }

        // 单位 0 调用 2 次（首败 + 重试成），单位 1 调用 3 次（首次 + 2 次重试），单位 2 一次
        assertEquals(
            listOf(0, 0, 1, 1, 1, 2),
            translate.calls.map { it.second },
        )
        assertEquals(2, done?.done)
        assertEquals(1, done?.failedUnits)
    }

    @Test
    fun `插队单位提前为下一个处理`() = runBlocking {
        val translate = FakeTranslate()
        val gate = CompletableDeferred<Unit>()
        translate.gate = gate
        val queue = newQueue(this, FakeTranslationDao(), mapOf(7L to FakeSource("书", 3)), translate)

        queue.enqueueBook(7L, AiTargetLang.ZH_HANS)
        // 等队列翻完第一个单位（gate 放行后 calls 出现 0）再发插队
        gate.complete(Unit)
        awaitProgress(queue, 7L) { translate.calls.isNotEmpty() }
        queue.jumpQueue(7L, 2)
        awaitProgress(queue, 7L) { it?.status == BookTranslationQueue.Status.DONE }

        assertEquals(listOf(0, 2, 1), translate.calls.map { it.second })
    }

    @Test
    fun `书不在队列时插队 = 单单位后台直译`() = runBlocking {
        val translate = FakeTranslate()
        val queue = newQueue(this, FakeTranslationDao(), mapOf(7L to FakeSource("书", 3)), translate)

        queue.jumpQueue(7L, 1)

        val deadline = System.currentTimeMillis() + 5_000L
        while (translate.calls.isEmpty() && System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(5)
        }
        assertEquals(listOf(7L to 1), translate.calls)
        assertTrue(queue.progress.value.isEmpty()) // 直译路径不进队列进度表
    }

    @Test
    fun `取消进行中的书：当前单位被中断且不再处理后续单位`() = runBlocking {
        val translate = FakeTranslate()
        val gate = CompletableDeferred<Unit>()
        translate.gate = gate
        val queue = newQueue(this, FakeTranslationDao(), mapOf(7L to FakeSource("书", 3)), translate)

        queue.enqueueBook(7L, AiTargetLang.ZH_HANS)
        queue.cancel(7L)
        gate.complete(Unit) // 放行可能被卡住的第一个单位（取消应让其中断/结果被丢弃）
        kotlinx.coroutines.delay(300) // 给消费循环足够的调度窗口

        assertTrue(
            "取消后不应翻完全部单位：${translate.calls}",
            translate.calls.size < 3,
        )
        assertTrue(
            "状态不应是 DONE：${queue.progress.value[7L]}",
            queue.progress.value[7L]?.status != BookTranslationQueue.Status.DONE,
        )
    }

    @Test
    fun `暂停期间不处理新单位恢复后继续`() = runBlocking {
        val translate = FakeTranslate()
        val gate = CompletableDeferred<Unit>()
        translate.gate = gate
        val queue = newQueue(this, FakeTranslationDao(), mapOf(7L to FakeSource("书", 2)), translate)

        queue.enqueueBook(7L, AiTargetLang.ZH_HANS)
        queue.pause()
        gate.complete(Unit)
        // 暂停中：给足时间也不应翻完（第一单位放行后暂停生效于下一单位前）
        kotlinx.coroutines.delay(100)
        val pausedCalls = translate.calls.size
        queue.resume()
        awaitProgress(queue, 7L) { it?.status == BookTranslationQueue.Status.DONE }

        assertTrue(pausedCalls <= 1)
        assertEquals(2, translate.calls.size)
    }
}

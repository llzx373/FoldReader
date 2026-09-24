package com.llzx373.foldreader.feature.translate

import com.llzx373.foldreader.core.ai.AiTargetLang
import com.llzx373.foldreader.core.data.db.TranslationDao
import com.llzx373.foldreader.core.data.db.TranslationEntity
import com.llzx373.foldreader.core.translate.TranslationUnit
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 队列消费侧对一本书的读取面（生产实现开 parser 内容源；测试直接喂内存文本）。
 */
interface BookTranslationSource {
    val bookTitle: String

    /** 单位清单（首次计算后由实现方落盘 saveUnits，断点续译共享同一份切块边界）。 */
    suspend fun units(lang: AiTargetLang): List<TranslationUnit>

    suspend fun readUnitText(unit: TranslationUnit): String

    fun close()
}

/**
 * 全书批量翻译队列（M20）：照 `BookPrewarmQueue` 的「Channel + 单协程串行」骨架，
 * 扩展断点续译 / 失败退避重试 / 暂停恢复 / 按书取消 / 未译单位插队（R5）。
 *
 * - 串行：一次只翻一本书的一个单位，单位间让出 [betweenUnitsDelayMs]；
 * - 断点续译：入队时读台账，done 单位跳过——重入队同一本书就是续译；
 * - 失败重试：单单位失败按 [retryBaseDelayMs] 指数退避再试 [maxUnitRetries] 次
 *   （引擎内部另有一次整体重试），仍败记 failed 继续下一单位，不卡住整本书；
 * - 插队（R5）：[jumpQueue] 把指定单位提前为下一个处理；书不在队列时单单位后台直译；
 * - 调度与 IO 全走注入（[translateUnitCall] / [sourceFor]），核心调度可用假引擎单测。
 */
class BookTranslationQueue(
    private val scope: CoroutineScope,
    private val sourceFor: suspend (bookId: Long) -> BookTranslationSource?,
    private val translationDao: TranslationDao,
    /** 单单位翻译调用（生产 = TranslateEngine.translateUnit；测试 = fake）。 */
    private val translateUnitCall:
        suspend (bookId: Long, bookTitle: String, unit: TranslationUnit, unitText: String, lang: AiTargetLang) -> Result<Int>,
    /** 当前目标语言（插队直译路径用，与阅读器口径一致取设置页值）。 */
    private val currentLang: suspend () -> AiTargetLang,
    private val betweenUnitsDelayMs: Long = BETWEEN_UNITS_DELAY_MS,
    private val maxUnitRetries: Int = MAX_UNIT_RETRIES,
    private val retryBaseDelayMs: Long = RETRY_BASE_DELAY_MS,
    private val pausePollMs: Long = PAUSE_POLL_MS,
) {

    enum class Status { QUEUED, RUNNING, PAUSED, DONE, CANCELLED, FAILED }

    /** 一本书的队列进度（UI / 前台服务通知的数据源）。 */
    data class BookProgress(
        val bookId: Long,
        val lang: AiTargetLang,
        val total: Int,
        val done: Int,
        val currentUnitTitle: String?,
        val status: Status,
        val failedUnits: Int = 0,
    )

    private data class Job(val bookId: Long, val lang: AiTargetLang)

    private val pending = Channel<Job>(Channel.UNLIMITED)
    /** 在渠道里排队 / 正在处理的书（插队与重入队判据）；process 取出即移除。 */
    private val inQueue = ConcurrentHashMap.newKeySet<Long>()
    private val cancelled = ConcurrentHashMap.newKeySet<Long>()
    /** 插队请求：bookId → 提前处理的单位号（下一单位循环消费掉）。 */
    private val jumpRequests = ConcurrentHashMap<Long, Int>()

    private val _progress = MutableStateFlow<Map<Long, BookProgress>>(emptyMap())
    val progress: StateFlow<Map<Long, BookProgress>> = _progress.asStateFlow()

    @Volatile
    private var paused = false
    private var currentBookId: Long? = null
    private var currentUnitJob: kotlinx.coroutines.Job? = null

    init {
        scope.launch {
            for (job in pending) {
                inQueue.remove(job.bookId)
                if (cancelled.remove(job.bookId)) {
                    removeProgress(job.bookId)
                    continue
                }
                process(job)
                if (betweenUnitsDelayMs > 0) delay(betweenUnitsDelayMs)
            }
        }
    }

    /** 该书是否在队列中（排队或进行中）——插队重排的判据。 */
    fun isActive(bookId: Long): Boolean = inQueue.contains(bookId) || currentBookId == bookId

    /** 是否整队暂停中（前台服务通知展示用）。 */
    fun isPaused(): Boolean = paused

    /**
     * 入队（幂等）：已在队列直接忽略；再次入队 = 断点续译（done 单位跳过）。
     * 入队前先把该书过期的 CANCELLED/FAILED 终态清掉。
     */
    fun enqueueBook(bookId: Long, lang: AiTargetLang) {
        if (isActive(bookId)) return
        cancelled.remove(bookId)
        updateProgress(bookId) {
            BookProgress(bookId, lang, total = 0, done = 0, currentUnitTitle = null, status = Status.QUEUED)
        }
        inQueue += bookId
        pending.trySend(Job(bookId, lang))
    }

    /** 整队暂停 / 恢复（单位粒度生效：当前单位翻完才停）。 */
    fun pause() {
        paused = true
    }

    fun resume() {
        paused = false
    }

    /**
     * 取消一本书：排队的直接作废；进行中的中断当前单位 Job（翻译协程取消，
     * 引擎不留 failed——下次入队按 pending 重译）。
     */
    fun cancel(bookId: Long) {
        cancelled += bookId
        inQueue.remove(bookId)
        jumpRequests.remove(bookId)
        if (currentBookId == bookId) currentUnitJob?.cancel()
        if (currentBookId != bookId) removeProgress(bookId)
    }

    /**
     * 插队（R5）：阅读中翻到未译单位时调用。
     * 该书在队列中 → 该单位提前为下一个处理；不在队列 → 单单位后台直译（同 M19 单单位链路）。
     */
    fun jumpQueue(bookId: Long, unitIndex: Int) {
        if (isActive(bookId)) {
            jumpRequests[bookId] = unitIndex
            return
        }
        scope.launch {
            val lang = currentLang()
            val source = sourceFor(bookId) ?: return@launch
            try {
                val units = source.units(lang)
                val unit = units.getOrNull(unitIndex) ?: return@launch
                val text = source.readUnitText(unit)
                translateUnitCall(bookId, source.bookTitle, unit, text, lang)
            } finally {
                source.close()
            }
        }
    }

    /** 进度表清理（终态条目展示完毕后由 UI 摘除；不影响台账与落盘产物）。 */
    fun removeProgress(bookId: Long) {
        _progress.value = _progress.value - bookId
    }

    private suspend fun process(job: Job) {
        val source = sourceFor(job.bookId)
        if (source == null) {
            updateProgress(job.bookId) { it?.copy(status = Status.FAILED) }
            return
        }
        currentBookId = job.bookId
        try {
            val units = source.units(job.lang)
            if (units.isEmpty()) {
                updateProgress(job.bookId) { it?.copy(status = Status.FAILED, total = 0) }
                return
            }
            val doneSet = translationDao.getForBook(job.bookId, job.lang.name)
                .filter { it.status == TranslationEntity.STATUS_DONE }
                .mapTo(HashSet()) { it.unitIndex }
            var failedUnits = 0
            var cursor = 0
            updateProgress(job.bookId) {
                BookProgress(
                    job.bookId, job.lang,
                    total = units.size, done = doneSet.size,
                    currentUnitTitle = null, status = Status.RUNNING,
                )
            }
            while (true) {
                if (job.bookId in cancelled) {
                    updateProgress(job.bookId) { it?.copy(status = Status.CANCELLED) }
                    return
                }
                while (paused) {
                    updateProgress(job.bookId) { it?.copy(status = Status.PAUSED) }
                    delay(pausePollMs)
                    if (job.bookId in cancelled) {
                        updateProgress(job.bookId) { it?.copy(status = Status.CANCELLED) }
                        return
                    }
                }
                updateProgress(job.bookId) { it?.copy(status = Status.RUNNING) }
                // 插队优先：被提前的单位（未译）先做，做完回到正常顺序
                val jumped = jumpRequests.remove(job.bookId)
                    ?.takeIf { it in units.indices && it !in doneSet }
                val unitIndex = jumped ?: run {
                    while (cursor < units.size && units[cursor].index in doneSet) cursor++
                    if (cursor < units.size) cursor++ else -1
                }
                if (unitIndex < 0) break // 全部 done：这本书翻完了
                val unit = units[unitIndex]
                updateProgress(job.bookId) {
                    it?.copy(currentUnitTitle = unit.title, failedUnits = failedUnits)
                }
                val ok = translateWithRetry(job, source, unit)
                if (job.bookId in cancelled && !ok) {
                    // 取消中断了当前单位：不计成败，按取消收尾
                    updateProgress(job.bookId) { it?.copy(status = Status.CANCELLED) }
                    return
                }
                if (ok) {
                    doneSet += unit.index
                    updateProgress(job.bookId) { it?.copy(done = doneSet.size) }
                } else {
                    failedUnits++
                    updateProgress(job.bookId) { it?.copy(failedUnits = failedUnits) }
                }
                if (betweenUnitsDelayMs > 0) delay(betweenUnitsDelayMs)
            }
            updateProgress(job.bookId) {
                it?.copy(status = Status.DONE, currentUnitTitle = null, failedUnits = failedUnits)
            }
        } finally {
            currentBookId = null
            source.close()
        }
    }

    /** 单单位翻译 + 指数退避重试：子 Job 承载，[cancel] 可单独中断而不掀掉整个消费循环。 */
    private suspend fun translateWithRetry(
        job: Job,
        source: BookTranslationSource,
        unit: TranslationUnit,
    ): Boolean {
        val text = source.readUnitText(unit)
        var attempt = 0
        var result: Result<Int>? = null
        kotlinx.coroutines.coroutineScope {
            val unitJob = launch {
                while (true) {
                    result = translateUnitCall(job.bookId, source.bookTitle, unit, text, job.lang)
                    if (result?.isSuccess == true) break
                    attempt++
                    if (attempt > maxUnitRetries) break
                    delay(retryBaseDelayMs * (1L shl (attempt - 1)))
                }
            }
            currentUnitJob = unitJob
            unitJob.join()
        }
        currentUnitJob = null
        return result?.isSuccess == true
    }

    private inline fun updateProgress(bookId: Long, block: (BookProgress?) -> BookProgress?) {
        val next = block(_progress.value[bookId]) ?: return
        _progress.value = _progress.value + (bookId to next)
    }

    private companion object {
        /** 单位间让出：串行翻译不占满，阅读/翻页保持流畅。 */
        const val BETWEEN_UNITS_DELAY_MS = 200L

        /** 单单位失败后的队列级重试次数（引擎内部另有一次整体重试）。 */
        const val MAX_UNIT_RETRIES = 2

        /** 退避基数：第 1/2 次重试前各等 1× / 2×。 */
        const val RETRY_BASE_DELAY_MS = 1_000L

        /** 暂停态的轮询间隔。 */
        const val PAUSE_POLL_MS = 300L
    }
}

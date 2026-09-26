package com.llzx373.foldreader.feature.translate

import com.llzx373.foldreader.core.ai.AiTargetLang
import com.llzx373.foldreader.core.data.db.ComicPageTranslationDao
import com.llzx373.foldreader.core.data.db.ComicPageTranslationEntity
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 漫画整卷翻译队列（M22）：照 `BookTranslationQueue` 的「Channel + 单协程串行」骨架，
 * 单位从电子书的章节换成漫画的页——页就是翻译单位，无内容源与插队概念。
 *
 * - 串行：一次只翻一本书的一页，页间让出 [betweenPagesDelayMs]；
 * - 断点续译：入队时读台账（[ComicPageTranslationDao.getForBook]），
 *   [ComicPageTranslationEntity.STATUS_DONE] 的页跳过——重入队同一本书就是续译；
 * - 失败重试：单页失败按 [retryBaseDelayMs] 指数退避再试 [maxPageRetries] 次
 *   （引擎内部另有一次整体重试），仍败记 failed 继续下一页，不卡住整卷；
 *   全部页处理完（含跳过）置 DONE，有 failed 页仍 DONE 但 failedPages 计数展示；
 * - 调度与 IO 全走注入（[translatePageCall] / [bookFor]），核心调度可用假引擎单测。
 */
class ComicTranslationQueue(
    private val scope: CoroutineScope,
    /** 书名与页数（进度展示 / 页数上限）；null = 书不存在。 */
    private val bookFor: suspend (bookId: Long) -> Pair<String, Int>?,
    private val pageDao: ComicPageTranslationDao,
    /** 单页翻译调用（生产 = ComicTranslateEngine.translatePage；测试 = fake）。 */
    private val translatePageCall:
        suspend (bookId: Long, bookTitle: String, pageIndex: Int, lang: AiTargetLang) -> Result<Int>,
    private val betweenPagesDelayMs: Long = BETWEEN_PAGES_DELAY_MS,
    private val maxPageRetries: Int = MAX_PAGE_RETRIES,
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
        val failedPages: Int = 0,
        val status: Status,
    )

    private data class Job(val bookId: Long, val lang: AiTargetLang)

    private val pending = Channel<Job>(Channel.UNLIMITED)
    /** 在渠道里排队 / 正在处理的书（重入队判据）；process 取出即移除。 */
    private val inQueue = ConcurrentHashMap.newKeySet<Long>()
    private val cancelled = ConcurrentHashMap.newKeySet<Long>()

    private val _progress = MutableStateFlow<Map<Long, BookProgress>>(emptyMap())
    val progress: StateFlow<Map<Long, BookProgress>> = _progress.asStateFlow()

    @Volatile
    private var paused = false
    private var currentBookId: Long? = null
    private var currentPageJob: kotlinx.coroutines.Job? = null

    init {
        scope.launch {
            for (job in pending) {
                inQueue.remove(job.bookId)
                if (cancelled.remove(job.bookId)) {
                    removeProgress(job.bookId)
                    continue
                }
                process(job)
                if (betweenPagesDelayMs > 0) delay(betweenPagesDelayMs)
            }
        }
    }

    /** 该书是否在队列中（排队或进行中）。 */
    fun isActive(bookId: Long): Boolean = inQueue.contains(bookId) || currentBookId == bookId

    /** 是否整队暂停中（前台服务通知展示用）。 */
    fun isPaused(): Boolean = paused

    /**
     * 入队（幂等）：已在队列直接忽略；再次入队 = 断点续译（done 页跳过）。
     * 入队前先把该书过期的 CANCELLED/FAILED 终态清掉。
     */
    fun enqueueBook(bookId: Long, lang: AiTargetLang) {
        if (isActive(bookId)) return
        cancelled.remove(bookId)
        updateProgress(bookId) {
            BookProgress(bookId, lang, total = 0, done = 0, status = Status.QUEUED)
        }
        inQueue += bookId
        pending.trySend(Job(bookId, lang))
    }

    /** 整队暂停 / 恢复（页粒度生效：当前页翻完才停）。 */
    fun pause() {
        paused = true
    }

    fun resume() {
        paused = false
    }

    /**
     * 取消一本书：排队的直接作废；进行中的中断当前页 Job（翻译协程取消，
     * 引擎取消不留 failed——下次入队按未译重译）。
     */
    fun cancel(bookId: Long) {
        cancelled += bookId
        inQueue.remove(bookId)
        if (currentBookId == bookId) currentPageJob?.cancel()
        if (currentBookId != bookId) removeProgress(bookId)
    }

    /** 进度表清理（终态条目展示完毕后由 UI 摘除；不影响台账与落盘产物）。 */
    fun removeProgress(bookId: Long) {
        _progress.value = _progress.value - bookId
    }

    private suspend fun process(job: Job) {
        val book = bookFor(job.bookId)
        if (book == null) {
            updateProgress(job.bookId) { it?.copy(status = Status.FAILED) }
            return
        }
        val (bookTitle, pageCount) = book
        currentBookId = job.bookId
        try {
            if (pageCount <= 0) {
                updateProgress(job.bookId) { it?.copy(status = Status.FAILED, total = 0) }
                return
            }
            val doneSet = pageDao.getForBook(job.bookId, job.lang.name)
                .filter { it.status == ComicPageTranslationEntity.STATUS_DONE }
                .mapTo(HashSet()) { it.pageIndex }
            var failedPages = 0
            var cursor = 0
            updateProgress(job.bookId) {
                BookProgress(
                    job.bookId, job.lang,
                    total = pageCount, done = doneSet.size,
                    status = Status.RUNNING,
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
                while (cursor < pageCount && cursor in doneSet) cursor++
                if (cursor >= pageCount) break // 全部 done：这本书翻完了
                val pageIndex = cursor++
                val ok = translateWithRetry(job, bookTitle, pageIndex)
                if (job.bookId in cancelled && !ok) {
                    // 取消中断了当前页：不计成败，按取消收尾
                    updateProgress(job.bookId) { it?.copy(status = Status.CANCELLED) }
                    return
                }
                if (ok) {
                    doneSet += pageIndex
                    updateProgress(job.bookId) { it?.copy(done = doneSet.size) }
                } else {
                    failedPages++
                    updateProgress(job.bookId) { it?.copy(failedPages = failedPages) }
                }
                if (betweenPagesDelayMs > 0) delay(betweenPagesDelayMs)
            }
            updateProgress(job.bookId) {
                it?.copy(status = Status.DONE, failedPages = failedPages)
            }
        } finally {
            currentBookId = null
        }
    }

    /** 单页翻译 + 指数退避重试：子 Job 承载，[cancel] 可单独中断而不掀掉整个消费循环。 */
    private suspend fun translateWithRetry(
        job: Job,
        bookTitle: String,
        pageIndex: Int,
    ): Boolean {
        var attempt = 0
        var result: Result<Int>? = null
        kotlinx.coroutines.coroutineScope {
            val pageJob = launch {
                while (true) {
                    result = translatePageCall(job.bookId, bookTitle, pageIndex, job.lang)
                    if (result?.isSuccess == true) break
                    attempt++
                    if (attempt > maxPageRetries) break
                    delay(retryBaseDelayMs * (1L shl (attempt - 1)))
                }
            }
            currentPageJob = pageJob
            pageJob.join()
        }
        currentPageJob = null
        return result?.isSuccess == true
    }

    private inline fun updateProgress(bookId: Long, block: (BookProgress?) -> BookProgress?) {
        val next = block(_progress.value[bookId]) ?: return
        _progress.value = _progress.value + (bookId to next)
    }

    private companion object {
        /** 页间让出：串行翻译不占满，阅读/翻页保持流畅。 */
        const val BETWEEN_PAGES_DELAY_MS = 200L

        /** 单页失败后的队列级重试次数（引擎内部另有一次整体重试）。 */
        const val MAX_PAGE_RETRIES = 2

        /** 退避基数：第 1/2 次重试前各等 1× / 2×。 */
        const val RETRY_BASE_DELAY_MS = 1_000L

        /** 暂停态的轮询间隔。 */
        const val PAUSE_POLL_MS = 300L
    }
}

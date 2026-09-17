package com.llzx373.foldreader.feature.reader

import com.llzx373.foldreader.core.format.SearchHit

/**
 * 搜索命中批处理：决定「什么时候把累积的命中发布给 UI」。
 *
 * 逐条发布会让每个命中都拷贝一次完整命中列表（O(n²)）并发射一次 StateFlow，
 * 大书上命中上万条时 UI 会被拖垮。这里把发布收敛为两类时机：
 * 累积条数达到 [batchSize]，或距上次发布已过 [intervalMs]。
 *
 * 纯逻辑、不持时钟：调用方传入 `nowMs`，便于单测。
 */
class SearchHitBatcher(
    private val batchSize: Int = HIT_BATCH_SIZE,
    private val intervalMs: Long = PUBLISH_INTERVAL_MS,
) {

    private val buffer = ArrayList<SearchHit>()

    /** 已累积的全部命中。调用方发布时应取快照（`toList()`），不要就地修改。 */
    val hits: List<SearchHit> get() = buffer

    private var pendingSincePublish = 0
    private var lastPublishMs = 0L

    /** 记入一个命中；返回 true 表示本次应当发布。 */
    fun onHit(hit: SearchHit, nowMs: Long): Boolean {
        buffer += hit
        pendingSincePublish++
        return pendingSincePublish >= batchSize || elapsed(nowMs)
    }

    /** 扫描进度事件；返回 true 表示本次应当顺带发布（同样受时间节流约束）。 */
    fun onProgress(nowMs: Long): Boolean = elapsed(nowMs)

    /** 调用方完成一次发布后必须调用，用于重置节流窗口。 */
    fun onPublished(nowMs: Long) {
        pendingSincePublish = 0
        lastPublishMs = nowMs
    }

    private fun elapsed(nowMs: Long): Boolean = nowMs - lastPublishMs >= intervalMs

    companion object {
        /** 条数阈值：再密也不至于让下拉列表卡住的上限。 */
        const val HIT_BATCH_SIZE = 200

        /** 时间阈值：约每秒 6~7 次发布，用户感知为「实时」。 */
        const val PUBLISH_INTERVAL_MS = 150L
    }
}

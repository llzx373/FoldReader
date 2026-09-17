package com.llzx373.foldreader.core.debug

import java.util.concurrent.atomic.AtomicBoolean

/**
 * 按需刷新调度：只有真的有数据要落盘时才排一次任务。
 *
 * 原先用固定周期的 `scheduleWithFixedDelay`，没有数据时也每 500ms 唤醒一次调度线程；
 * 日志是偶发写入，空闲期的唤醒纯属浪费。
 *
 * 线程安全：多个线程可能同时写日志，[onData] 用 CAS 保证同一时刻最多一个待执行任务。
 */
internal class FlushScheduler(
    /** 排一次延迟任务；返回 false 表示没能排上（如执行器已关闭），此时允许后续重试。 */
    private val schedule: (Runnable) -> Boolean,
    private val flush: () -> Unit,
) {
    private val scheduled = AtomicBoolean(false)

    /** 有新数据时调用。 */
    fun onData() {
        if (!scheduled.compareAndSet(false, true)) return
        if (!schedule(::dispatch)) scheduled.set(false)
    }

    /** 立即刷新（用户打点、切换开关等要求马上落盘的场合）。 */
    fun flushNow() = flush()

    private fun dispatch() {
        scheduled.set(false)
        flush()
    }
}

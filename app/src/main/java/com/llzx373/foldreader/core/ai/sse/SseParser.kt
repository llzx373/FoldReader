package com.llzx373.foldreader.core.ai.sse

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import okhttp3.Call
import okio.BufferedSource

/**
 * 一条 SSE 事件。[event] 缺省为 null；多行 `data:` 按 SSE 规范用 '\n' 拼接。
 */
data class SseEvent(val event: String?, val data: String)

/**
 * 把 SSE 字节流读成事件序列（阻塞读，调用方需在 IO 调度器上消费）。
 *
 * 按行读取，`readUtf8Line` 天然容忍 \r\n 与粘包；忽略注释行（':' 开头）与空 data 事件；
 * 空行派发事件；流结束时若还有未派发的缓冲事件则补发。
 */
internal fun BufferedSource.readSseEvents(): Sequence<SseEvent> = sequence {
    var event: String? = null
    val data = StringBuilder()

    fun dispatchPending(): SseEvent? {
        if (data.isEmpty()) {
            event = null
            return null
        }
        val ev = SseEvent(event, data.toString())
        event = null
        data.clear()
        return ev
    }

    while (true) {
        val line = readUtf8Line() ?: break
        when {
            line.isEmpty() -> dispatchPending()?.let { yield(it) }
            line.startsWith(":") -> Unit // 注释行，忽略
            line.startsWith("event:") -> event = line.substring(6).trim()
            line.startsWith("data:") -> {
                if (data.isNotEmpty()) data.append('\n')
                data.append(line.substring(5).removePrefix(" "))
            }
        }
    }
    dispatchPending()?.let { yield(it) }
}

/**
 * 把阻塞的 OkHttp 调用绑到协程取消上：收集端取消时 `call.cancel()` 立刻中断
 * 底层 HTTP（execute/read），不再干等到 readTimeout——「取消会中断底层 HTTP」
 * 是 [com.llzx373.foldreader.core.ai.AiProvider] 注释对外的承诺。
 *
 * 取消后 OkHttp 抛 IOException("Canceled")：调用方的 catch 必须先
 * `ensureActive()` 把真正的协程取消抛回去，不能包成网络错误。
 */
internal suspend inline fun <T> Call.withCancellation(block: (Call) -> T): T {
    // invokeOnCancellation 是内部 API；invokeOnCompletion 等价覆盖——
    // 正常完成时 finally 的 dispose 会抢在前面摘掉注册，cancel() 不会误伤已完成的调用
    val handle = currentCoroutineContext().job.invokeOnCompletion { cause ->
        if (cause != null) cancel()
    }
    return try {
        block(this)
    } finally {
        handle.dispose()
    }
}

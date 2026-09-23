package com.llzx373.foldreader.core.ai.sse

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

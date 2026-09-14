package com.llzx373.foldreader.core.format

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** 一次搜索命中：[offset] 为命中起始字符偏移；[context] 为 ±contextChars 摘要。 */
data class SearchHit(
    val offset: Long,
    val context: String,
    val matchStartInContext: Int,
    val matchLength: Int,
)

/**
 * 全文分块扫描：按 [windowChars] 字符窗口流式读取（不整文件入内存），
 * 窗口步进 = windowChars - query.length + 1，保证任意命中起点都完整落在某窗口内；
 * 每窗口只上报"归本窗口所有"（命中起点 < 步进）的命中，跨边界命中不丢不重。
 * 匹配 case-insensitive（对中文无影响）。可取消（协程取消即停）。
 */
suspend fun searchContent(
    content: BookContent,
    query: String,
    windowChars: Int = 4096,
    contextChars: Int = 20,
    onHit: suspend (SearchHit) -> Unit,
    onProgress: suspend (scannedChars: Long) -> Unit = {},
) {
    val needle = query.trim().lowercase()
    val total = content.charCount
    if (needle.isEmpty() || total <= 0L) return
    val step = (windowChars - needle.length + 1).coerceAtLeast(1)
    var windowStart = 0L
    while (windowStart < total) {
        currentCoroutineContext().ensureActive()
        val windowEnd = minOf(windowStart + windowChars, total)
        val window = content.read(windowStart until windowEnd)
        val hay = window.lowercase()
        val ownedEnd = minOf(step.toLong(), windowEnd - windowStart)
        var idx = hay.indexOf(needle)
        while (idx >= 0 && idx < ownedEnd) {
            val abs = windowStart + idx
            val contextStart = maxOf(0L, abs - contextChars)
            val contextEnd = minOf(total, abs + needle.length + contextChars)
            val context = content.read(contextStart until contextEnd)
            onHit(
                SearchHit(
                    offset = abs,
                    context = context,
                    matchStartInContext = (abs - contextStart).toInt(),
                    matchLength = needle.length,
                ),
            )
            idx = hay.indexOf(needle, idx + 1)
        }
        onProgress(windowEnd)
        windowStart += step
    }
}

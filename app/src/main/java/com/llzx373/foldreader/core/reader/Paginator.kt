package com.llzx373.foldreader.core.reader

import com.llzx373.foldreader.core.format.BookContent

data class PaginatorKey(
    val bookId: Long,
    val widthPx: Int,
    val heightPx: Int,
    val density: Float,
    val scaledDensity: Float,
    val config: LayoutConfig,
)

class PaginatorStore(private val maxPagesPerBook: Int = 64) {
    private val caches = HashMap<PaginatorKey, PageCache<Long, Page>>()

    @Synchronized
    fun getOrCreate(key: PaginatorKey): PageCache<Long, Page> =
        caches.getOrPut(key) { PageCache(maxPagesPerBook) }

    @Synchronized
    fun remove(bookId: Long) {
        caches.keys.removeAll { it.bookId == bookId }
    }
}

class Paginator(
    private val content: BookContent,
    private val config: LayoutConfig,
    private val measurer: TextMeasurer,
    private val widthPx: Int,
    private val heightPx: Int,
    private val density: Float,
    private val scaledDensity: Float,
    private val cache: PageCache<Long, Page> = PageCache(64),
) {
    private val bounds = mutableListOf(0L)

    private val fontSizePx = config.fontSizeSp * scaledDensity
    private val lineHeightPx = fontSizePx * config.lineSpacingMultiplier
    private val paragraphSpacingPx = fontSizePx * config.paragraphSpacingEm
    private val indentPx = (config.firstLineIndentChars * fontSizePx).toInt()
    private val availHeightPx =
        heightPx - (config.marginTopDp + config.marginBottomDp) * density
    private val fullTextWidthPx =
        widthPx - (config.marginLeftDp + config.marginRightDp) * density
    private val textWidthPx = minOf(fullTextWidthPx, config.maxLineChars * fontSizePx)
    private val horizontalInsetPx = (widthPx - textWidthPx) / 2f

    suspend fun pageAt(offset: Long): Page {
        val target = offset.coerceIn(0L, content.charCount)
        var idx = bounds.binarySearch(target)
        idx = if (idx >= 0) idx else -idx - 2
        var page = getOrPaginate(bounds[idx])
        while (page.charEnd <= target && page.charEnd < content.charCount) {
            page = advance(page.charEnd)
        }
        return page
    }

    suspend fun pageBefore(offset: Long): Page? {
        if (offset <= 0L) return null
        val current = pageAt(offset)
        val target = current.charStart
        if (target == 0L) return null
        var idx = bounds.binarySearch(target)
        idx = if (idx >= 0) idx - 1 else -idx - 2
        var page = getOrPaginate(bounds[idx])
        while (page.charEnd < target) {
            page = advance(page.charEnd)
        }
        return page
    }

    suspend fun pageAfter(offset: Long): Page? {
        val current = pageAt(offset)
        if (current.charEnd >= content.charCount) return null
        return pageAt(current.charEnd)
    }

    private suspend fun advance(boundary: Long): Page {
        if (boundary > bounds.last()) bounds += boundary
        return getOrPaginate(boundary)
    }

    private suspend fun getOrPaginate(start: Long): Page =
        cache.get(start) ?: paginateFrom(start).also { cache.put(start, it) }

    private suspend fun paginateFrom(start: Long): Page {
        val producer = LineProducer(start)
        val lines = mutableListOf<PageLine>()
        var used = 0f
        while (true) {
            val line = producer.next() ?: break
            val extra = if (line.isParagraphStart && lines.isNotEmpty()) paragraphSpacingPx else 0f
            if (lines.isNotEmpty() && used + extra + lineHeightPx > availHeightPx) {
                producer.pushBack(line)
                break
            }
            lines += line
            used += extra + lineHeightPx
        }
        return Page(
            charStart = start,
            charEnd = lines.lastOrNull()?.charEnd ?: start,
            lines = lines,
            paddingLeft = horizontalInsetPx,
            paddingRight = horizontalInsetPx,
        )
    }

    private inner class LineProducer(private var pos: Long) {
        private val pending = ArrayDeque<PageLine>()

        suspend fun next(): PageLine? {
            if (pending.isEmpty()) loadParagraph()
            return pending.removeFirstOrNull()
        }

        fun pushBack(line: PageLine) = pending.addFirst(line)

        private suspend fun loadParagraph() {
            if (pos >= content.charCount) return
            val windowEnd = minOf(pos + SCAN_CHARS, content.charCount)
            val window = content.read(pos until windowEnd)
            var newlineLen = 0
            var bodyEnd = window.length
            val nl = window.indexOf('\n')
            if (nl >= 0) {
                bodyEnd = nl
                newlineLen = 1
                if (nl > 0 && window[nl - 1] == '\r') {
                    bodyEnd = nl - 1
                    newlineLen = 2
                }
            }
            val body = window.substring(0, bodyEnd)
            val paraEnd = pos + bodyEnd + newlineLen
            if (body.isEmpty()) {
                pending += PageLine(pos, paraEnd, "", isParagraphStart = true, isParagraphEnd = true)
            } else {
                val raw = measurer.measureLineBreaks(
                    body, textWidthPx.toInt(), indentPx, fontSizePx,
                    config.letterSpacingEm, config.typeface,
                )
                val breaks = Kinsoku.adjust(body, raw)
                var lineStart = 0
                for ((i, b0) in breaks.withIndex()) {
                    val b = maxOf(b0, lineStart + 1)
                    val isLast = i == breaks.lastIndex
                    pending += PageLine(
                        charStart = pos + lineStart,
                        charEnd = if (isLast) paraEnd else pos + b,
                        text = body.substring(lineStart, b),
                        isParagraphStart = lineStart == 0,
                        isParagraphEnd = isLast,
                    )
                    lineStart = b
                }
            }
            pos = paraEnd
        }
    }

    private companion object {
        const val SCAN_CHARS = 4096L
    }
}

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
    private val diskCache: PageDiskCache? = null,
    private val diskKey: PaginatorKey? = null,
) {
    private val bounds = mutableListOf(0L)
    private val boundsLock = Any()
    @Volatile private var diskBoundsLoaded = false
    @Volatile private var fullBoundsFromDisk = false

    private val fontSizePx = config.fontSizeSp * scaledDensity
    private val lineHeightPx = fontSizePx * config.lineSpacingMultiplier
    private val paragraphSpacingPx = fontSizePx * config.paragraphSpacingEm
    private val indentPx = (config.firstLineIndentChars * fontSizePx).toInt()
    private val availHeightPx =
        (heightPx - (config.marginTopDp + config.marginBottomDp) * density).coerceAtLeast(lineHeightPx)
    private val marginLeftPx = config.marginLeftDp * density
    private val marginRightPx = config.marginRightDp * density
    private val fullTextWidthPx = (widthPx - marginLeftPx - marginRightPx).coerceAtLeast(fontSizePx)
    private val textWidthPx = minOf(fullTextWidthPx, config.maxLineChars * fontSizePx).coerceAtLeast(fontSizePx)
    private val centerSlackPx = (fullTextWidthPx - textWidthPx).coerceAtLeast(0f) / 2f
    private val paddingLeftPx = marginLeftPx + centerSlackPx
    private val paddingRightPx = marginRightPx + centerSlackPx

    suspend fun pageAt(offset: Long): Page {
        ensureDiskBounds()
        val target = offset.coerceIn(0L, content.charCount)
        var page = getOrPaginate(boundAtOrBefore(target))
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
        var page = getOrPaginate(boundAtOrBefore(target - 1))
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

    private fun boundAtOrBefore(target: Long): Long = synchronized(boundsLock) {
        var idx = bounds.binarySearch(target)
        idx = if (idx >= 0) idx else -idx - 2
        bounds[idx.coerceAtLeast(0)]
    }

    private suspend fun advance(boundary: Long): Page {
        synchronized(boundsLock) {
            if (boundary > bounds.last()) bounds += boundary
        }
        return getOrPaginate(boundary)
    }

    private fun ensureDiskBounds() {
        if (diskBoundsLoaded) return
        diskBoundsLoaded = true
        val key = diskKey ?: return
        val stored = runCatching { diskCache?.load(key, content.charCount) }.getOrNull() ?: return
        if (stored.size < 2) return
        synchronized(boundsLock) {
            if (bounds.size == 1) {
                bounds.clear()
                stored.forEach { bounds += it }
                fullBoundsFromDisk = true
            }
        }
    }

    val hasFullBoundaryIndex: Boolean get() = diskBoundsLoaded && fullBoundsFromDisk

    /** offset 所在页的页序（0-based）：bounds 中小于等于 offset 的最后一条边界序号。 */
    fun pageIndexOf(offset: Long): Int = synchronized(boundsLock) {
        var idx = bounds.binarySearch(offset.coerceAtLeast(0L))
        idx = if (idx >= 0) idx else -idx - 2
        idx.coerceAtLeast(0)
    }

    val boundaryPageCount: Int get() = synchronized(boundsLock) { bounds.size }

    fun persistBounds() {
        val key = diskKey ?: return
        val cache = diskCache ?: return
        val snapshot = synchronized(boundsLock) { bounds.toLongArray() }
        runCatching { cache.save(key, content.charCount, snapshot) }
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
            paddingLeft = paddingLeftPx,
            paddingRight = paddingRightPx,
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
            val paragraphStart = pos == 0L || content.read(pos - 1 until pos) == "\n"
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
            val paragraphEnd = newlineLen > 0 || paraEnd >= content.charCount
            if (body.isEmpty()) {
                pending += PageLine(pos, paraEnd, "", isParagraphStart = paragraphStart, isParagraphEnd = true)
            } else {
                val paraIndentPx =
                    if (!config.autoIndentEnabled || hasLeadingIndent(body)) 0 else indentPx
                val raw = measurer.measureLineBreaks(
                    body, textWidthPx.toInt(), paraIndentPx, fontSizePx,
                    config.letterSpacingEm, config.typeface,
                )
                val breaks = Kinsoku.adjust(body, raw)
                val starts = ArrayList<Int>(breaks.size)
                val ends = ArrayList<Int>(breaks.size)
                var lineStart = 0
                for (b0 in breaks) {
                    val b = maxOf(b0, lineStart + 1).coerceAtMost(body.length)
                    if (b <= lineStart) continue
                    starts += lineStart
                    ends += b
                    lineStart = b
                    if (lineStart >= body.length) break
                }
                for (i in starts.indices) {
                    val isLast = i == starts.lastIndex
                    pending += PageLine(
                        charStart = pos + starts[i],
                        charEnd = if (isLast) paraEnd else pos + ends[i],
                        text = body.substring(starts[i], ends[i]),
                        isParagraphStart = paragraphStart && i == 0,
                        isParagraphEnd = isLast && paragraphEnd,
                    )
                }
            }
            pos = paraEnd
        }
    }

    private companion object {
        const val SCAN_CHARS = 4096L
    }
}

package com.llzx373.foldreader.core.reader

import com.llzx373.foldreader.core.format.BookContent

data class PaginatorKey(
    val bookId: Long,
    val widthPx: Int,
    val heightPx: Int,
    val density: Float,
    val scaledDensity: Float,
    val config: LayoutConfig,
    /** 摄像头开孔规避（参与磁盘缓存键）。 */
    val avoidance: PageAvoidance = PageAvoidance(),
)

/**
 * 摄像头开孔规避：oddTopLines = 奇数序页（跨页右页）顶部预留行数，
 * evenBottomLines = 偶数序页（跨页左页）底部预留行数。预留行所在区域留白。
 */
data class PageAvoidance(
    val oddTopLines: Int = 0,
    val evenBottomLines: Int = 0,
) {
    val active: Boolean get() = oddTopLines > 0 || evenBottomLines > 0
}

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
    /** 摄像头开孔规避：奇数序页顶部/偶数序页底部按行减容，配合渲染偏移避开开孔。 */
    val avoidance: PageAvoidance = PageAvoidance(),
    /** 图片占位段落表：占位符（U+FFFC）偏移 → IMAGE span（含原始尺寸）；非图片书为空。 */
    private val images: Map<Long, com.llzx373.foldreader.core.format.TextSpan> = emptyMap(),
) {
    private val bounds = mutableListOf(0L)
    private val boundsLock = Any()
    @Volatile private var diskBoundsLoaded = false
    @Volatile private var fullBoundsFromDisk = false

    /**
     * 已成功落盘的边界条数。后台全书分页会周期性调用 [persistBounds]，
     * 有了它就能只追加新增尾部，而不是每 64 页都把整份数组重写一遍（O(n²) 写入）。
     * 0 = 磁盘上没有与本实例同源的前缀，下一次落盘必须整份写。
     */
    @Volatile private var persistedCount = 0

    /**
     * 临时分页起点（段首吸附后的锚点）。磁盘边界缺失且进度在书中部时，
     * 从这里开始向后排版先出第一屏，精确前缀边界由后台追上后整体切换。
     * 注意：从段首临时起排的页边界与从 0 精确起排的页边界一般不一致，
     * 因此播种分页器不落盘、不进入共享内存缓存。
     */
    var seedOrigin: Long = 0L
        private set
    private var indexBase: Int = 0
    val isSeeded: Boolean get() = seedOrigin > 0L

    /** 以 [origin]（必须是段首）为临时第 [pageIndexBase] 页起排。 */
    fun seed(origin: Long, pageIndexBase: Int) {
        require(origin > 0L)
        synchronized(boundsLock) {
            bounds.clear()
            bounds += origin
        }
        seedOrigin = origin
        indexBase = pageIndexBase.coerceAtLeast(0)
        diskBoundsLoaded = true
        fullBoundsFromDisk = false
    }

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
    /** 图片行高上限：约 60% 页可用高（等比缩放上限，与奇偶页减容无关保持稳定）。 */
    private val maxImageHeightPx = availHeightPx * 0.6f

    suspend fun pageAt(offset: Long): Page {
        ensureDiskBounds()
        val target = offset.coerceIn(seedOrigin, content.charCount)
        var page = getOrPaginate(boundAtOrBefore(target))
        while (page.charEnd <= target && page.charEnd < content.charCount) {
            val next = advance(page.charEnd)
            // 排不出内容的空页（charEnd 没前进）会让这里原地打转：实时索引还没推到该处时
            // 确实可能出现。到此为止交给调用方，不能把翻页/滚动卡死。
            if (next.charEnd <= page.charEnd) break
            page = next
        }
        return page
    }

    suspend fun pageBefore(offset: Long): Page? {
        if (offset <= 0L) return null
        // 播种分页器不知道起点之前的确切页（需从 0 重排），由调用方决定回退策略
        if (isSeeded && offset <= seedOrigin) return null
        val current = pageAt(offset)
        val target = current.charStart
        if (target <= seedOrigin) return null
        var page = getOrPaginate(boundAtOrBefore(target - 1))
        while (page.charEnd < target) {
            // 同 pageAt：空页不前进就停，避免死循环
            val next = advance(page.charEnd)
            if (next.charEnd <= page.charEnd) break
            page = next
        }
        return page
    }

    suspend fun pageAfter(offset: Long): Page? {
        val current = pageAt(offset)
        if (current.charEnd >= content.charCount) {
            if (content.isCharCountFinal) return null
            // 实时索引尚未推进到页尾之后：等索引增长/封口，封口后仍到顶才是真文末
            content.awaitCharsAbove(current.charEnd)
            if (current.charEnd >= content.charCount) return null
        }
        return pageAt(current.charEnd)
    }

    private fun boundAtOrBefore(target: Long): Long = synchronized(boundsLock) {
        var idx = bounds.binarySearch(target)
        idx = if (idx >= 0) idx else -idx - 2
        bounds[idx.coerceAtLeast(0)]
    }

    /** 已知的下一条页边界（严格大于 [offset]）；未知返回 null。用于跳过已落盘的前缀。 */
    fun knownBoundAfter(offset: Long): Long? = synchronized(boundsLock) {
        val idx = bounds.binarySearch(offset)
        bounds.getOrNull(if (idx >= 0) idx + 1 else -idx - 1)
    }

    /** 已知边界（含磁盘缓存）是否覆盖 [offset]：覆盖则 pageAt 只需排一两页。 */
    fun boundsCover(offset: Long): Boolean {
        ensureDiskBounds()
        return synchronized(boundsLock) { offset <= 0L || bounds.last() > offset }
    }

    /** 按版式几何估算 [offset] 大约在第几页（0-based），用于播种时的临时页码。 */
    fun estimatePageIndex(offset: Long): Int {
        val linesPerPage = (availHeightPx / lineHeightPx).toInt().coerceAtLeast(1)
        val charsPerLine = (textWidthPx / fontSizePx).coerceAtLeast(1f)
        // 段距/空行的经验折扣，宁低估不高估
        val charsPerPage = linesPerPage * charsPerLine * 0.92f
        return (offset.coerceAtLeast(0L) / charsPerPage).toInt()
    }

    /** [offset] 所在段落（或前一段落）的起点：向前找最后一个换行符的下一字符。 */
    suspend fun snapToParagraphStart(offset: Long): Long {
        val pos = offset.coerceIn(0L, content.charCount)
        if (pos == 0L) return 0L
        var end = pos
        while (end > 0L) {
            val start = maxOf(0L, end - SNAP_SCAN_CHARS)
            val window = content.read(start until end)
            val nl = window.lastIndexOf('\n')
            if (nl >= 0) return start + nl + 1
            end = start
        }
        return 0L
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
        val adopted = synchronized(boundsLock) {
            // 只有在内存边界还没长出来时才能采纳磁盘快照；采纳后内存前缀即等于磁盘内容，
            // 后续 append 才能安全地只写尾部。未采纳则 persistedCount 归零，下次整份写。
            if (bounds.size != 1) return@synchronized false
            bounds.clear()
            stored.forEach { bounds += it }
            fullBoundsFromDisk = true
            true
        }
        persistedCount = if (adopted) stored.size else 0
    }

    val hasFullBoundaryIndex: Boolean get() = diskBoundsLoaded && fullBoundsFromDisk

    /** offset 所在页的页序（0-based）：bounds 中小于等于 offset 的最后一条边界序号。 */
    fun pageIndexOf(offset: Long): Int = synchronized(boundsLock) {
        var idx = bounds.binarySearch(offset.coerceAtLeast(seedOrigin))
        idx = if (idx >= 0) idx else -idx - 2
        indexBase + idx.coerceAtLeast(0)
    }

    val boundaryPageCount: Int get() = synchronized(boundsLock) { indexBase + bounds.size }

    fun persistBounds() {
        if (isSeeded) return
        val key = diskKey ?: return
        val cache = diskCache ?: return
        val snapshot = synchronized(boundsLock) { bounds.toLongArray() }
        val already = persistedCount
        if (snapshot.size <= already) return
        val appended = already > 0 &&
            runCatching { cache.append(key, content.charCount, snapshot, already) }.getOrDefault(false)
        if (!appended) {
            runCatching { cache.save(key, content.charCount, snapshot) }
        }
        persistedCount = snapshot.size
    }

    private suspend fun getOrPaginate(start: Long): Page =
        cache.get(start) ?: paginateFrom(start).also { cache.put(start, it) }

    private suspend fun paginateFrom(start: Long): Page {
        // 摄像头开孔规避：奇数序页顶部 / 偶数序页底部按预留行数减容。
        // 页序以 bounds 中位置为准，边界从 0 顺序推进，奇偶对同一边界恒定
        val reserveLines = if (avoidance.active) {
            if (pageIndexOf(start) % 2 == 1) avoidance.oddTopLines else avoidance.evenBottomLines
        } else {
            0
        }
        val pageAvailHeightPx = if (reserveLines > 0) {
            (availHeightPx - reserveLines * lineHeightPx).coerceAtLeast(lineHeightPx)
        } else {
            availHeightPx
        }
        val producer = LineProducer(start)
        val lines = mutableListOf<PageLine>()
        var used = 0f
        while (true) {
            val line = producer.next() ?: break
            val extra = if (line.isParagraphStart && lines.isNotEmpty()) paragraphSpacingPx else 0f
            // 图片行按缩放后实际高度占用；文本行仍固定 lineHeightPx（纯文本路径逐像素不变）
            val lineH = line.heightPx ?: lineHeightPx
            if (lines.isNotEmpty() && used + extra + lineH > pageAvailHeightPx) {
                producer.pushBack(line)
                break
            }
            lines += line
            used += extra + lineH
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
            if (pos >= content.charCount) {
                // 实时索引的书 charCount 从 0 增长：未到终值时等索引推进，
                // 否则会把"索引还没建好"误判成文末，排出空页上屏
                if (content.isCharCountFinal) return
                content.awaitCharsAbove(pos)
                if (pos >= content.charCount) return
            }
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
                // 图片占位段落（单 U+FFFC 字符且在图片表中）：产出图片行，高度按缩放后实际值
                val imageSpan = if (body.length == 1 && body[0] == IMAGE_PLACEHOLDER_CHAR) {
                    images[pos]
                } else {
                    null
                }
                if (imageSpan != null) {
                    pending += PageLine(
                        charStart = pos,
                        charEnd = paraEnd,
                        text = body,
                        isParagraphStart = paragraphStart,
                        isParagraphEnd = true,
                        heightPx = imageLineHeightPx(
                            srcW = imageSpan.width,
                            srcH = imageSpan.height,
                            availWidthPx = textWidthPx,
                            maxHeightPx = maxImageHeightPx,
                            fallbackPx = lineHeightPx,
                        ),
                        imagePath = imageSpan.payload,
                        imageAlt = imageSpan.alt,
                    )
                } else {
                    loadTextLines(body, paraEnd, paragraphStart, paragraphEnd)
                }
            }
            pos = paraEnd
        }

        private suspend fun loadTextLines(
            body: String,
            paraEnd: Long,
            paragraphStart: Boolean,
            paragraphEnd: Boolean,
        ) {
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
    }

    private companion object {
        const val SCAN_CHARS = 4096L
        const val SNAP_SCAN_CHARS = 4096L
        /** 图片占位字符（U+FFFC，与压平规范 v3 的 img 占位块一致）。 */
        const val IMAGE_PLACEHOLDER_CHAR = '￼'
    }
}

/**
 * 图片行高：按可用宽等比缩放后的实际高度，上限 [maxHeightPx]（约 60% 页高）；
 * 原始尺寸未知（<=0）时退回一行文本高度（占位灰框）。
 */
internal fun imageLineHeightPx(
    srcW: Int,
    srcH: Int,
    availWidthPx: Float,
    maxHeightPx: Float,
    fallbackPx: Float,
): Float {
    if (srcW <= 0 || srcH <= 0) return fallbackPx
    val scaled = srcH * (availWidthPx / srcW)
    return scaled.coerceIn(1f, maxHeightPx)
}

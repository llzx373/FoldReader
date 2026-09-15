package com.llzx373.foldreader.feature.reader

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.llzx373.foldreader.AppContainer
import com.llzx373.foldreader.core.data.db.ReadingProgressEntity
import com.llzx373.foldreader.core.data.repository.BookPrefsRepository
import com.llzx373.foldreader.core.data.repository.BookshelfRepository
import com.llzx373.foldreader.core.data.settings.DualPageMode
import com.llzx373.foldreader.core.data.settings.PageTurnMode
import com.llzx373.foldreader.core.data.settings.ReadingPreferences
import com.llzx373.foldreader.core.data.settings.ReadingTheme
import com.llzx373.foldreader.core.format.BookContent
import com.llzx373.foldreader.core.format.BookParser
import com.llzx373.foldreader.core.format.Chapter
import com.llzx373.foldreader.core.format.EncodingDetector
import com.llzx373.foldreader.core.reader.CharsReadTracker
import com.llzx373.foldreader.core.reader.FontManager
import com.llzx373.foldreader.core.reader.LayoutConfig
import com.llzx373.foldreader.core.reader.Page
import com.llzx373.foldreader.core.reader.PageDiskCache
import com.llzx373.foldreader.core.reader.Paginator
import com.llzx373.foldreader.core.reader.PaginatorKey
import com.llzx373.foldreader.core.reader.PaginatorStore
import com.llzx373.foldreader.core.reader.StaticLayoutTextMeasurer
import com.llzx373.foldreader.core.reader.renderSpreadToBitmap
import com.llzx373.foldreader.core.reader.sessionFlushDelta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class PageSpread(
    val left: Page,
    val right: Page?,
)

/** spread 的分页几何指纹：UI 据此判断 spread 是否与当前屏幕版式一致（不一致 = 重分页进行中）。 */
data class SpreadGeometry(
    val dual: Boolean,
    val pageWidthPx: Int,
    val heightPx: Int,
)

data class ReaderUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val bookTitle: String = "",
    val totalChars: Long = 0,
    val spread: PageSpread? = null,
    val dualPage: Boolean = false,
    val chapterTitle: String = "",
    val chapterIndex: Int = 0,
    val chapterCount: Int = 0,
    val progressFraction: Float = 0f,
    val layoutConfig: LayoutConfig = LayoutConfig(),
    val scrollPages: List<Page> = emptyList(),
    val totalPages: Int = 0,
    val pageNumber: Int = 0,
    val inChapterFraction: Float = -1f,
    val spreadGeometry: SpreadGeometry? = null,
)

private data class SpreadViewport(
    val dual: Boolean,
    val leftWidthPx: Int,
    val rightWidthPx: Int,
    val heightPx: Int,
    val density: Float,
    val scaledDensity: Float,
    /** 摄像头开孔规避（UI 侧按 displayCutout 实算；双页翻页模式外恒为空）。 */
    val avoidance: com.llzx373.foldreader.core.reader.PageAvoidance =
        com.llzx373.foldreader.core.reader.PageAvoidance(),
)

@OptIn(FlowPreview::class)
class ReaderViewModel(
    private val bookId: Long,
    private val bookshelfRepository: BookshelfRepository,
    private val bookPrefsRepository: BookPrefsRepository,
    private val parser: BookParser,
    private val fontManager: FontManager,
    private val pageDiskCache: PageDiskCache,
    private val initialAnchor: Long = -1L,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    // stateIn 初值是默认偏好，真正的每书偏好异步到达；分页与 viewport 必须等首次真实值，
    // 否则进书会先按默认偏好（AUTO）排版再跳变（单页闪成双页或反之）。
    private val _preferencesLoaded = MutableStateFlow(false)
    val preferencesLoaded: StateFlow<Boolean> = _preferencesLoaded.asStateFlow()

    val preferences: StateFlow<ReadingPreferences> = bookPrefsRepository.observe(bookId)
        .onEach { _preferencesLoaded.value = true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReadingPreferences())

    private var content: BookContent? = null
    private var chapters: List<Chapter> = emptyList()
    private var appliedEncoding: String? = null
    private var pendingReopenAnchor = -1L
    private var baseReadingMillis = 0L
    private var firstReadAtMs = 0L
    private var charsReadBase = 0L
    private val charsReadTracker = CharsReadTracker()
    private var lastSessionFlushTotalMs = 0L
    private var paginatorLeft: Paginator? = null
    private var dualActive = false
    // 当前分页器产出版式：showSpread 落账时用它们盖章（而不是 uiState 现值），
    // 避免重分页竞态把"旧版式 spread + 新版式指纹"发布出去
    private var activeConfig: LayoutConfig? = null
    private var activeGeom: SpreadGeometry? = null
    private val paginatorStore = PaginatorStore()
    private val pageMutex = Mutex()
    private val anchorOffset = MutableStateFlow(0L)
    private val viewport = MutableStateFlow<SpreadViewport?>(null)
    private val pendingSave = MutableStateFlow<Long?>(null)
    private val timer = ReadingTimer()
    private val speedTracker = ReadingSpeedTracker()
    private val autoPageClock = AutoPageClock()
    private val autoPageUiPaused = MutableStateFlow(false)

    private val _autoPageStatus = MutableStateFlow(AutoPageStatus())
    val autoPageStatus: StateFlow<AutoPageStatus> = _autoPageStatus.asStateFlow()

    val autoScrollTicks = kotlinx.coroutines.flow.MutableSharedFlow<Float>(extraBufferCapacity = 8)

    private val autoPageTurnRequests = AutoPageTurnRequests()
    val autoPageTurns: kotlinx.coroutines.flow.SharedFlow<Boolean> = autoPageTurnRequests.requests

    private val _prevSpread = MutableStateFlow<PageSpread?>(null)
    val prevSpread: StateFlow<PageSpread?> = _prevSpread.asStateFlow()
    private val _nextSpread = MutableStateFlow<PageSpread?>(null)
    val nextSpread: StateFlow<PageSpread?> = _nextSpread.asStateFlow()

    private val curlBitmapCache = SpreadBitmapCache<Bitmap>()

    @Volatile
    private var curlContext: CurlRenderContext? = null

    /** UI 侧供给渲染上下文（几何/主题/抓取时刻文本）；更新后按当前对页重建预生成。 */
    fun setCurlRenderContext(ctx: CurlRenderContext?) {
        curlContext = ctx
        if (ctx != null) _uiState.value.spread?.let { schedulePrefetch(it) }
    }

    fun curlBitmap(spread: PageSpread): Bitmap? {
        val ctx = curlContext ?: return null
        return curlBitmapCache.get(ctx.keyFor(spread, _uiState.value.layoutConfig))
    }

    /** 现场渲染（缓存命中直接返回）；渲染失败返回 null，调用方降级。 */
    suspend fun renderCurlBitmap(spread: PageSpread): Bitmap? {
        val ctx = curlContext ?: return null
        val key = ctx.keyFor(spread, _uiState.value.layoutConfig)
        curlBitmapCache.get(key)?.let { return it }
        val bitmap = withContext(Dispatchers.Default) {
            runCatching { renderCurlBitmapWith(ctx, spread) }.getOrNull()
        } ?: return null
        if (curlContext === ctx) curlBitmapCache.put(key, bitmap)
        return bitmap
    }

    private fun renderCurlBitmapWith(ctx: CurlRenderContext, spread: PageSpread): Bitmap {
        val (leftHighlights, rightHighlights) = ctx.highlights(spread)
        return renderSpreadToBitmap(
            spread = spread,
            config = _uiState.value.layoutConfig,
            colors = ctx.colors,
            geom = ctx.geom,
            leftHighlights = leftHighlights,
            rightHighlights = rightHighlights,
            density = ctx.density,
            scaledDensity = ctx.scaledDensity,
            widthPx = ctx.widthPx,
            heightPx = ctx.heightPx,
        )
    }

    /** 闲时预生成对页位图；渲染期间上下文被替换则结果丢弃（键内容寻址，过期条目无害）。 */
    private suspend fun pregenCurlBitmaps(spreads: List<PageSpread>) {
        val ctx = curlContext ?: return
        withContext(Dispatchers.Default) {
            spreads.forEach { spread ->
                val key = ctx.keyFor(spread, _uiState.value.layoutConfig)
                if (curlBitmapCache.get(key) != null) return@forEach
                val bitmap = runCatching { renderCurlBitmapWith(ctx, spread) }.getOrNull()
                    ?: return@forEach
                if (curlContext === ctx) curlBitmapCache.put(key, bitmap)
            }
        }
    }

    val bookmarks: StateFlow<List<com.llzx373.foldreader.core.data.db.BookmarkEntity>> =
        bookshelfRepository.observeBookmarks(bookId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val annotations: StateFlow<List<com.llzx373.foldreader.core.data.db.AnnotationEntity>> =
        bookshelfRepository.observeAnnotations(bookId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 快照校对不通过的标注 id（打开书时抽样校验，标记"可能错位"，不删）。 */
    private val _shiftedAnnotationIds = MutableStateFlow<Set<Long>>(emptySet())
    val shiftedAnnotationIds: StateFlow<Set<Long>> = _shiftedAnnotationIds.asStateFlow()

    data class SearchState(
        val query: String = "",
        val running: Boolean = false,
        val scannedChars: Long = 0,
        val totalChars: Long = 0,
        val hits: List<com.llzx373.foldreader.core.format.SearchHit> = emptyList(),
    )

    private val _searchState = MutableStateFlow(SearchState())
    val searchState: StateFlow<SearchState> = _searchState.asStateFlow()
    private var searchJob: kotlinx.coroutines.Job? = null

    /** 命中后正文高亮区间（[起始, 结束 Exclusive)），几秒后由 UI 淡出清除。 */
    private val _searchHighlight = MutableStateFlow<Pair<Long, Long>?>(null)
    val searchHighlight: StateFlow<Pair<Long, Long>?> = _searchHighlight.asStateFlow()

    fun startSearch(query: String) {
        searchJob?.cancel()
        val source = content
        if (source == null || query.isBlank()) {
            _searchState.value = SearchState()
            return
        }
        _searchState.value = SearchState(
            query = query,
            running = true,
            totalChars = source.charCount,
        )
        searchJob = viewModelScope.launch {
            com.llzx373.foldreader.core.format.searchContent(
                content = source,
                query = query,
                onHit = { hit ->
                    _searchState.update { it.copy(hits = it.hits + hit) }
                },
                onProgress = { scanned ->
                    _searchState.update { it.copy(scannedChars = scanned) }
                },
            )
            _searchState.update { it.copy(running = false) }
        }
    }

    fun cancelSearch() {
        searchJob?.cancel()
        searchJob = null
        _searchState.update { it.copy(running = false) }
    }

    fun setSearchHighlight(start: Long, endExclusive: Long) {
        _searchHighlight.value = start to endExclusive
    }

    fun clearSearchHighlight() {
        _searchHighlight.value = null
    }

    private suspend fun verifyAnnotationSnapshots() {
        val source = content ?: return
        val anns = withContext(Dispatchers.IO) {
            bookshelfRepository.observeAnnotations(bookId).first()
        }
        val shifted = mutableSetOf<Long>()
        anns.forEach { ann ->
            val range = snapshotVerifyRange(
                start = ann.startCharOffset,
                end = ann.endCharOffset,
                snapshotLength = ann.selectedText.length,
                totalChars = source.charCount,
            )
            val actual = if (range == null) {
                ""
            } else {
                runCatching { source.read(range) }.getOrNull()
            }
            if (actual == null || actual != ann.selectedText) shifted += ann.id
        }
        _shiftedAnnotationIds.value = shifted
    }

    suspend fun selectedTextOf(start: Long, end: Long): String {
        val source = content ?: return ""
        val safeEnd = minOf(end, source.charCount)
        if (safeEnd <= start) return ""
        return withContext(Dispatchers.IO) {
            runCatching { source.read(start until safeEnd) }.getOrDefault("")
        }
    }

    private suspend fun snapshotFor(start: Long, end: Long): String {
        val source = content ?: return ""
        return withContext(Dispatchers.IO) {
            val (range, truncated) = annotationSnapshotRange(start, end, source.charCount)
                ?: return@withContext ""
            if (truncated) {
                android.util.Log.d(
                    "ReaderAnnotation",
                    "annotation snapshot truncated at $ANNOTATION_SNAPSHOT_MAX_CHARS chars",
                )
            }
            runCatching { source.read(range) }.getOrDefault("")
        }
    }

    fun addAnnotation(
        start: Long,
        end: Long,
        color: Long,
        note: String?,
        style: String = com.llzx373.foldreader.core.data.db.AnnotationEntity.STYLE_HIGHLIGHT,
    ) {
        if (end <= start) return
        viewModelScope.launch {
            val snapshot = snapshotFor(start, end)
            bookshelfRepository.addAnnotation(
                com.llzx373.foldreader.core.data.db.AnnotationEntity(
                    bookId = bookId,
                    startCharOffset = start,
                    endCharOffset = end,
                    selectedText = snapshot,
                    color = color,
                    note = note?.trim()?.takeIf { it.isNotEmpty() },
                    style = style,
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    fun updateAnnotation(
        annotation: com.llzx373.foldreader.core.data.db.AnnotationEntity,
        color: Long,
        note: String?,
        style: String = annotation.style,
    ) {
        viewModelScope.launch {
            bookshelfRepository.updateAnnotation(
                annotation.copy(
                    color = color,
                    note = note?.trim()?.takeIf { it.isNotEmpty() },
                    style = style,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    fun deleteAnnotation(id: Long) {
        viewModelScope.launch { bookshelfRepository.deleteAnnotation(id) }
    }

    /**
     * 焦点页书签 toggle：双页下 [leftPage] 选择左/右页（锚点取该页首字符），
     * 同锚点已有书签则删除，否则新增（快照取页首若干字符）；同页不同偏移可共存多条。
     */
    fun toggleBookmark(leftPage: Boolean) {
        val spread = _uiState.value.spread ?: return
        val page = if (leftPage) spread.left else spread.right ?: spread.left
        if (page.isEmpty) return
        val anchor = page.charStart
        viewModelScope.launch {
            val existing = findBookmarkAt(bookmarks.value, anchor)
            if (existing != null) {
                bookshelfRepository.deleteBookmark(existing.id)
            } else {
                val excerpt = runCatching {
                    content?.read(anchor until minOf(page.charEnd, anchor + 48)) ?: ""
                }.getOrDefault("")
                bookshelfRepository.addBookmark(
                    com.llzx373.foldreader.core.data.db.BookmarkEntity(
                        bookId = bookId,
                        charOffset = anchor,
                        chapterIndex = chapterIndexAt(chapters, anchor),
                        snapshotText = bookmarkSnapshotOf(excerpt),
                        createdAt = System.currentTimeMillis(),
                    ),
                )
            }
        }
    }

    /** 任意位置书签 toggle：锚点 = [start] 精确字符偏移，快照取 [start, end) 文本截断。 */
    fun toggleBookmarkAt(start: Long, end: Long) {
        if (start < 0L) return
        viewModelScope.launch {
            val existing = findBookmarkAt(bookmarks.value, start)
            if (existing != null) {
                bookshelfRepository.deleteBookmark(existing.id)
            } else {
                val excerpt = selectedTextOf(start, minOf(end, start + 48))
                bookshelfRepository.addBookmark(
                    com.llzx373.foldreader.core.data.db.BookmarkEntity(
                        bookId = bookId,
                        charOffset = start,
                        chapterIndex = chapterIndexAt(chapters, start),
                        snapshotText = bookmarkSnapshotOf(excerpt),
                        createdAt = System.currentTimeMillis(),
                    ),
                )
            }
        }
    }

    fun renameBookmark(bookmark: com.llzx373.foldreader.core.data.db.BookmarkEntity, label: String) {
        viewModelScope.launch { bookshelfRepository.renameBookmark(bookmark.copy(label = label.trim())) }
    }

    fun deleteBookmark(id: Long) {
        viewModelScope.launch { bookshelfRepository.deleteBookmark(id) }
    }

    init {
        openBook()
        viewModelScope.launch {
            pendingSave.filterNotNull().debounce(500L).collect { offset ->
                runCatching { persistProgress(offset) }
            }
        }
        viewModelScope.launch {
            bookshelfRepository.observeBook(bookId)
                .map { it?.encoding.orEmpty() }
                .distinctUntilChanged()
                .collect { encoding ->
                    val applied = appliedEncoding
                    if (applied != null && encoding != applied) reopenWithEncoding()
                }
        }
        viewModelScope.launch { autoPageLoop() }
    }

    private fun reopenWithEncoding() {
        pendingReopenAnchor = anchorOffset.value
        runCatching { (content as? java.io.Closeable)?.close() }
        content = null
        paginatorStore.remove(bookId)
        curlBitmapCache.clear()
        viewModelScope.launch {
            pageMutex.withLock { paginatorLeft = null }
            runCatching { bookshelfRepository.saveChapters(bookId, emptyList()) }
            openBook()
        }
    }

    fun retry() {
        if (_uiState.value.loading) return
        openBook()
    }

    private fun openBook() {
        _uiState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val book = bookshelfRepository.getBook(bookId)
            if (book == null) {
                _uiState.update { it.copy(loading = false, error = "书籍不存在") }
                return@launch
            }
            runCatching { bookPrefsRepository.ensureInitialized(bookId) }
            try {
                val uri = Uri.parse(book.fileUri)
                val charsetOverride = EncodingDetector.forNameOrNull(book.encoding)
                val opened = withContext(Dispatchers.IO) {
                    val stored = bookshelfRepository.getChapters(bookId)
                    val resolved = stored.ifEmpty {
                        parser.parseChapters(uri, charsetOverride).also { scanned ->
                            runCatching { bookshelfRepository.saveChapters(bookId, scanned) }
                        }
                    }
                    parser.openContent(uri, charsetOverride) to resolved
                }
                content = opened.first
                chapters = opened.second
                appliedEncoding = book.encoding
                verifyAnnotationSnapshots()
                val progress = bookshelfRepository.getProgress(bookId)
                baseReadingMillis = progress?.totalReadingMillis ?: 0L
                firstReadAtMs = progress?.firstReadAt ?: 0L
                charsReadBase = progress?.charsReadTotal ?: 0L
                anchorOffset.value = when {
                    pendingReopenAnchor >= 0L ->
                        pendingReopenAnchor.coerceAtMost(opened.first.charCount).also {
                            pendingReopenAnchor = -1L
                        }
                    initialAnchor >= 0L -> initialAnchor.coerceAtMost(opened.first.charCount)
                    else -> progress?.charOffset ?: 0L
                }
                charsReadTracker.jump(anchorOffset.value)
                _uiState.update {
                    it.copy(bookTitle = book.title, totalChars = opened.first.charCount)
                }
                collectViewport()
            } catch (t: Throwable) {
                _uiState.update { it.copy(loading = false, error = t.message ?: "打开失败") }
            }
        }
    }

    private suspend fun autoPageLoop() {
        var lastTick = System.nanoTime()
        while (true) {
            val p = preferences.value
            val now = System.currentTimeMillis()
            val uiPaused = autoPageUiPaused.value
            val manualPaused = autoPageClock.isManualPaused(now)
            _autoPageStatus.value = AutoPageStatus(
                enabled = p.autoPageEnabled,
                paused = p.autoPageEnabled && (uiPaused || manualPaused),
            )
            if (!autoPageClock.shouldRun(p.autoPageEnabled, uiPaused, now)) {
                lastTick = System.nanoTime()
                delay(300L)
                continue
            }
            when (p.autoPageMode) {
                com.llzx373.foldreader.core.data.settings.AutoPageMode.INTERVAL -> {
                    delay(p.autoPageIntervalSec.coerceIn(3, 30) * 1000L)
                    val recheck = System.currentTimeMillis()
                    if (autoPageClock.shouldRun(
                            preferences.value.autoPageEnabled, autoPageUiPaused.value, recheck,
                        )
                    ) {
                        // 到点发翻页请求，由 UI 走正常翻页动画（含字符计数）
                        autoPageTurnRequests.request(forward = true)
                    }
                }
                com.llzx373.foldreader.core.data.settings.AutoPageMode.SCROLL -> {
                    delay(16L)
                    val t = System.nanoTime()
                    val dt = ((t - lastTick) / 1_000_000_000.0).toFloat().coerceAtMost(0.1f)
                    lastTick = t
                    autoScrollTicks.tryEmit(p.autoPageSpeedPx * dt)
                }
            }
        }
    }

    fun noteManualInteraction() {
        autoPageClock.noteManualInteraction(System.currentTimeMillis())
    }

    fun setAutoPageUiPaused(paused: Boolean) {
        autoPageUiPaused.value = paused
    }

    fun setAutoPageMode(mode: com.llzx373.foldreader.core.data.settings.AutoPageMode) {
        viewModelScope.launch {
            bookPrefsRepository.update(bookId) { it.copy(autoPageMode = mode.name) }
        }
    }

    fun setAutoPageIntervalSec(seconds: Int) {
        viewModelScope.launch {
            bookPrefsRepository.update(bookId) { it.copy(autoPageIntervalSec = seconds.coerceIn(3, 30)) }
        }
    }

    fun setAutoPageSpeedPx(pxPerSecond: Float) {
        viewModelScope.launch {
            bookPrefsRepository.update(bookId) { it.copy(autoPageSpeedPx = pxPerSecond.coerceIn(10f, 300f)) }
        }
    }

    private fun buildProgress(offset: Long, nowMs: Long) = ReadingProgressEntity(
        bookId = bookId,
        charOffset = offset,
        chapterIndex = chapterIndexAt(chapters, offset),
        totalReadingMillis = baseReadingMillis + timer.totalMs(nowMs),
        firstReadAt = if (firstReadAtMs > 0L) firstReadAtMs else nowMs,
        charsReadTotal = charsReadBase + charsReadTracker.total,
        updatedAt = nowMs,
    )

    private suspend fun persistProgress(offset: Long) {
        val nowMs = System.currentTimeMillis()
        bookshelfRepository.saveProgress(buildProgress(offset, nowMs))
        bookshelfRepository.touchLastRead(bookId)
        flushReadingSession(nowMs)
    }

    /** 阅读时长按天分桶：本次打开累计的增量 upsert 到当天。 */
    private suspend fun flushReadingSession(nowMs: Long) {
        val total = timer.totalMs(nowMs)
        val delta = sessionFlushDelta(total, lastSessionFlushTotalMs)
        if (delta <= 0L) return
        lastSessionFlushTotalMs = total
        runCatching {
            bookshelfRepository.addReadingSession(
                bookId,
                com.llzx373.foldreader.core.reader.dayStartMs(nowMs, java.time.ZoneId.systemDefault()),
                delta,
            )
        }
    }

    /** 退到后台（ON_STOP）时立即落库一次，避免长停留不翻页丢整段时长。 */
    fun flushReadingSessionNow() {
        val nowMs = System.currentTimeMillis()
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { flushReadingSession(nowMs) }
        }
    }

    private suspend fun collectViewport() {
        combine(
            viewport.filterNotNull(),
            preferencesLoaded.filter { it },
            preferences
                .distinctUntilChanged { a, b ->
                    a.fontSizeSp == b.fontSizeSp &&
                        a.lineSpacingMultiplier == b.lineSpacingMultiplier &&
                        a.marginLevel == b.marginLevel &&
                        a.maxLineChars == b.maxLineChars &&
                        a.paragraphSpacingEm == b.paragraphSpacingEm &&
                        a.letterSpacingEm == b.letterSpacingEm &&
                        a.fontKey == b.fontKey &&
                        a.autoIndentEnabled == b.autoIndentEnabled
                },
        ) { v, _, p -> v to p }.collectLatest { (v, p) ->
            val source = content ?: return@collectLatest
            try {
                val config = buildLayoutConfig(p)
                val pageWidthPx = if (v.dual) {
                    dualPageWidthPx(v.leftWidthPx, v.rightWidthPx)
                } else {
                    v.leftWidthPx
                }
                // 重分页开始：旧分页的预取目标全部作废，防止被 turn() 消费后污染 uiState
                _prevSpread.value = null
                _nextSpread.value = null
                val wasScrolling = _uiState.value.scrollPages.isNotEmpty()
                val paginator = buildPaginator(
                    source, config, pageWidthPx, v.heightPx, v.density, v.scaledDensity, v.avoidance,
                )
                val t0 = System.nanoTime()
                val spread = withContext(Dispatchers.Default) {
                    spreadFrom(paginator, v.dual, anchorOffset.value)
                }
                logPaginateTiming(t0)
                val geom = SpreadGeometry(v.dual, pageWidthPx, v.heightPx)
                pageMutex.withLock {
                    paginatorLeft = paginator
                    dualActive = v.dual
                    activeConfig = config
                    activeGeom = geom
                }
                publish(spread, config, v.dual, geom)
                // 滚动模式：版式变化后按当前锚点用新分页器重建滚动页流
                if (wasScrolling) enterScrollMode()
                buildFullBounds(paginator)
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (t: Throwable) {
                android.util.Log.w("ReaderViewModel", "repaginate failed, keep previous spread", t)
            }
        }
    }

    private fun buildLayoutConfig(p: ReadingPreferences): LayoutConfig {
        val (marginH, marginV) = marginDpFor(p.marginLevel)
        return LayoutConfig(
            fontSizeSp = p.fontSizeSp,
            lineSpacingMultiplier = p.lineSpacingMultiplier,
            letterSpacingEm = p.letterSpacingEm,
            paragraphSpacingEm = p.paragraphSpacingEm,
            maxLineChars = p.maxLineChars,
            autoIndentEnabled = p.autoIndentEnabled,
            marginLeftDp = marginH,
            marginRightDp = marginH,
            marginTopDp = marginV,
            marginBottomDp = marginV,
            fontKey = p.fontKey,
            typeface = fontManager.resolve(p.fontKey),
        )
    }

    private fun buildPaginator(
        source: BookContent,
        config: LayoutConfig,
        widthPx: Int,
        heightPx: Int,
        density: Float,
        scaledDensity: Float,
        avoidance: com.llzx373.foldreader.core.reader.PageAvoidance =
            com.llzx373.foldreader.core.reader.PageAvoidance(),
    ): Paginator {
        val capped = config.copy(
            maxLineChars = capMaxLineChars(
                userMaxLineChars = config.maxLineChars,
                pageWidthPx = widthPx.toFloat(),
                horizontalMarginsPx = (config.marginLeftDp + config.marginRightDp) * density,
                fontSizePx = config.fontSizeSp * scaledDensity,
            ),
        )
        val key = PaginatorKey(bookId, widthPx, heightPx, density, scaledDensity, capped, avoidance)
        return Paginator(
            content = source,
            config = capped,
            measurer = StaticLayoutTextMeasurer(),
            widthPx = widthPx,
            heightPx = heightPx,
            density = density,
            scaledDensity = scaledDensity,
            cache = paginatorStore.getOrCreate(key),
            diskCache = pageDiskCache,
            diskKey = key,
            avoidance = avoidance,
        )
    }

    /** 闲时把全书分页一遍：页边界落盘，并向 UI 汇报总页数（随 collectLatest 取消）。 */
    private suspend fun buildFullBounds(paginator: Paginator) {
        val total = content?.charCount ?: return
        if (total <= 0L) return
        withContext(Dispatchers.Default) {
            if (paginator.hasFullBoundaryIndex) {
                _uiState.update { it.copy(totalPages = paginator.boundaryPageCount) }
            } else {
                val count = paginateToEnd(paginator, total) { done ->
                    _uiState.update { it.copy(totalPages = done) }
                }
                _uiState.update { it.copy(totalPages = count) }
                withContext(Dispatchers.IO) { paginator.persistBounds() }
            }
        }
    }

    private suspend fun paginateToEnd(paginator: Paginator, total: Long, onCount: (Int) -> Unit): Int {
        var page = paginator.pageAt(0)
        var count = 1
        while (page.charEnd < total) {
            kotlinx.coroutines.yield()
            page = paginator.pageAt(page.charEnd)
            count++
            if (count % 16 == 0) {
                onCount(count)
                delay(10L)
            }
        }
        return count
    }

    private suspend fun spreadFrom(
        paginator: Paginator,
        dual: Boolean,
        anchor: Long,
    ): PageSpread {
        var leftPage = paginator.pageAt(anchor)
        // 摄像头规避开启时页容量按奇偶不对称：跨页左页必须落在偶数序页上，
        // 否则左右页的预留会互换（跳转、进度恢复等任意锚点都可能落在奇数页）
        if (dual && paginator.avoidance.active && paginator.pageIndexOf(leftPage.charStart) % 2 == 1) {
            paginator.pageBefore(leftPage.charStart)?.let { leftPage = it }
        }
        val total = content?.charCount ?: 0L
        val rightPage = if (dual && leftPage.charEnd < total) {
            paginator.pageAt(leftPage.charEnd).takeIf { it.charEnd > it.charStart }
        } else {
            null
        }
        return PageSpread(leftPage, rightPage)
    }

    private suspend fun currentSpreadFrom(anchor: Long): PageSpread? {
        val (paginator, dual) = pageMutex.withLock { paginatorLeft to dualActive }
        val p = paginator ?: return null
        return withContext(Dispatchers.Default) { spreadFrom(p, dual, anchor) }
    }

    fun setViewports(
        dual: Boolean,
        leftWidthPx: Int,
        rightWidthPx: Int,
        heightPx: Int,
        density: Float,
        scaledDensity: Float,
        avoidance: com.llzx373.foldreader.core.reader.PageAvoidance =
            com.llzx373.foldreader.core.reader.PageAvoidance(),
    ) {
        if (leftWidthPx <= 0 || heightPx <= 0 || (dual && rightWidthPx <= 0)) {
            android.util.Log.w(
                "ReaderViewModel",
                "ignore invalid viewport: dual=$dual left=$leftWidthPx right=$rightWidthPx height=$heightPx",
            )
            return
        }
        viewport.value = SpreadViewport(
            dual, leftWidthPx, rightWidthPx, heightPx, density, scaledDensity, avoidance,
        )
    }

    suspend fun adjacentSpread(forward: Boolean): PageSpread? {
        val current = _uiState.value.spread ?: return null
        val left = pageMutex.withLock { paginatorLeft } ?: return null
        val dual = pageMutex.withLock { dualActive }
        val total = _uiState.value.totalChars
        val anchor = when {
            forward && dual -> current.right?.charEnd ?: return null
            forward -> if (current.left.charEnd < total) current.left.charEnd else return null
            else -> {
                val one = withContext(Dispatchers.Default) {
                    left.pageBefore(current.left.charStart)
                } ?: return null
                val two = withContext(Dispatchers.Default) { left.pageBefore(one.charStart) }
                if (dual) two?.charStart ?: one.charStart else one.charStart
            }
        }
        return currentSpreadFrom(anchor)
    }

    /** [countCharsRead] = true 表示翻页推进（累计已读字符）；跳转类调用传 false（只重置基线）。 */
    fun showSpread(spread: PageSpread, countCharsRead: Boolean = false) {
        if (countCharsRead) {
            charsReadTracker.advance(spread.left.charStart)
        } else {
            charsReadTracker.jump(spread.left.charStart)
        }
        anchorOffset.value = spread.left.charStart
        trackSpeed(spread.left.charStart)
        publish(
            spread,
            activeConfig ?: _uiState.value.layoutConfig,
            activeGeom?.dual ?: _uiState.value.dualPage,
            activeGeom,
        )
        pendingSave.value = spread.left.charStart
    }

    suspend fun seekToFraction(fraction: Float) {
        val total = _uiState.value.totalChars
        if (total <= 0L) return
        val target = (total * fraction.coerceIn(0f, 1f)).toLong()
        seekToOffset(target)
    }

    suspend fun seekToOffset(offset: Long) {
        currentSpreadFrom(offset)?.let { showSpread(it) }
    }

    suspend fun relocate() {
        seekToOffset(anchorOffset.value)
    }

    fun enterScrollMode() {
        val current = _uiState.value.spread ?: return
        charsReadTracker.jump(current.left.charStart)
        _uiState.update { it.copy(scrollPages = listOf(current.left)) }
    }

    suspend fun scrollExtend(forward: Boolean) {
        val pages = _uiState.value.scrollPages
        if (pages.isEmpty()) return
        val p = pageMutex.withLock { paginatorLeft } ?: return
        val next = withContext(Dispatchers.Default) {
            if (forward) p.pageAfter(pages.last().charStart) else p.pageBefore(pages.first().charStart)
        } ?: return
        _uiState.update {
            it.copy(scrollPages = if (forward) it.scrollPages + next else listOf(next) + it.scrollPages)
        }
    }

    fun scrollAnchorTo(charStart: Long) {
        anchorOffset.value = charStart
        charsReadTracker.advance(charStart)
        trackSpeed(charStart)
        pendingSave.value = charStart
        _uiState.update {
            it.copy(
                progressFraction = progressPercentOf(charStart, it.totalChars),
                chapterTitle = chapters.getOrNull(chapterIndexAt(chapters, charStart))?.title.orEmpty(),
                chapterIndex = chapterIndexAt(chapters, charStart),
                chapterCount = chapters.size,
                pageNumber = (paginatorLeft?.pageIndexOf(charStart) ?: -1) + 1,
                inChapterFraction = inChapterFraction(chapters, chapterIndexAt(chapters, charStart), charStart),
            )
        }
    }

    suspend fun chapterAt(index: Int): Chapter? = chapters.getOrNull(index)

    suspend fun seekChapter(delta: Int) {
        if (chapters.isEmpty()) return
        val idx = (_uiState.value.chapterIndex + delta).coerceIn(0, chapters.lastIndex)
        chapters.getOrNull(idx)?.let { seekToOffset(it.charStart) }
    }

    fun chapterList(): List<Chapter> = chapters

    fun setPageTurnMode(mode: PageTurnMode) {
        viewModelScope.launch {
            bookPrefsRepository.update(bookId) {
                it.copy(
                    pageTurnMode = mode.name,
                    pageTurnModeExplicit = true,
                    simulationDegraded = if (mode == PageTurnMode.SIMULATION) {
                        false
                    } else {
                        it.simulationDegraded
                    },
                )
            }
        }
    }

    fun setSimulationDegraded(degraded: Boolean) {
        viewModelScope.launch {
            bookPrefsRepository.update(bookId) { it.copy(simulationDegraded = degraded) }
        }
    }

    /** 系统内存紧张（onTrimMemory）时清空翻页位图缓存，后续翻页现场重渲染。 */
    fun clearCurlBitmaps() = curlBitmapCache.clear()

    fun setDualPageMode(mode: DualPageMode) {
        viewModelScope.launch {
            bookPrefsRepository.update(bookId) { it.copy(dualPageMode = mode.name) }
        }
    }

    fun setReaderBrightness(brightness: Float) {
        viewModelScope.launch {
            bookPrefsRepository.update(bookId) {
                it.copy(readerBrightness = brightness.coerceIn(-1f, 1f))
            }
        }
    }

    fun setAutoPageEnabled(enabled: Boolean) {
        viewModelScope.launch {
            bookPrefsRepository.update(bookId) { it.copy(autoPageEnabled = enabled) }
        }
    }

    fun setAutoIndentEnabled(enabled: Boolean) {
        viewModelScope.launch {
            bookPrefsRepository.update(bookId) { it.copy(autoIndentEnabled = enabled) }
        }
    }

    fun setPanelScreenOff(enabled: Boolean) {
        viewModelScope.launch {
            bookPrefsRepository.update(bookId) { it.copy(panelScreenOff = enabled) }
        }
    }

    fun setFontSize(sizeSp: Float) {
        viewModelScope.launch {
            bookPrefsRepository.update(bookId) { it.copy(fontSizeSp = sizeSp) }
        }
    }

    fun setLineSpacing(multiplier: Float) {
        viewModelScope.launch {
            bookPrefsRepository.update(bookId) { it.copy(lineSpacingMultiplier = multiplier) }
        }
    }

    fun setMarginLevel(level: Int) {
        viewModelScope.launch {
            bookPrefsRepository.update(bookId) { it.copy(marginLevel = level.coerceIn(0, 2)) }
        }
    }

    fun setMaxLineChars(chars: Int) {
        viewModelScope.launch {
            bookPrefsRepository.update(bookId) { it.copy(maxLineChars = chars.coerceIn(18, 40)) }
        }
    }

    fun setParagraphSpacingEm(spacingEm: Float) {
        viewModelScope.launch {
            bookPrefsRepository.update(bookId) {
                it.copy(paragraphSpacingEm = spacingEm.coerceIn(0f, 2f))
            }
        }
    }

    fun setLetterSpacingEm(spacingEm: Float) {
        viewModelScope.launch {
            bookPrefsRepository.update(bookId) {
                it.copy(letterSpacingEm = spacingEm.coerceIn(0f, 0.5f))
            }
        }
    }

    fun setTheme(theme: ReadingTheme) {
        viewModelScope.launch {
            bookPrefsRepository.update(bookId) { it.copy(themeId = theme.name) }
        }
    }

    fun setCustomColors(backgroundArgb: Int?, textArgb: Int?) {
        viewModelScope.launch {
            bookPrefsRepository.update(bookId) {
                it.copy(customBackgroundArgb = backgroundArgb, customTextArgb = textArgb)
            }
        }
    }

    fun setReadingActive(active: Boolean) {
        val now = System.currentTimeMillis()
        if (active) timer.start(now) else timer.stop(now)
    }

    fun remainingTimeText(): String? {
        val total = _uiState.value.totalChars
        if (total <= 0L) return null
        val minutes = speedTracker.remainingMinutes(total, anchorOffset.value) ?: return null
        if (minutes <= 0.0 || minutes > 100_000.0) return null
        return formatRemainingTime(minutes)
    }

    private fun trackSpeed(offset: Long) {
        if (timer.isRunning) speedTracker.feed(offset, System.currentTimeMillis())
    }

    private fun publish(spread: PageSpread, config: LayoutConfig, dual: Boolean, geom: SpreadGeometry?) {
        _uiState.update {
            it.copy(
                loading = false,
                error = null,
                spread = spread,
                dualPage = dual,
                layoutConfig = config,
                spreadGeometry = geom,
                progressFraction = progressPercentOf(spread.left.charStart, it.totalChars),
                chapterTitle = chapters.getOrNull(chapterIndexAt(chapters, spread.left.charStart))?.title.orEmpty(),
                chapterIndex = chapterIndexAt(chapters, spread.left.charStart),
                chapterCount = chapters.size,
                pageNumber = (paginatorLeft?.pageIndexOf(spread.left.charStart) ?: -1) + 1,
                inChapterFraction = inChapterFraction(
                    chapters, chapterIndexAt(chapters, spread.left.charStart), spread.left.charStart,
                ),
            )
        }
        schedulePrefetch(spread)
    }

    private var paginateCount = 0
    private var paginateTotalMs = 0L

    private fun logPaginateTiming(t0Nanos: Long) {
        if (!com.llzx373.foldreader.BuildConfig.DEBUG) return
        val ms = (System.nanoTime() - t0Nanos) / 1_000_000L
        paginateCount++
        paginateTotalMs += ms
        android.util.Log.d(
            "ReaderPerf",
            "spread paginated in ${ms}ms (avg ${paginateTotalMs / paginateCount}ms, n=$paginateCount)",
        )
    }

    private fun schedulePrefetch(spread: PageSpread) {
        viewModelScope.launch {
            val snapshot = pageMutex.withLock { paginatorLeft to dualActive }
            val paginator = snapshot.first ?: return@launch
            val dual = snapshot.second
            val total = _uiState.value.totalChars
            var next: PageSpread? = null
            var prev: PageSpread? = null
            withContext(Dispatchers.Default) {
                runCatching {
                    if (dual) {
                        val r = spread.right
                        if (r != null && r.charEnd < total) {
                            next = spreadFrom(paginator, true, r.charEnd)
                        }
                        paginator.pageBefore(spread.left.charStart)?.let { p1 ->
                            val p0 = paginator.pageBefore(p1.charStart)
                            prev = spreadFrom(paginator, true, p0?.charStart ?: p1.charStart)
                        }
                    } else {
                        if (spread.left.charEnd < total) {
                            next = spreadFrom(paginator, false, spread.left.charEnd)
                        }
                        paginator.pageBefore(spread.left.charStart)?.let { p1 ->
                            prev = spreadFrom(paginator, false, p1.charStart)
                        }
                    }
                }
            }
            if (_uiState.value.spread == spread) {
                _nextSpread.value = next
                _prevSpread.value = prev
            }
            pregenCurlBitmaps(listOfNotNull(spread, prev, next))
        }
    }

    override fun onCleared() {
        val offset = pendingSave.value
        val nowMs = System.currentTimeMillis()
        kotlinx.coroutines.runBlocking {
            runCatching {
                if (offset != null) {
                    bookshelfRepository.saveProgress(buildProgress(offset, nowMs))
                }
                bookshelfRepository.touchLastRead(bookId, nowMs)
                flushReadingSession(nowMs)
            }
        }
        // 关闭内容源，连带取消未完成的后台索引构建
        runCatching { (content as? java.io.Closeable)?.close() }
        content = null
        curlBitmapCache.clear()
    }

    companion object {
        fun factory(
            container: AppContainer,
            bookId: Long,
            initialAnchor: Long = -1L,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                container.activeReaderBookId.value = bookId
                ReaderViewModel(
                    bookId = bookId,
                    bookshelfRepository = container.bookshelfRepository,
                    bookPrefsRepository = container.bookPrefsRepository,
                    parser = container.txtBookParser,
                    fontManager = container.fontManager,
                    pageDiskCache = container.pageDiskCache,
                    initialAnchor = initialAnchor,
                )
            }
        }
    }
}

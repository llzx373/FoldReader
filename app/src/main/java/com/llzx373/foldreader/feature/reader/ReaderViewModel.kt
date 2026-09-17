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
import com.llzx373.foldreader.core.format.BookParsers
import com.llzx373.foldreader.core.format.Chapter
import com.llzx373.foldreader.core.format.EncodingDetector
import com.llzx373.foldreader.core.format.PageLabel
import com.llzx373.foldreader.core.format.TextSpan
import com.llzx373.foldreader.core.format.TextSpanType
import com.llzx373.foldreader.core.format.pageLabelAt
import com.llzx373.foldreader.core.reader.CharsReadTracker
import com.llzx373.foldreader.core.reader.FontManager
import com.llzx373.foldreader.core.reader.LayoutConfig
import com.llzx373.foldreader.core.reader.Page
import com.llzx373.foldreader.core.reader.PageCache
import com.llzx373.foldreader.core.reader.PageDiskCache
import com.llzx373.foldreader.core.reader.Paginator
import com.llzx373.foldreader.core.reader.PaginatorKey
import com.llzx373.foldreader.core.reader.PaginatorStore
import com.llzx373.foldreader.core.reader.StaticLayoutTextMeasurer
import com.llzx373.foldreader.core.reader.decodeSampledImage
import com.llzx373.foldreader.core.reader.renderSpreadToBitmap
import com.llzx373.foldreader.core.reader.sessionFlushDelta
import com.llzx373.foldreader.core.reader.sliceSpansForLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
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
    /** 当前位置对应的纸书页码（EPUB page-list）；无 page-list 的书为 null。 */
    val paperPageLabel: String? = null,
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
    private val parsers: BookParsers,
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
    private var paperPageLabels: List<PageLabel>? = null
    /** 打开书时加载一次的样式/结构 span（EPUB）；TXT/FB2 为 null。 */
    private var textSpans: List<TextSpan>? = null
    /** 图片占位段落表（占位符偏移 → IMAGE span），buildPaginator 时注入分页器。 */
    private var imageLineSpans: Map<Long, TextSpan> = emptyMap()
    private var bookUri: Uri? = null
    private var bookParser: BookParser? = null
    /** 图片位图 LRU（按字节数）；渲染同步路径只查缓存，解码在 spread 构建期预取。 */
    private val imageBitmapCache = object : android.util.LruCache<String, Bitmap>(IMAGE_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    /** 图片行位图查询（渲染期同步调用；未命中 = 占位灰框，不阻塞）。 */
    val imageProvider: (String) -> Bitmap? = { imageBitmapCache.get(it) }
    private var lastPageWidthPx = 0
    private var appliedEncoding: String? = null
    private var pendingReopenAnchor = -1L
    private var baseReadingMillis = 0L
    private var firstReadAtMs = 0L
    private var charsReadBase = 0L
    private val charsReadTracker = CharsReadTracker()
    private var lastSessionFlushTotalMs = 0L
    private var paginatorLeft: Paginator? = null
    /** 播种（临时起点）分页器激活期间，后台追赶用的精确分页器。 */
    private var exactPaginator: Paginator? = null
    private var boundsJob: kotlinx.coroutines.Job? = null
    /** 首帧已上屏：后台全书分页、位图预渲染等重活的总开关。 */
    private val firstFrameRendered = MutableStateFlow(false)

    /** UI 在第一帧实际绘制后调用；此前不做任何抢占 CPU 的后台工作。 */
    fun noteFirstFrameRendered() {
        firstFrameRendered.value = true
    }

    private var dualActive = false
    // 当前分页器产出版式：showSpread 落账时用它们盖章（而不是 uiState 现值），
    // 避免重分页竞态把"旧版式 spread + 新版式指纹"发布出去
    private var activeConfig: LayoutConfig? = null
    private var activeGeom: SpreadGeometry? = null
    private val paginatorStore = PaginatorStore()
    private val pageMutex = Mutex()
    private val anchorOffset = MutableStateFlow(0L)
    private val viewport = MutableStateFlow<SpreadViewport?>(null)
    /** 实时索引封口（或失败收尾）时 +1，驱动 collectViewport 用最终 charCount 重排一次。 */
    private val contentRevision = MutableStateFlow(0)
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
            imageProvider = imageProvider,
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
            // 命中累积在批处理器里，按时间/条数节流发布：逐条 update 会让每个命中
            // 都拷贝一整份命中列表（O(n²)）并发一次 StateFlow，命中上万条时 UI 直接卡死。
            val batcher = SearchHitBatcher()
            var scanned = 0L
            com.llzx373.foldreader.core.format.searchContent(
                content = source,
                query = query,
                onHit = { hit ->
                    val now = System.currentTimeMillis()
                    if (batcher.onHit(hit, now)) {
                        batcher.onPublished(now)
                        publishSearch(batcher.hits, scanned)
                    }
                },
                onProgress = { chars ->
                    scanned = chars
                    val now = System.currentTimeMillis()
                    if (batcher.onProgress(now)) {
                        batcher.onPublished(now)
                        publishSearch(batcher.hits, scanned)
                    }
                },
            )
            publishSearch(batcher.hits, scanned)
            _searchState.update { it.copy(running = false) }
        }
    }

    private fun publishSearch(
        hits: List<com.llzx373.foldreader.core.format.SearchHit>,
        scanned: Long,
    ) {
        val snapshot = hits.toList()
        _searchState.update { it.copy(hits = snapshot, scannedChars = scanned) }
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

    /**
     * 脚注弹注内容：从目标偏移读一段原文并按段落/长度截取；
     * 目标越界（如注释锚点在 linear="no" 部分）或读取失败时返回 null，调用方降级为普通跳转。
     */
    suspend fun noteExcerptAt(offset: Long): String? {
        val source = content ?: return null
        val total = source.charCount
        if (offset < 0 || offset >= total) return null
        val end = minOf(offset + NOTE_EXCERPT_READ_CHARS, total)
        val raw = withContext(Dispatchers.IO) {
            runCatching { source.read(offset until end) }.getOrNull()
        } ?: return null
        return com.llzx373.foldreader.core.reader.excerptNote(raw).takeIf { it.isNotEmpty() }
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
            // 章节随数据库被动更新：首次打开时表为空，后台索引扫完落库后这里自动刷新
            bookshelfRepository.observeChapters(bookId).collect { list ->
                chapters = list
                refreshChapterState()
            }
        }
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
                val parser = parsers.parserFor(book.format)
                val charsetOverride = EncodingDetector.forNameOrNull(book.encoding)
                // 打开关键路径只做两件事：建/读偏移索引（缺失时异步后台补建）+ 恢复进度。
                // 章节扫描不再挡在首帧前：实时索引完成后由 onChaptersIndexed 落库，
                // 本章节的 observeChapters 收集器随数据库更新自动刷新。
                val opened = withContext(Dispatchers.IO) {
                    parser.openContent(uri, charsetOverride)
                }
                content = opened
                appliedEncoding = book.encoding
                verifyAnnotationSnapshots()
                val progress = bookshelfRepository.getProgress(bookId)
                baseReadingMillis = progress?.totalReadingMillis ?: 0L
                firstReadAtMs = progress?.firstReadAt ?: 0L
                charsReadBase = progress?.charsReadTotal ?: 0L
                // 首次打开（无进度）时尊重 EPUB landmarks/guide 正文起点；TXT/FB2 恒 null
                val preferredStart = if (progress == null && pendingReopenAnchor < 0L && initialAnchor < 0L) {
                    withContext(Dispatchers.IO) {
                        runCatching { parser.preferredStartOffset(uri) }.getOrNull()
                    }
                } else {
                    null
                }
                paperPageLabels = withContext(Dispatchers.IO) {
                    runCatching { parser.pageLabels(uri) }.getOrNull()
                }
                // 样式/结构 span（EPUB）：打开时加载一次，构建 spread 时按行切片挂载
                val spans = withContext(Dispatchers.IO) {
                    runCatching { parser.textSpans(uri) }.getOrNull()
                }
                textSpans = spans
                imageLineSpans = spans.orEmpty()
                    .filter { it.type == TextSpanType.IMAGE }
                    .associateBy { it.start }
                bookUri = uri
                bookParser = parser
                anchorOffset.value = when {
                    pendingReopenAnchor >= 0L ->
                        pendingReopenAnchor.coerceAtMost(opened.charCount).also {
                            pendingReopenAnchor = -1L
                        }
                    initialAnchor >= 0L -> initialAnchor.coerceAtMost(opened.charCount)
                    else -> progress?.charOffset
                        ?: preferredStart?.coerceAtMost(opened.charCount)
                        ?: 0L
                }
                charsReadTracker.jump(anchorOffset.value)
                _uiState.update {
                    it.copy(bookTitle = book.title, totalChars = opened.charCount)
                }
                maybeScanChaptersInBackground(uri, charsetOverride, opened, parser)
                watchLiveIndexCompletion(opened)
                collectViewport()
            } catch (t: Throwable) {
                _uiState.update { it.copy(loading = false, error = t.message ?: "打开失败") }
            }
        }
    }

    /**
     * 兜底补扫章节：索引快照有效（无后台索引在跑）但章节表为空，
     * 说明上次扫描结果没落库；首帧上屏后闲时补扫一次。
     */
    private fun maybeScanChaptersInBackground(
        uri: Uri,
        charsetOverride: java.nio.charset.Charset?,
        opened: BookContent,
        parser: BookParser,
    ) {
        val liveIndexing = (opened as? com.llzx373.foldreader.core.format.txt.TxtBookContent)
            ?.indexProgress != null
        if (liveIndexing) return
        viewModelScope.launch(Dispatchers.IO) {
            firstFrameRendered.filter { it }.first()
            if (chapters.isNotEmpty()) return@launch
            val scanned = runCatching { parser.parseChapters(uri, charsetOverride) }.getOrNull()
                ?: return@launch
            if (chapters.isEmpty()) {
                runCatching { bookshelfRepository.saveChapters(bookId, scanned) }
            }
        }
    }

    /**
     * 实时索引（无快照首开时后台异步建偏移索引）封口后收尾：
     * charCount 从 0 长到终值，这里刷新 totalChars 并触发一次重排，
     * 否则首帧之后进度百分比、总页数、翻页/预取都停在 totalChars=0 的状态。
     */
    private fun watchLiveIndexCompletion(opened: BookContent) {
        val live = opened as? com.llzx373.foldreader.core.format.txt.TxtBookContent
        if (live?.indexProgress == null) return
        viewModelScope.launch {
            // 等索引封口（总数不可能超过 MAX_VALUE，即等到 complete/失败为止）
            runCatching { live.awaitCharsAbove(Long.MAX_VALUE) }
            if (content !== live) return@launch
            _uiState.update { it.copy(totalChars = live.charCount) }
            contentRevision.update { it + 1 }
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
            contentRevision,
        ) { v, _, p, _ -> v to p }.collectLatest { (v, p) ->
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
                val anchor = anchorOffset.value
                val t0 = System.nanoTime()
                val prepared = withContext(Dispatchers.Default) {
                    val exact = buildPaginator(
                        source, config, pageWidthPx, v.heightPx, v.density, v.scaledDensity, v.avoidance,
                    )
                    // 磁盘/已知边界覆盖不到锚点且锚点在书中部：从锚点所在段首临时起排，
                    // 先把第一屏排出来；精确前缀边界由 scheduleBoundsBuild 后台追上后切换
                    val seeded = if (!exact.boundsCover(anchor) && anchor >= SEED_MIN_ANCHOR_CHARS) {
                        val origin = exact.snapToParagraphStart(anchor)
                        // 全书无换行（起点为 0）或锚点恰在段边界末尾（起点即文末，会排出空页）时不播种
                        if (origin > 0L && origin < source.charCount) {
                            buildPaginator(
                                source, config, pageWidthPx, v.heightPx, v.density, v.scaledDensity,
                                v.avoidance, sharedCache = false,
                            ).also { it.seed(origin, exact.estimatePageIndex(origin)) }
                        } else {
                            null
                        }
                    } else {
                        null
                    }
                    val active = seeded ?: exact
                    Triple(exact, active, spreadFrom(active, v.dual, anchor))
                }
                logPaginateTiming(t0)
                val geom = SpreadGeometry(v.dual, pageWidthPx, v.heightPx)
                pageMutex.withLock {
                    paginatorLeft = prepared.second
                    exactPaginator = if (prepared.second !== prepared.first) prepared.first else null
                    dualActive = v.dual
                    activeConfig = config
                    activeGeom = geom
                }
                publish(prepared.third, config, v.dual, geom)
                if (prepared.second !== prepared.first) {
                    // 精确总页数未知期间先给估算值，后台追界时逐步修正
                    _uiState.update {
                        it.copy(totalPages = prepared.first.estimatePageIndex(it.totalChars) + 1)
                    }
                }
                // 滚动模式：版式变化后按当前锚点用新分页器重建滚动页流
                if (wasScrolling) enterScrollMode()
                scheduleBoundsBuild(prepared.first)
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
        /** false = 临时（播种）分页器：页边界与精确序列不同，不进共享缓存、不读盘。 */
        sharedCache: Boolean = true,
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
        lastPageWidthPx = widthPx
        return Paginator(
            content = source,
            config = capped,
            measurer = StaticLayoutTextMeasurer(),
            widthPx = widthPx,
            heightPx = heightPx,
            density = density,
            scaledDensity = scaledDensity,
            cache = if (sharedCache) paginatorStore.getOrCreate(key) else PageCache(64),
            diskCache = if (sharedCache) pageDiskCache else null,
            diskKey = if (sharedCache) key else null,
            avoidance = avoidance,
            images = imageLineSpans,
        )
    }

    /**
     * 首帧上屏后（再稍候片刻）后台把全书边界排完：逐段落盘、向 UI 汇报总页数，
     * 追上当前阅读位置时把播种分页器切换为精确分页器。
     */
    private fun scheduleBoundsBuild(paginator: Paginator) {
        boundsJob?.cancel()
        val total = content?.charCount ?: return
        if (total <= 0L) return
        boundsJob = viewModelScope.launch {
            firstFrameRendered.filter { it }.first()
            delay(FULL_BOUNDS_IDLE_DELAY_MS)
            withContext(Dispatchers.Default) {
                if (paginator.hasFullBoundaryIndex) {
                    _uiState.update { it.copy(totalPages = paginator.boundaryPageCount) }
                    swapToExactIfCovered(paginator)
                    return@withContext
                }
                val count = paginateToEnd(paginator, total) { done ->
                    _uiState.update { it.copy(totalPages = done) }
                    swapToExactIfCovered(paginator)
                }
                _uiState.update { it.copy(totalPages = count) }
                withContext(Dispatchers.IO) { paginator.persistBounds() }
            }
        }
    }

    /** 精确边界追上当前位置后整体切换：重新锚定当前 spread（页起点可能与临时分页略不同）。 */
    private suspend fun swapToExactIfCovered(exact: Paginator) {
        pageMutex.withLock {
            if (exactPaginator === exact && paginatorLeft?.isSeeded == true) paginatorLeft else null
        } ?: return
        if (!exact.boundsCover(anchorOffset.value)) return
        val dual = pageMutex.withLock {
            if (exactPaginator !== exact) return
            paginatorLeft = exact
            exactPaginator = null
            dualActive
        }
        _prevSpread.value = null
        _nextSpread.value = null
        val spread = withContext(Dispatchers.Default) { spreadFrom(exact, dual, anchorOffset.value) }
        showSpread(spread, countCharsRead = false)
        if (_uiState.value.scrollPages.isNotEmpty()) enterScrollMode()
    }

    /** 目标越过临时起点（向前翻回起点之前）时，立即切回精确分页器同步补排前缀。 */
    private suspend fun paginatorFor(anchor: Long): Paginator? {
        val current = pageMutex.withLock { paginatorLeft } ?: return null
        if (!current.isSeeded || anchor >= current.seedOrigin) return current
        return pageMutex.withLock {
            val exact = exactPaginator
            if (exact != null) {
                paginatorLeft = exact
                exactPaginator = null
            }
            exact ?: paginatorLeft
        }
    }

    private suspend fun paginateToEnd(paginator: Paginator, total: Long, onBatch: suspend (Int) -> Unit): Int {
        var pos = 0L
        var count = 1
        var sincePersist = 0
        while (pos < total) {
            kotlinx.coroutines.yield()
            // 已落盘/已排出的前缀边界直接跳过，不为它们重新排版
            val known = paginator.knownBoundAfter(pos)
            pos = if (known != null) {
                known
            } else {
                val page = paginator.pageAt(pos)
                if (page.charEnd <= pos) break
                page.charEnd
            }
            count++
            sincePersist++
            if (count % 16 == 0) {
                onBatch(count)
                delay(10L)
            }
            // 分段落盘：中途退出时前缀边界仍在，下次打开只需接续后排
            if (sincePersist >= INCREMENTAL_PERSIST_PAGES) {
                sincePersist = 0
                withContext(Dispatchers.IO) { paginator.persistBounds() }
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
        // span 渲染期挂载（不进任何分页缓存）+ 图片位图预取（IO，未就绪则画占位灰框）
        val left = attachSpans(leftPage)
        val right = rightPage?.let { attachSpans(it) }
        prefetchImages(left)
        right?.let { prefetchImages(it) }
        return PageSpread(left, right)
    }

    /** 把落在各行区间内的样式 span 截断/拆段挂到行上；无 span 的书零开销返回原页。 */
    private fun attachSpans(page: Page): Page {
        val spans = textSpans ?: return page
        if (spans.isEmpty()) return page
        val styled = spans.any { it.type != TextSpanType.IMAGE }
        if (!styled) return page
        return page.copy(
            lines = page.lines.map { line ->
                if (line.imagePath != null || line.text.isEmpty()) {
                    line
                } else {
                    line.copy(
                        spans = sliceSpansForLine(spans, line.charStart, line.charStart + line.text.length),
                    )
                }
            },
        )
    }

    /** spread 内图片行的位图预取：缺失时 IO 解码（按行框尺寸降采样）进 LRU。 */
    private suspend fun prefetchImages(page: Page) {
        for (line in page.lines) {
            val path = line.imagePath ?: continue
            if (imageBitmapCache.get(path) != null) continue
            val uri = bookUri ?: return
            val parser = bookParser ?: return
            val file = withContext(Dispatchers.IO) {
                runCatching { parser.imageFile(uri, path) }.getOrNull()
            } ?: continue
            val targetH = (line.heightPx ?: 0f).toInt().coerceAtLeast(64)
            val targetW = lastPageWidthPx.takeIf { it > 0 } ?: (targetH * 3)
            val bitmap = withContext(Dispatchers.IO) {
                runCatching { decodeSampledImage(file, targetW, targetH) }.getOrNull()
            } ?: continue
            imageBitmapCache.put(path, bitmap)
        }
    }

    /** 系统内存紧张时清空插图位图缓存；未命中的行回落占位灰框，之后按需重新解码。 */
    fun clearImageBitmaps() {
        imageBitmapCache.evictAll()
    }

    private suspend fun currentSpreadFrom(anchor: Long): PageSpread? {
        val paginator = paginatorFor(anchor) ?: return null
        val dual = pageMutex.withLock { dualActive }
        return withContext(Dispatchers.Default) { spreadFrom(paginator, dual, anchor) }
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
        val dual = pageMutex.withLock { dualActive }
        val source = content
        var total = source?.charCount ?: _uiState.value.totalChars
        val anchor = when {
            forward && dual -> current.right?.charEnd ?: return null
            forward -> {
                if (current.left.charEnd >= total && source != null && !source.isCharCountFinal) {
                    // 实时索引还在增长：等它越过当前页尾再判断是否真到文末
                    source.awaitCharsAbove(current.left.charEnd)
                    total = source.charCount
                }
                if (current.left.charEnd < total) current.left.charEnd else return null
            }
            else -> {
                val left = paginatorFor(current.left.charStart - 1) ?: return null
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
        val edge = if (forward) pages.last().charStart else pages.first().charStart - 1
        val p = paginatorFor(edge) ?: return
        val next = withContext(Dispatchers.Default) {
            if (forward) p.pageAfter(pages.last().charStart) else p.pageBefore(pages.first().charStart)
        }?.let { attachSpans(it).also { attached -> prefetchImages(attached) } } ?: return
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

    /**
     * 系统内存紧张（onTrimMemory）时清空两类位图缓存：翻页离屏位图与插图位图，
     * 后续翻页现场重渲染、未命中的图片行先画占位灰框再按需解码。
     */
    fun clearBitmaps() {
        curlBitmapCache.clear()
        clearImageBitmaps()
    }

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

    /** 章节表更新后按当前位置刷新章节标题/序号/章内进度。 */
    private fun refreshChapterState() {
        val anchor = _uiState.value.spread?.left?.charStart ?: anchorOffset.value
        _uiState.update {
            it.copy(
                chapterTitle = chapters.getOrNull(chapterIndexAt(chapters, anchor))?.title.orEmpty(),
                chapterIndex = chapterIndexAt(chapters, anchor),
                chapterCount = chapters.size,
                inChapterFraction = inChapterFraction(chapters, chapterIndexAt(chapters, anchor), anchor),
            )
        }
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
                paperPageLabel = paperPageLabels?.let {
                    pageLabelAt(it, spread.left.charStart)?.label
                },
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
            // 用实时 charCount：索引增长期间也能向后排预取
            val total = content?.charCount ?: _uiState.value.totalChars
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
            // 首帧上屏前不渲染整页位图（3 张整页 drawText 会跟首屏抢 CPU）
            firstFrameRendered.filter { it }.first()
            pregenCurlBitmaps(listOfNotNull(spread, prev, next))
        }
    }

    override fun onCleared() {
        val offset = pendingSave.value
        val nowMs = System.currentTimeMillis()
        val exact = exactPaginator
        val active = paginatorLeft
        // 收尾落库 + 页边界落盘 + 关闭内容源放 IO 线程异步做：
        // 进度在阅读期间已由防抖保存覆盖，这里只是最后一笔，
        // 不值得在主线程 runBlocking 挡返回转场的收尾帧。
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                if (offset != null) {
                    bookshelfRepository.saveProgress(buildProgress(offset, nowMs))
                }
                bookshelfRepository.touchLastRead(bookId, nowMs)
                flushReadingSession(nowMs)
            }
            runCatching { exact?.persistBounds() }
            runCatching { active?.persistBounds() }
            // 关闭内容源，连带取消未完成的后台索引构建
            runCatching { (content as? java.io.Closeable)?.close() }
            content = null
            curlBitmapCache.clear()
        }
    }

    companion object {
        /** 锚点深于该字数且页边界缓存未覆盖时，才启用段首播种起排（浅位置从 0 排足够快）。 */
        private const val SEED_MIN_ANCHOR_CHARS = 30_000L

        /** 首帧上屏后再延迟该时长，才启动全书后台分页，避开首次翻页。 */
        private const val FULL_BOUNDS_IDLE_DELAY_MS = 400L

        /** 后台每多排多少页就把页边界增量落盘一次。 */
        private const val INCREMENTAL_PERSIST_PAGES = 64

        /** 内嵌图片位图 LRU 容量（字节数）。 */
        private const val IMAGE_CACHE_BYTES = 16 * 1024 * 1024

        /** 弹注读取窗口：远大于截取上限，保证段落过滤后仍有足够内容。 */
        private const val NOTE_EXCERPT_READ_CHARS = 2_000L

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
                    parsers = container.bookParsers,
                    fontManager = container.fontManager,
                    pageDiskCache = container.pageDiskCache,
                    initialAnchor = initialAnchor,
                )
            }
        }
    }
}

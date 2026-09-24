package com.llzx373.foldreader.feature.reader

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.llzx373.foldreader.AppContainer
import com.llzx373.foldreader.core.ai.AiException
import com.llzx373.foldreader.core.ai.AiTargetLang
import com.llzx373.foldreader.core.ai.prompt.SelectionTranslatePrompt
import com.llzx373.foldreader.core.data.db.BookFormat
import com.llzx373.foldreader.core.data.db.PersonAppearanceEntity
import com.llzx373.foldreader.core.data.db.ReadingProgressEntity
import com.llzx373.foldreader.core.data.repository.BookPrefsRepository
import com.llzx373.foldreader.core.data.repository.BookshelfRepository
import com.llzx373.foldreader.core.data.settings.DualPageMode
import com.llzx373.foldreader.core.data.settings.PageTurnMode
import com.llzx373.foldreader.core.data.settings.ReadingPreferences
import com.llzx373.foldreader.core.data.settings.ReadingTheme
import com.llzx373.foldreader.core.data.settings.SettingsRepository
import com.llzx373.foldreader.core.debug.DiagnosticLog
import com.llzx373.foldreader.core.format.BookContent
import com.llzx373.foldreader.core.format.BookParser
import com.llzx373.foldreader.core.format.BookParsers
import com.llzx373.foldreader.core.format.Chapter
import com.llzx373.foldreader.core.format.EncodingDetector
import com.llzx373.foldreader.core.format.PageLabel
import com.llzx373.foldreader.core.format.StringBookContent
import com.llzx373.foldreader.core.format.TextSpan
import com.llzx373.foldreader.core.format.TextSpanType
import com.llzx373.foldreader.core.format.pageLabelAt
import com.llzx373.foldreader.core.translate.TranslationUnit
import com.llzx373.foldreader.core.translate.bilingualSyncTarget
import com.llzx373.foldreader.core.translate.computeUnits
import com.llzx373.foldreader.core.translate.locateTranslatedUnit
import com.llzx373.foldreader.core.translate.locateUnit
import com.llzx373.foldreader.core.translate.offsetInOriginal
import com.llzx373.foldreader.core.translate.offsetInTranslated
import com.llzx373.foldreader.core.translate.splitIntoParagraphs
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
import com.llzx373.foldreader.core.reader.collectScrollPages
import com.llzx373.foldreader.core.reader.decodeSampledImage
import com.llzx373.foldreader.core.reader.sessionFlushDelta
import com.llzx373.foldreader.core.reader.sliceSpansForLine
import com.llzx373.foldreader.core.tts.TtsSentenceSplitter
import com.llzx373.foldreader.core.tts.TtsState
import com.llzx373.foldreader.core.tts.android.ReaderTtsController
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Immutable
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

/**
 * 阅读页的**结构态**：只在打开、翻页、改版式、折叠切换时变。
 * 滚动/翻页过程中每页都变的展示态见 [ReadingPosition]。
 */
data class ReaderUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val bookTitle: String = "",
    val totalChars: Long = 0,
    val spread: PageSpread? = null,
    val dualPage: Boolean = false,
    val layoutConfig: LayoutConfig = LayoutConfig(),
    val totalPages: Int = 0,
    val spreadGeometry: SpreadGeometry? = null,
)

/**
 * 阅读位置展示态：每跨一页都会变。
 *
 * 与 [ReaderUiState] 分开是因为滚动模式下它每页都更新——留在同一个 data class 里，
 * 每跨一页都会让读取 uiState 的整棵阅读树重组。独立成流后只有真正显示它的
 * 页眉/页脚/菜单订阅，且订阅点都放在各自的 Composable 或可见性判断之内。
 */
@Immutable
data class ReadingPosition(
    val progressFraction: Float = 0f,
    val chapterTitle: String = "",
    val chapterIndex: Int = 0,
    val chapterCount: Int = 0,
    val pageNumber: Int = 0,
    val inChapterFraction: Float = -1f,
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

@OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ReaderViewModel(
    private val bookId: Long,
    private val bookshelfRepository: BookshelfRepository,
    private val bookPrefsRepository: BookPrefsRepository,
    /** 应用级偏好（双页模式等）直写全局：这些项不随书独立演化。 */
    private val settingsRepository: SettingsRepository,
    private val parsers: BookParsers,
    private val fontManager: FontManager,
    private val pageDiskCache: PageDiskCache,
    /** TTS 听书控制器（M13.1）：AppContainer 持有的进程级单例，状态不经 Composable。 */
    private val ttsController: ReaderTtsController,
    private val initialAnchor: Long = -1L,
    /** M18 选中即译：按当前设置装配 AI Provider；未配置返回 null（默认即未配置）。 */
    private val aiProvider: suspend () -> com.llzx373.foldreader.core.ai.AiProvider? = { null },
    /** M18 选中即译：外发台账记录（feature / scope / 估算 token 数）。 */
    private val recordOutbound: (feature: String, scope: String, estimatedTokens: Int) -> Unit =
        { _, _, _ -> },
    /** M19：译本副本存储；null = 翻译功能不挂（界面零变化）。 */
    private val translationStore: com.llzx373.foldreader.core.translate.TranslationStore? = null,
    /** M19：翻译单位台账（目录面板状态行的数据源）。 */
    private val translationDao: com.llzx373.foldreader.core.data.db.TranslationDao? = null,
    /** M19：按当前设置装配翻译引擎（Provider 未配置时引擎内失败返回）。 */
    private val translateEngine: suspend () -> com.llzx373.foldreader.feature.translate.TranslateEngine? =
        { null },
    /** M20 全书翻译队列（R5 插队）：null = 不挂（测试中默认不挂）。 */
    private val translationQueue: com.llzx373.foldreader.feature.translate.BookTranslationQueue? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    private val _readingPosition = MutableStateFlow(ReadingPosition())
    val readingPosition: StateFlow<ReadingPosition> = _readingPosition.asStateFlow()

    /**
     * 滚动模式页流。用快照列表而非 `StateFlow<List<Page>>` 的原因：
     *  - 追加/前插是 O(1)，不必整表拷贝（原实现每加一页都重建整个 List）；
     *  - 读取它的只有 LazyColumn 的 items，变更只失效那一段，不会连累整棵阅读树重组。
     * 写入均发生在主线程（调用方协程上下文）。
     */
    val scrollPages: SnapshotStateList<Page> = mutableStateListOf()

    // stateIn 初值是默认偏好，真正的每书偏好异步到达；分页与 viewport 必须等首次真实值，
    // 否则进书会先按默认偏好（AUTO）排版再跳变（单页闪成双页或反之）。
    private val _preferencesLoaded = MutableStateFlow(false)
    val preferencesLoaded: StateFlow<Boolean> = _preferencesLoaded.asStateFlow()

    val preferences: StateFlow<ReadingPreferences> = bookPrefsRepository.observe(bookId)
        .onEach { _preferencesLoaded.value = true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReadingPreferences())

    /**
     * 人物出场索引（M13.2）。只有目录面板的「人物」页签会订阅它——面板一关
     * WhileSubscribed 就停掉上游收集，不参与每页重组路径。
     */
    val personAppearances: StateFlow<List<PersonAppearanceEntity>> =
        bookshelfRepository.observePersonAppearances(bookId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // 下面这些是「主线程发布、之后被 Default/IO 线程读取」的字段：分页（Default）与位图预取（IO）
    // 都要读它们。不标 @Volatile 时另一个线程可能长读旧引用（改字号/换书之后尤其明显），所以全部标上。
    @Volatile private var content: BookContent? = null
    @Volatile private var chapters: List<Chapter> = emptyList()
    @Volatile private var paperPageLabels: List<PageLabel>? = null
    /** 打开书时加载一次的样式/结构 span（EPUB）；TXT/FB2 为 null。 */
    @Volatile private var textSpans: List<TextSpan>? = null
    /** 图片占位段落表（占位符偏移 → IMAGE span），buildPaginator 时注入分页器。 */
    @Volatile private var imageLineSpans: Map<Long, TextSpan> = emptyMap()
    @Volatile private var bookUri: Uri? = null
    @Volatile private var bookParser: BookParser? = null
    /** 图片位图 LRU（按字节数）；渲染同步路径只查缓存，解码在 spread 构建期预取。 */
    private val imageBitmapCache = object : android.util.LruCache<String, Bitmap>(IMAGE_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    /** 图片行位图查询（渲染期同步调用；未命中 = 占位灰框，不阻塞）。 */
    val imageProvider: (String) -> Bitmap? = { imageBitmapCache.get(it) }
    /** 每页宽高（Default 线程写、IO 线程预取位图时读）。 */
    @Volatile private var lastPageWidthPx = 0
    private var appliedEncoding: String? = null
    /** 打开时那一份正文所在的清洗副本路径；null 表示当时直接读原文件。 */
    private var appliedCleanedPath: String? = null
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

    /** TTS 朗读（M13.1）整段状态：UI 订阅显示「停止朗读」/错误提示，下方 collector 做翻页联动。 */
    val ttsState: StateFlow<TtsState> = ttsController.state

    private val ttsTurnRequests = AutoPageTurnRequests()
    /** TTS 推进到下一跨页时的翻页请求：走 UI 正常翻页动画（落后多页时 VM 直接 seek，不经这里）。 */
    val ttsPageTurns: kotlinx.coroutines.flow.SharedFlow<Boolean> = ttsTurnRequests.requests

    /**
     * 只保留**文本锚点**的书签。
     *
     * 同一本书可能两种锚点都有（文本型 PDF 既能当电子书读、也能按页读），
     * 页式书签的 [BookmarkEntity.charOffset] 恒为 0，混进来会全部挤在第 0 字符上。
     */
    val bookmarks: StateFlow<List<com.llzx373.foldreader.core.data.db.BookmarkEntity>> =
        bookshelfRepository.observeBookmarks(bookId)
            .map { list -> list.filter { it.pageIndex == null } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 同理只保留文本锚点的标注；页式高亮由页式阅读器负责渲染。 */
    val annotations: StateFlow<List<com.llzx373.foldreader.core.data.db.AnnotationEntity>> =
        bookshelfRepository.observeAnnotations(bookId)
            .map { list -> list.filter { it.pageIndex == null } }
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
        }.filter { it.pageIndex == null }
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

    // ---------- M18 选中即译 ----------

    /** 翻译卡片状态；null = 卡片关闭。状态机见 [TranslateCardUi]。 */
    private val _translationCardState = MutableStateFlow<TranslateCardUi?>(null)
    val translationCardState: StateFlow<TranslateCardUi?> = _translationCardState.asStateFlow()

    /** 一次性轻提示（如「已存为批注」）：UI 收集后短暂展示，不重组阅读树。 */
    val notices = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 4)

    private var translateJob: kotlinx.coroutines.Job? = null
    private var translateSelStart = 0L
    private var translateSelEnd = 0L
    private var translateSource = ""
    private var translateLang = AiTargetLang.ZH_HANS

    /** 入口：取选区文本 → 首次外发一次性确认（仅首次）→ 流式翻译。 */
    fun translateSelection(start: Long, end: Long) {
        translateJob?.cancel()
        translateJob = viewModelScope.launch {
            val text = selectedTextOf(start, end)
            if (text.isBlank()) return@launch
            translateSelStart = start
            translateSelEnd = end
            translateSource = text
            val prefs = settingsRepository.preferences.first()
            translateLang = prefs.aiTargetLang
            if (prefs.aiTranslationConfirmed) {
                streamTranslation()
            } else {
                _translationCardState.value = TranslateCardUi.AwaitConfirmation(
                    baseUrl = prefs.aiBaseUrl,
                    lang = translateLang,
                )
            }
        }
    }

    /** 确认框「同意并翻译」：落一次性确认标记后开始翻译。 */
    fun confirmTranslation() {
        if (_translationCardState.value !is TranslateCardUi.AwaitConfirmation) return
        startTranslationJob {
            settingsRepository.setAiTranslationConfirmed(true)
        }
    }

    /**
     * 卡片上临时切换目标语言：只对这张卡片生效，不写回设置。
     * 还在确认阶段只更新显示语言；已在翻译/出错则取消旧请求按新语言重译。
     */
    fun retranslate(lang: AiTargetLang) {
        val current = _translationCardState.value ?: return
        translateLang = lang
        when (current) {
            is TranslateCardUi.AwaitConfirmation ->
                _translationCardState.value = current.copy(lang = lang)
            else -> if (translateSource.isNotEmpty()) startTranslationJob()
        }
    }

    /** 失败重试：选区文本还在内存，直接重走翻译，不重读正文。 */
    fun retryTranslation() {
        if (_translationCardState.value !is TranslateCardUi.Error) return
        startTranslationJob()
    }

    /** 关闭卡片：取消进行中的请求并清状态。 */
    fun closeTranslationCard() {
        translateJob?.cancel()
        translateJob = null
        _translationCardState.value = null
    }

    /** 「存为批注」：复用标注体系，note 存当前译文（流未停也照存当前内容）。 */
    fun saveTranslationAsNote() {
        val s = _translationCardState.value as? TranslateCardUi.Streaming ?: return
        if (s.translatedSoFar.isBlank()) return
        addAnnotation(
            start = translateSelStart,
            end = translateSelEnd,
            color = TRANSLATION_NOTE_COLOR,
            note = "译文（${SelectionTranslatePrompt.displayName(s.lang)}）：\n" +
                s.translatedSoFar.trim(),
        )
        notices.tryEmit("已存为批注")
        closeTranslationCard()
    }

    private fun startTranslationJob(before: suspend () -> Unit = {}) {
        translateJob?.cancel()
        translateJob = viewModelScope.launch {
            before()
            streamTranslation()
        }
    }

    private suspend fun streamTranslation() {
        val lang = translateLang
        val provider = aiProvider()
        if (provider == null) {
            _translationCardState.value =
                TranslateCardUi.Error("请先在设置中完成 AI 服务配置", lang)
            return
        }
        _translationCardState.value = TranslateCardUi.Loading(lang)
        // 外发台账 token 估算口径同「章节规则」：字符数 / 2 的保守量级估算，台账只用于审计
        recordOutbound("选中即译", _uiState.value.bookTitle, translateSource.length / 2)
        val prefs = settingsRepository.preferences.first()
        val model = prefs.aiModelTranslation.ifBlank { prefs.aiModelGeneral }
        val reply = StringBuilder()
        try {
            provider.chat(SelectionTranslatePrompt.buildMessages(translateSource, lang), model)
                .collect { delta ->
                    reply.append(delta)
                    _translationCardState.value = TranslateCardUi.Streaming(
                        source = translateSource,
                        translatedSoFar = reply.toString(),
                        lang = lang,
                        running = true,
                    )
                }
            _translationCardState.value = TranslateCardUi.Streaming(
                source = translateSource,
                translatedSoFar = reply.toString(),
                lang = lang,
                running = false,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: AiException) {
            _translationCardState.value =
                TranslateCardUi.Error(e.message ?: "翻译失败", lang)
        } catch (e: Exception) {
            _translationCardState.value = TranslateCardUi.Error("网络不可达或响应异常", lang)
        }
    }

    // ---------- M19 视角 1：原文 / 译文切换 ----------

    /** 正文模式：原文 / 译文（译本就是另一份内容源，锚点是另一套坐标）。 */
    enum class ReaderViewMode { ORIGINAL, TRANSLATED }

    private val _viewMode = MutableStateFlow(ReaderViewMode.ORIGINAL)
    val viewMode: StateFlow<ReaderViewMode> = _viewMode.asStateFlow()

    /**
     * 当前目标语言的翻译单位（目录面板状态映射 / 切换重定位用）；
     * 空 = 尚未加载。单位一经落盘（saveUnits）即固定，缓存以语言为键。
     */
    private val _translationUnits = MutableStateFlow<List<TranslationUnit>>(emptyList())
    val translationUnits: StateFlow<List<TranslationUnit>> = _translationUnits.asStateFlow()
    private var unitsCacheLang: String? = null

    /** 当前目标语言下各单位的台账（目录面板行状态：未译/翻译中/已译/失败）。 */
    val unitStatuses: StateFlow<List<com.llzx373.foldreader.core.data.db.TranslationEntity>> =
        settingsRepository.preferences
            .map { it.aiTargetLang.name }
            .distinctUntilChanged()
            .flatMapLatest { lang ->
                translationDao?.observeForBook(bookId, lang) ?: flowOf(emptyList())
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // 切到译文时暂存的原文侧现场（切回时原样恢复）
    private var originalContent: BookContent? = null
    private var originalChapters: List<Chapter>? = null
    private var originalTextSpans: List<TextSpan>? = null
    private var originalImageLineSpans: Map<Long, TextSpan> = emptyMap()
    private var originalPaperLabels: List<PageLabel>? = null
    /** 译文期间数据库又到的新章节目录：译文模式下章节观察者只暂存不覆盖内存章节。 */
    private var pendingOriginalChapters: List<Chapter>? = null
    private var viewModeSwitching = false

    /**
     * 加载（或首次计算）本书 [lang] 的翻译单位：优先读盘上清单（切块边界随译本固定），
     * 没有就从当前章节实时切块（不落盘——落盘在首次翻译单位时由 saveUnits 完成）。
     */
    private suspend fun loadUnits(lang: AiTargetLang): List<TranslationUnit> {
        if (unitsCacheLang == lang.name && _translationUnits.value.isNotEmpty()) {
            return _translationUnits.value
        }
        val store = translationStore ?: return emptyList()
        val source = content ?: return emptyList()
        val units = withContext(Dispatchers.IO) {
            store.readUnits(bookId, lang.name) ?: run {
                // 实时索引未封口时 charCount 还会长，切块边界不可靠，宁可不译
                if (!source.isCharCountFinal) return@withContext emptyList()
                // computeUnits 的 read 是非挂起回调，而 BookContent.read 是 suspend：
                // 在 IO 线程上 runBlocking 桥接（read 不碰主线程，无死锁风险）
                computeUnits(chapterList(), source.charCount, read = { range ->
                    kotlinx.coroutines.runBlocking { source.read(range) }
                })
            }
        }
        if (units.isNotEmpty()) {
            unitsCacheLang = lang.name
            _translationUnits.value = units
        }
        return units
    }

    /** 目录面板打开时调用：预载翻译单位（章 ↔ 单位状态映射的数据源）。 */
    fun prepareUnitStatuses() {
        if (_translationUnits.value.isNotEmpty()) return
        viewModelScope.launch {
            loadUnits(settingsRepository.preferences.first().aiTargetLang)
        }
    }

    /** 本书当前目标语言是否已有译本内容（菜单「原文/译文」可用判据）。 */
    suspend fun hasTranslationForTargetLang(): Boolean {
        val store = translationStore ?: return false
        val lang = settingsRepository.preferences.first().aiTargetLang
        return withContext(Dispatchers.IO) { store.hasAny(bookId, lang.name) }
    }

    /** 菜单「原文/译文」切换；译文侧还没有内容时发一次性提示并留在原文。 */
    fun toggleViewMode() {
        if (viewModeSwitching) return
        when (_viewMode.value) {
            ReaderViewMode.ORIGINAL -> switchToTranslated()
            ReaderViewMode.TRANSLATED -> switchToOriginal()
        }
    }

    /**
     * 切到译文：assemble 出译本流 → StringBookContent 替换内容源，章节换译本章节，
     * 锚点按「单位号 + 单位内比例」重定位。分页器改走非共享缓存（页边界不落盘，
     * 免得与原文的同 bookId 页边界缓存互相污染）。
     */
    private fun switchToTranslated() {
        val store = translationStore ?: return
        viewModelScope.launch {
            viewModeSwitching = true
            try {
                // 双页对照的右页是译本坐标，译文模式下整书都是译本——先退出对照
                if (_bilingualCompare.value) exitBilingualCompare(repaint = false)
                val lang = settingsRepository.preferences.first().aiTargetLang
                if (!withContext(Dispatchers.IO) { store.hasAny(bookId, lang.name) }) {
                    notices.tryEmit("还没有已翻译的内容")
                    return@launch
                }
                val units = loadUnits(lang)
                if (units.isEmpty()) return@launch
                val (text, translatedChapters) = withContext(Dispatchers.IO) {
                    store.assemble(bookId, lang.name, units)
                }
                val source = content ?: return@launch
                val (unitIndex, fraction) = locateUnit(units, anchorOffset.value) ?: (0 to 0f)
                val targetAnchor = offsetInTranslated(translatedChapters, unitIndex, fraction)
                // 朗读锚点在原坐标系：切模式前停掉（译文模式 TTS 禁用）
                if (ttsState.value.bookId == bookId && ttsState.value.playing) ttsController.stop()
                originalContent = source
                originalChapters = chapterList()
                originalTextSpans = textSpans
                originalImageLineSpans = imageLineSpans
                originalPaperLabels = paperPageLabels
                content = StringBookContent(text)
                chapters = translatedChapters
                textSpans = null
                imageLineSpans = emptyMap()
                paperPageLabels = null
                _viewMode.value = ReaderViewMode.TRANSLATED
                _uiState.update { it.copy(totalChars = text.length.toLong()) }
                anchorOffset.value = targetAnchor
                // 驱动 collectViewport 用新内容重排（共享页缓存/落盘只服务原文模式）
                contentRevision.update { it + 1 }
                refreshChapterState()
                // 首次切换一句话告知（一次性，之后不再打扰）
                if (!settingsRepository.preferences.first().translationViewHintShown) {
                    notices.tryEmit("已按单位进度定位到译文")
                    settingsRepository.setTranslationViewHintShown(true)
                }
            } finally {
                viewModeSwitching = false
            }
        }
    }

    /** 切回原文：按译文锚点反定位（同一单位同一比例）后恢复原文现场。 */
    private fun switchToOriginal() {
        viewModelScope.launch {
            viewModeSwitching = true
            try {
                val original = originalContent ?: return@launch
                val (unitIndex, fraction) =
                    locateTranslatedUnit(chapterList(), anchorOffset.value) ?: (0 to 0f)
                val targetAnchor = offsetInOriginal(_translationUnits.value, unitIndex, fraction)
                restoreOriginalContent()
                anchorOffset.value = targetAnchor.coerceIn(0L, original.charCount)
                contentRevision.update { it + 1 }
                refreshChapterState()
            } finally {
                viewModeSwitching = false
            }
        }
    }

    /** 恢复原文内容源与章节（切回 / 译文期间原文被换时的兜底共用）。 */
    private fun restoreOriginalContent() {
        val original = originalContent ?: return
        content = original
        originalContent = null
        chapters = pendingOriginalChapters ?: originalChapters ?: chapters
        pendingOriginalChapters = null
        originalChapters = null
        textSpans = originalTextSpans
        imageLineSpans = originalImageLineSpans
        paperPageLabels = originalPaperLabels
        originalTextSpans = null
        originalImageLineSpans = emptyMap()
        originalPaperLabels = null
        _viewMode.value = ReaderViewMode.ORIGINAL
        _uiState.update { it.copy(totalChars = original.charCount) }
    }

    // ---------- M20 视角 2：双页左原文右译文 ----------

    /** 双页对照开关：true 时 spread 的右页替换为译本流第二分页器的页（左页原文为准）。 */
    private val _bilingualCompare = MutableStateFlow(false)
    val bilingualCompare: StateFlow<Boolean> = _bilingualCompare.asStateFlow()

    // 右侧（译本）分页现场：进入对照时装配，离开即整体释放（内存约束：不翻倍常驻）
    private var bilingualContent: StringBookContent? = null
    private var bilingualPaginator: Paginator? = null
    private var bilingualUnits: List<TranslationUnit> = emptyList()
    private var bilingualChapters: List<Chapter> = emptyList()
    /** 右侧当前偏移（译本流）：BilingualSync 判断「右侧是否还停在左页所在单位」的输入。 */
    private var bilingualRightOffset = 0L
    private var bilingualGeomKey: BilingualGeomKey? = null

    /** 右分页器的版式指纹：与左页同高、同宽（dualPageWidthPx 既有规则）、同排版配置。 */
    private data class BilingualGeomKey(
        val widthPx: Int,
        val heightPx: Int,
        val density: Float,
        val scaledDensity: Float,
        val config: LayoutConfig,
    )

    /**
     * 菜单「双页对照」。进入：assemble 译本流 → 右分页器（同视口高、宽走
     * dualPageWidthPx、非共享页缓存不落盘——同 M19 译文模式口径）→ 重排当前跨页。
     * 仅双页翻页布局可用（dualActive）；单页 / 悬停由 UI 隐藏入口并兜底退出。
     */
    fun toggleBilingualCompare() {
        if (_bilingualCompare.value) {
            exitBilingualCompare(repaint = true)
            return
        }
        if (_viewMode.value != ReaderViewMode.ORIGINAL) return
        val store = translationStore ?: return
        viewModelScope.launch {
            if (!pageMutex.withLock { dualActive }) return@launch
            val v = viewport.value?.takeIf { it.dual } ?: return@launch
            val lang = settingsRepository.preferences.first().aiTargetLang
            if (!withContext(Dispatchers.IO) { store.hasAny(bookId, lang.name) }) {
                notices.tryEmit("还没有已翻译的内容")
                return@launch
            }
            val units = loadUnits(lang)
            if (units.isEmpty()) return@launch
            val (text, translatedChapters) = withContext(Dispatchers.IO) {
                store.assemble(bookId, lang.name, units)
            }
            val config = activeConfig ?: return@launch
            bilingualUnits = units
            bilingualChapters = translatedChapters
            bilingualContent = StringBookContent(text)
            bilingualGeomKey = null // 强制 ensureBilingualPaginator 重建
            withContext(Dispatchers.Default) {
                ensureBilingualPaginator(config, dualPageWidthPx(v.leftWidthPx, v.rightWidthPx), v)
            }
            // 右侧起点对齐到左页所在单位起点：首屏即按比例跟随，而不是先追齐一次
            val (unitIndex, _) = locateUnit(units, anchorOffset.value) ?: (0 to 0f)
            bilingualRightOffset = translatedChapters.getOrNull(unitIndex)?.charStart ?: 0L
            _bilingualCompare.value = true
            relocate()
        }
    }

    /** UI 兜底退出（折叠成单页 / 切滚动模式）；未激活时是 no-op。 */
    fun setBilingualCompare(on: Boolean) {
        if (on) {
            if (!_bilingualCompare.value) toggleBilingualCompare()
        } else if (_bilingualCompare.value) {
            exitBilingualCompare(repaint = true)
        }
    }

    /** 释放右分页器现场。[repaint] = true 时按左页锚点重排一次，把右页画回原文。 */
    private fun exitBilingualCompare(repaint: Boolean) {
        bilingualPaginator = null
        bilingualContent = null
        bilingualUnits = emptyList()
        bilingualChapters = emptyList()
        bilingualRightOffset = 0L
        bilingualGeomKey = null
        _bilingualCompare.value = false
        if (repaint) viewModelScope.launch { relocate() }
    }

    /** 版式 / 字号变化后按新几何重建右分页器（指纹未变则复用）。 */
    private fun ensureBilingualPaginator(config: LayoutConfig, pageWidthPx: Int, v: SpreadViewport) {
        val key = BilingualGeomKey(pageWidthPx, v.heightPx, v.density, v.scaledDensity, config)
        if (bilingualGeomKey == key && bilingualPaginator != null) return
        val source = bilingualContent ?: return
        bilingualPaginator = buildPaginator(
            source, config, pageWidthPx, v.heightPx, v.density, v.scaledDensity,
            avoidance = com.llzx373.foldreader.core.reader.PageAvoidance(),
            sharedCache = false,
        )
        bilingualGeomKey = key
    }

    /** 视角 2 右页：按 BilingualSync 同步策略取译本页；右分页器未就绪返回 null（右页留白）。 */
    private suspend fun bilingualRightPage(leftAnchor: Long): Page? {
        val paginator = bilingualPaginator ?: return null
        val result = bilingualSyncTarget(
            bilingualUnits, bilingualChapters, leftAnchor, bilingualRightOffset,
        ) ?: return null
        val page = paginator.pageAt(result.targetOffset)
        bilingualRightOffset = page.charStart
        return page
    }

    /**
     * 双语对照下 spread 的「原始右页末端」：右页已是译本坐标，凡是需要原文跨页末端的
     * 场景（向前翻页推进、TTS 翻页联动、「翻译本页」取范围）都改从左侧分页器实算。
     */
    private suspend fun originalSpreadEnd(spread: PageSpread): Long {
        if (!_bilingualCompare.value) return spread.right?.charEnd ?: spread.left.charEnd
        val source = content ?: return spread.left.charEnd
        if (spread.left.charEnd >= source.charCount) return spread.left.charEnd
        val paginator = paginatorFor(spread.left.charEnd) ?: return spread.left.charEnd
        return withContext(Dispatchers.Default) {
            runCatching { paginator.pageAt(spread.left.charEnd).charEnd }
                .getOrDefault(spread.left.charEnd)
        }
    }

    /** UI 选区跨页 / 对照面板取范围用：双语对照下 spread 右页是译本坐标，取原文跨页末端。 */
    suspend fun originalSpreadEndFor(spread: PageSpread): Long = originalSpreadEnd(spread)

    // ---------- M20 视角 3：滚动模式段落对照 ----------

    /** 段落对照开关：true 时滚动内容区切换为 ParagraphCompareContent（段落级原/译对照）。 */
    private val _paragraphCompare = MutableStateFlow(false)
    val paragraphCompare: StateFlow<Boolean> = _paragraphCompare.asStateFlow()

    /** 进入对照时的初始单位序号（当前锚点所在单位）；UI 首帧滚动定位用。 */
    var paragraphCompareAnchorIndex: Int = 0
        private set

    /** 对照模式下的跳转事件（进度条 / 目录 / 书签 / 搜索）：值为目标单位序号。 */
    val paragraphCompareJumps = kotlinx.coroutines.flow.MutableSharedFlow<Int>(extraBufferCapacity = 1)

    /** 一个对照单位的懒加载内容：原文段 + 译文段（null = 未译）。 */
    data class ParagraphCompareUnit(
        val unit: TranslationUnit,
        val sourceParagraphs: List<String>,
        val translatedParagraphs: List<String>?,
    )

    /** 单位粒度 LRU：大书不一次性读全文，滑出远端的单位内容可被回收重载。 */
    private val paragraphCompareCache =
        object : LinkedHashMap<Int, ParagraphCompareUnit>(16, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<Int, ParagraphCompareUnit>?,
            ): Boolean = size > PARAGRAPH_COMPARE_CACHE_UNITS
        }

    /** 菜单「段落对照」。进入：校验滚动模式 + 已有译本 + 预载单位清单。 */
    fun toggleParagraphCompare() {
        if (_paragraphCompare.value) {
            setParagraphCompare(false)
            return
        }
        if (_viewMode.value != ReaderViewMode.ORIGINAL) return
        if (preferences.value.pageTurnMode != PageTurnMode.SCROLL) return
        val store = translationStore ?: return
        viewModelScope.launch {
            val lang = settingsRepository.preferences.first().aiTargetLang
            if (!withContext(Dispatchers.IO) { store.hasAny(bookId, lang.name) }) {
                notices.tryEmit("还没有已翻译的内容")
                return@launch
            }
            val units = loadUnits(lang)
            if (units.isEmpty()) {
                notices.tryEmit("章节索引完成后才能对照")
                return@launch
            }
            paragraphCompareAnchorIndex = locateUnit(units, anchorOffset.value)?.first ?: 0
            _paragraphCompare.value = true
        }
    }

    /**
     * 退出段落对照：锚点已是原文坐标（对照期间 scrollAnchorTo 写回的都是单位起点），
     * 按「单位号 + 比例」口径锚点即单位起点比例 0——直接按锚点重排并重建滚动页流。
     */
    fun setParagraphCompare(on: Boolean) {
        if (on) {
            if (!_paragraphCompare.value) toggleParagraphCompare()
            return
        }
        if (!_paragraphCompare.value) return
        _paragraphCompare.value = false
        synchronized(paragraphCompareCache) { paragraphCompareCache.clear() }
        viewModelScope.launch {
            relocate()
            if (preferences.value.pageTurnMode == PageTurnMode.SCROLL) enterScrollMode()
        }
    }

    /** 对照项内容加载（单位粒度，带 LRU 缓存）；单位号越界 / 内容未就绪返回 null。 */
    suspend fun paragraphCompareUnit(unitIndex: Int): ParagraphCompareUnit? {
        synchronized(paragraphCompareCache) { paragraphCompareCache[unitIndex] }?.let { return it }
        val unit = _translationUnits.value.getOrNull(unitIndex) ?: return null
        val source = content ?: return null
        val store = translationStore ?: return null
        val lang = settingsRepository.preferences.first().aiTargetLang
        val loaded = withContext(Dispatchers.IO) {
            val end = minOf(unit.charEnd, source.charCount)
            val text = if (end > unit.charStart) {
                runCatching { source.read(unit.charStart until end) }.getOrDefault("")
            } else {
                ""
            }
            ParagraphCompareUnit(
                unit = unit,
                sourceParagraphs = splitIntoParagraphs(text),
                translatedParagraphs = store.loadUnit(bookId, lang.name, unitIndex)?.paragraphs,
            )
        }
        synchronized(paragraphCompareCache) { paragraphCompareCache[unitIndex] = loaded }
        return loaded
    }

    // ---------- M19 翻译本页（对照面板流式展示，不落盘） ----------

    data class PageTranslateState(
        /** true = 确认页（范围/预估/语言/临时提示词）；false = 对照面板。 */
        val confirming: Boolean = true,
        val start: Long = 0,
        val end: Long = 0,
        val lang: AiTargetLang = AiTargetLang.ZH_HANS,
        val sourceParagraphs: List<String> = emptyList(),
        /** 已闭合的译文段落（流式增量经 PartialJsonArray 提取），与 sourceParagraphs 对齐。 */
        val translated: List<String> = emptyList(),
        val running: Boolean = false,
        val error: String? = null,
    )

    private val _pageTranslateState = MutableStateFlow<PageTranslateState?>(null)
    val pageTranslateState: StateFlow<PageTranslateState?> = _pageTranslateState.asStateFlow()
    private var pageTranslateJob: kotlinx.coroutines.Job? = null
    private var pageTranslateOverride: String? = null

    /** 菜单「翻译本页」：取当前页（双页 = 当前跨页）范围，弹确认页。 */
    fun requestPageTranslation() {
        if (_viewMode.value != ReaderViewMode.ORIGINAL) return
        val spread = _uiState.value.spread ?: return
        viewModelScope.launch {
            // 双页对照下右页是译本坐标，跨页末端从左侧分页器实算
            val start = spread.left.charStart
            val end = originalSpreadEnd(spread)
            if (end <= start) return@launch
            val lang = settingsRepository.preferences.first().aiTargetLang
            _pageTranslateState.value =
                PageTranslateState(confirming = true, start = start, end = end, lang = lang)
        }
    }

    /** 确认页「开始翻译」：[systemOverride] 为当次临时提示词（与内置模板相同时调用方传 null）。 */
    fun startPageTranslation(lang: AiTargetLang, systemOverride: String?) {
        val st = _pageTranslateState.value ?: return
        pageTranslateJob?.cancel()
        pageTranslateOverride = systemOverride
        pageTranslateJob = viewModelScope.launch {
            val engine = translateEngine()
            if (engine == null) {
                _pageTranslateState.value =
                    st.copy(confirming = false, error = "请先在设置中完成 AI 服务配置")
                return@launch
            }
            val source = content ?: return@launch
            val text = withContext(Dispatchers.IO) {
                runCatching { source.read(st.start until minOf(st.end, source.charCount)) }
                    .getOrDefault("")
            }
            val paragraphs = splitIntoParagraphs(text)
            if (paragraphs.isEmpty()) {
                _pageTranslateState.value =
                    st.copy(confirming = false, lang = lang, error = "当前页没有可翻译的文字")
                return@launch
            }
            // 外发台账：范围 = 当前页文本，估算口径同其他功能（字符数 / 2）
            recordOutbound("按页翻译", _uiState.value.bookTitle, text.length / 2)
            _pageTranslateState.value = PageTranslateState(
                confirming = false,
                start = st.start,
                end = st.end,
                lang = lang,
                sourceParagraphs = paragraphs,
                running = true,
            )
            val raw = StringBuilder()
            try {
                engine.translateTextStream(text, lang, pageTranslateOverride).collect { delta ->
                    raw.append(delta)
                    _pageTranslateState.update {
                        it?.copy(
                            translated = com.llzx373.foldreader.core.ai.prompt.PartialJsonArray
                                .extractCompleteStrings(raw.toString()),
                        )
                    }
                }
                // 收尾校验：译文段数必须与原文一致（不一致按失败处理，可重试）
                val parsed = com.llzx373.foldreader.core.ai.prompt.UnitTranslatePrompt
                    .parseParagraphs(raw.toString(), paragraphs.size)
                if (parsed == null) {
                    _pageTranslateState.update {
                        it?.copy(running = false, error = "模型输出段落数与原文不一致，请重试")
                    }
                } else {
                    _pageTranslateState.update { it?.copy(translated = parsed, running = false) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AiException) {
                _pageTranslateState.update { it?.copy(running = false, error = e.message ?: "翻译失败") }
            } catch (e: Exception) {
                _pageTranslateState.update { it?.copy(running = false, error = "网络不可达或响应异常") }
            }
        }
    }

    fun retryPageTranslation() {
        val st = _pageTranslateState.value ?: return
        startPageTranslation(st.lang, pageTranslateOverride)
    }

    fun closePageTranslation() {
        pageTranslateJob?.cancel()
        pageTranslateJob = null
        _pageTranslateState.value = null
    }

    // ---------- M19 翻译本章 / 本节（后台，段落结构化落盘） ----------

    private var unitTranslateJob: kotlinx.coroutines.Job? = null
    private val _unitTranslateRunning = MutableStateFlow(false)
    val unitTranslateRunning: StateFlow<Boolean> = _unitTranslateRunning.asStateFlow()

    /** 菜单「翻译本章/本节」：当前阅读位置所在单位，后台翻译；完成后即可切译文查看。 */
    fun translateCurrentUnit() {
        if (_viewMode.value != ReaderViewMode.ORIGINAL) return
        if (unitTranslateJob?.isActive == true) return
        unitTranslateJob = viewModelScope.launch {
            val engine = translateEngine()
            val store = translationStore
            if (engine == null || store == null) {
                notices.tryEmit("请先在设置中完成 AI 服务配置")
                return@launch
            }
            val source = content ?: return@launch
            if (!source.isCharCountFinal) {
                notices.tryEmit("章节索引完成后才能翻译")
                return@launch
            }
            val lang = settingsRepository.preferences.first().aiTargetLang
            val units = loadUnits(lang)
            if (units.isEmpty()) return@launch
            // 清单落盘：固定切块边界，后续各单位的 saveUnit 据此重写译本流（含未译占位）
            withContext(Dispatchers.IO) { store.saveUnits(bookId, lang.name, units) }
            val (unitIndex, _) = locateUnit(units, anchorOffset.value) ?: return@launch
            _unitTranslateRunning.value = true
            try {
                translateOneUnit(engine, units, unitIndex, lang)
            } finally {
                _unitTranslateRunning.value = false
            }
        }
    }

    /**
     * 目录行「重译」：只作废该章覆盖到的已译 / 失败单位后逐个重跑，
     * 其他章与其他单位不受影响（R12 单位是翻译与对齐的最小粒度）。
     */
    fun retranslateChapter(chapterIndex: Int) {
        if (_viewMode.value != ReaderViewMode.ORIGINAL) return
        if (unitTranslateJob?.isActive == true) return
        val chapter = chapterList().getOrNull(chapterIndex) ?: return
        unitTranslateJob = viewModelScope.launch {
            val engine = translateEngine() ?: return@launch
            val store = translationStore ?: return@launch
            val lang = settingsRepository.preferences.first().aiTargetLang
            val units = loadUnits(lang)
            val statusByUnit = unitStatuses.value.associateBy({ it.unitIndex }, { it.status })
            val targets = com.llzx373.foldreader.feature.translate.unitsOfChapter(chapter, units)
                .filter {
                    val status = statusByUnit[it.index]
                    status == com.llzx373.foldreader.core.data.db.TranslationEntity.STATUS_DONE ||
                        status == com.llzx373.foldreader.core.data.db.TranslationEntity.STATUS_FAILED
                }
            if (targets.isEmpty()) return@launch
            _unitTranslateRunning.value = true
            try {
                for (unit in targets) {
                    withContext(Dispatchers.IO) { store.deleteUnit(bookId, lang.name, unit.index) }
                    translationDao?.deleteUnit(bookId, lang.name, unit.index)
                    translateOneUnit(engine, units, unit.index, lang)
                }
            } finally {
                _unitTranslateRunning.value = false
            }
        }
    }

    private suspend fun translateOneUnit(
        engine: com.llzx373.foldreader.feature.translate.TranslateEngine,
        units: List<TranslationUnit>,
        unitIndex: Int,
        lang: AiTargetLang,
    ) {
        val unit = units.getOrNull(unitIndex) ?: return
        val source = content ?: return
        val text = withContext(Dispatchers.IO) {
            runCatching { source.read(unit.charStart until unit.charEnd) }.getOrDefault("")
        }
        engine.translateUnit(
            bookId = bookId,
            bookTitle = _uiState.value.bookTitle,
            unit = unit,
            unitText = text,
            lang = lang,
        ).onSuccess {
            // 段落对照可能正开着：该单位译文变了，缓存作废下次懒加载重读
            synchronized(paragraphCompareCache) { paragraphCompareCache.remove(unitIndex) }
            notices.tryEmit("「${unit.title}」翻译完成")
        }.onFailure {
            notices.tryEmit("「${unit.title}」翻译失败：${it.message ?: "未知错误"}")
        }
    }

    /**
     * R5 插队：全书翻译进行中翻到未译单位时，把它提前为队列的下一个处理。
     * 只在「该书在队列中 + 原文模式」时触发——不在队列时阅读侧不主动发起翻译
     * （未确认的外发不能由翻页隐式触发），直译路径留给显式调用方。
     */
    private suspend fun maybeJumpTranslationQueue(offset: Long) {
        val queue = translationQueue ?: return
        if (_viewMode.value != ReaderViewMode.ORIGINAL) return // 译文坐标系不是单位坐标
        if (!queue.isActive(bookId)) return
        val lang = settingsRepository.preferences.first().aiTargetLang
        val units = loadUnits(lang)
        if (units.isEmpty()) return
        val (unitIndex, _) = locateUnit(units, offset) ?: return
        val status = unitStatuses.value.firstOrNull { it.unitIndex == unitIndex }?.status
        if (status != com.llzx373.foldreader.core.data.db.TranslationEntity.STATUS_DONE) {
            queue.jumpQueue(bookId, unitIndex)
        }
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
            // 章节随数据库被动更新：首次打开时表为空，后台索引扫完落库后这里自动刷新。
            // 译文模式下内存里挂的是译本章节——只暂存，切回原文时再恢复。
            bookshelfRepository.observeChapters(bookId).collect { list ->
                if (_viewMode.value == ReaderViewMode.TRANSLATED) {
                    pendingOriginalChapters = list
                } else {
                    chapters = list
                    refreshChapterState()
                }
            }
        }
        viewModelScope.launch {
            pendingSave.filterNotNull().debounce(500L).collect { offset ->
                runCatching { persistProgress(offset) }
                runCatching { maybeJumpTranslationQueue(offset) }
            }
        }
        viewModelScope.launch {
            // 盘上的正文有两个可变因素：编码（决定怎么解码）与清洗副本路径（决定读哪一份文件）。
            // 任一变化都说明内存里这一份已经过期——「智能整理」重洗完就属于后者，必须重开。
            bookshelfRepository.observeBook(bookId)
                .map { it?.encoding.orEmpty() to it?.cleanedFilePath }
                .distinctUntilChanged()
                // 智能整理是连写两行（先副本路径、再编码）。不合并的话会重开两次，
                // 头一次用的还是没更新完的编码，正文会闪一下乱码。等它写完再开。
                .debounce(REOPEN_SETTLE_MS)
                .collect { (encoding, cleanedPath) ->
                    if (appliedEncoding != null &&
                        (encoding != appliedEncoding || cleanedPath != appliedCleanedPath)
                    ) {
                        // 译文模式下原文被换（智能整理等）：先退回原文再重开——
                        // 译文锚点是另一套坐标，带不到重开后的原文上
                        if (_viewMode.value == ReaderViewMode.TRANSLATED) {
                            restoreOriginalContent()
                            anchorOffset.value =
                                runCatching { bookshelfRepository.getProgress(bookId)?.charOffset }
                                    .getOrNull() ?: 0L
                        }
                        reopenContent()
                    }
                }
        }
        viewModelScope.launch { autoPageLoop() }
        viewModelScope.launch { followTtsPlayback() }
    }

    /**
     * TTS 翻页联动（M13.1）：朗读进度（下一句起点的全书偏移）越过当前跨页末尾时翻页。
     * 只超一页发翻页请求走 UI 动画；落后多页（长句停顿、翻页被动画挡住）直接 seek
     * 到偏移所在跨页，避免连翻动画。滚动翻页模式下不做页面跟随（本步口径）。
     */
    private suspend fun followTtsPlayback() {
        ttsController.state.collect { state ->
            // paused 时不触发翻页联动：会话还在但朗读进度冻结
            if (!state.playing || state.paused || state.bookId != bookId) return@collect
            if (preferences.value.pageTurnMode == PageTurnMode.SCROLL) return@collect
            val spread = _uiState.value.spread ?: return@collect
            val spreadEnd = originalSpreadEnd(spread)
            if (state.charOffset < spreadEnd) return@collect
            val next = adjacentSpread(true)
            if (next != null && state.charOffset < originalSpreadEnd(next)) {
                ttsTurnRequests.request(forward = true)
            } else if (next != null) {
                seekToOffset(state.charOffset)
            }
        }
    }

    /** 「从当前位置朗读」：当前锚点 → 当前章末（无章节坐标时到全书末）。 */
    suspend fun speakFromHere() {
        val source = content ?: return
        val start = anchorOffset.value
        val end = chapters.getOrNull(chapterIndexAt(chapters, start))
            ?.charEnd
            ?.takeIf { it > start }
            ?: source.charCount
        speakRange(start, end)
    }

    /** 「朗读本章」：当前章 charStart..charEnd（无章节坐标时整本）。 */
    suspend fun speakChapter() {
        val source = content ?: return
        val chapter = chapters.getOrNull(_readingPosition.value.chapterIndex)
        val start = chapter?.charStart ?: 0L
        val end = chapter?.charEnd?.takeIf { it > start } ?: source.charCount
        speakRange(start, end)
    }

    fun stopSpeaking() = ttsController.stop()

    private suspend fun speakRange(start: Long, end: Long) {
        val source = content ?: return
        if (end <= start) return
        val text = runCatching { source.read(start until end) }.getOrNull() ?: return
        val segments = TtsSentenceSplitter.split(text, start)
        ttsController.speak(
            bookId = bookId,
            segments = segments,
            bookTitle = _uiState.value.bookTitle,
            chapterTitle = chapters.getOrNull(chapterIndexAt(chapters, start))?.title.orEmpty(),
        )
    }

    fun pauseSpeaking() = ttsController.pause()

    fun resumeSpeaking() = ttsController.resume()

    /**
     * 重开正文：编码变了（换解码方式）或清洗副本换了（智能整理/撤销清理）时调用。
     * 保留当前阅读位置，清掉分页器与章节，重新走一次 [openBook]。
     */
    private fun reopenContent() {
        pendingReopenAnchor = anchorOffset.value
        // 正文被换后单位清单与译本流坐标都可能失效，对照模式一律退出重进
        if (_bilingualCompare.value) exitBilingualCompare(repaint = false)
        if (_paragraphCompare.value) {
            _paragraphCompare.value = false
            synchronized(paragraphCompareCache) { paragraphCompareCache.clear() }
        }
        runCatching { (content as? java.io.Closeable)?.close() }
        content = null
        paginatorStore.remove(bookId)
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
            // 防御性断言：漫画由 ComicReaderViewModel 承担，路由分流见 ReaderHost。
            // 真走到这里说明分发出了问题——明确报错，而不是把压缩包当文本解出满屏乱码。
            if (book.format == BookFormat.COMIC) {
                _uiState.update { it.copy(loading = false, error = "漫画请用漫画阅读器打开") }
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
                    parser.openContent(uri, charsetOverride, bookId)
                }
                content = opened
                appliedEncoding = book.encoding
                appliedCleanedPath = book.cleanedFilePath
                // 能走到这里就说明压平产物已就绪（本次压平或命中缓存）：回写标记，书架角标随之消失。
                // 预热失败或进程中途被杀时，这条就是兜底。
                if (book.format != BookFormat.TXT) {
                    runCatching { bookshelfRepository.markContentPrepared(bookId) }
                }
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
                maybeScanChaptersInBackground(
                    uri = uri,
                    charsetOverride = charsetOverride,
                    opened = opened,
                    parser = parser,
                    cheapChapterScan = book.format != BookFormat.TXT,
                )
                watchLiveIndexCompletion(opened)
                collectViewport()
            } catch (t: Throwable) {
                _uiState.update { it.copy(loading = false, error = t.message ?: "打开失败") }
            }
        }
    }

    /**
     * 兜底补扫章节：章节表为空说明此前那次回填没落库（写库异常曾被静默吞掉）。
     * 首帧上屏后闲时补扫一次，让「没有目录」在**本次打开内**就自愈。
     */
    private fun maybeScanChaptersInBackground(
        uri: Uri,
        charsetOverride: java.nio.charset.Charset?,
        opened: BookContent,
        parser: BookParser,
        /** EPUB/FB2 的 parseChapters 只读 .toc sidecar（便宜）；TXT 是一次全量索引扫描（贵）。 */
        cheapChapterScan: Boolean,
    ) {
        val liveIndexing = (opened as? com.llzx373.foldreader.core.format.txt.TxtBookContent)
            ?.indexProgress != null
        if (!shouldScanChaptersInBackground(liveIndexing, cheapChapterScan)) return
        viewModelScope.launch(Dispatchers.IO) {
            firstFrameRendered.filter { it }.first()
            if (chapters.isNotEmpty()) return@launch
            val scanned = runCatching { parser.parseChapters(uri, charsetOverride, bookId) }
                .onFailure { DiagnosticLog.line("章节补扫失败 bookId=$bookId: ${it.message}") }
                .getOrNull() ?: return@launch
            if (chapters.isEmpty()) {
                runCatching { bookshelfRepository.saveChapters(bookId, scanned) }
                    .onFailure { DiagnosticLog.line("章节补写失败 bookId=$bookId: ${it.message}") }
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

    /**
     * 双模式进度独立：译文模式只写 translationAnchor，原文锚点列原样保留
     * （两套坐标系，绝不互相顶替）；原文模式照常更新 charOffset 并保留译文锚点。
     */
    private fun progressForSave(offset: Long, nowMs: Long, existing: ReadingProgressEntity?): ReadingProgressEntity {
        val base = buildProgress(offset, nowMs)
        if (_viewMode.value != ReaderViewMode.TRANSLATED) {
            // 文本与页式（页式 PDF）共用一行进度，整行 REPLACE 保存前先把页式锚点带过来
            // （keepPagedAnchor 同时把译文锚点也带过来）
            return base.keepPagedAnchor(existing)
        }
        // keepTranslationAnchor 把旧的文本锚点与页式锚点带过来；从未有过原文进度时锚点归零
        val withAnchor = base.copy(translationAnchor = offset)
        return if (existing == null) {
            withAnchor.copy(charOffset = 0L, chapterIndex = 0)
        } else {
            withAnchor.keepTranslationAnchor(existing)
        }
    }

    private suspend fun persistProgress(offset: Long) {
        val nowMs = System.currentTimeMillis()
        val existing = bookshelfRepository.getProgress(bookId)
        bookshelfRepository.saveProgress(progressForSave(offset, nowMs, existing))
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
                        a.autoIndentEnabled == b.autoIndentEnabled &&
                        a.normalizeWhitespaceEnabled == b.normalizeWhitespaceEnabled
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
                val wasScrolling = scrollPages.isNotEmpty()
                val anchor = anchorOffset.value
                val t0 = System.nanoTime()
                val prepared = withContext(Dispatchers.Default) {
                    // 译文模式不走共享 PageCache / 磁盘页边界：与原文同 bookId，页边界会互撞
                    val useSharedCache = _viewMode.value == ReaderViewMode.ORIGINAL
                    val exact = buildPaginator(
                        source, config, pageWidthPx, v.heightPx, v.density, v.scaledDensity, v.avoidance,
                        sharedCache = useSharedCache,
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
                    // 视角 2：折叠成单页即退出对照；仍双页时按新版式确保右分页器就绪
                    if (_bilingualCompare.value) {
                        if (!v.dual) {
                            exitBilingualCompare(repaint = false)
                        } else {
                            ensureBilingualPaginator(config, pageWidthPx, v)
                        }
                    }
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
            normalizeWhitespaceEnabled = p.normalizeWhitespaceEnabled,
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
        val spread = withContext(Dispatchers.Default) { spreadFrom(exact, dual, anchorOffset.value) }
        // 本函数在 scheduleBoundsBuild 的 Default 线程上下文里被调用，而 showSpread / enterScrollMode
        // 要写快照列表（scrollPages）与阅读统计（charsReadTracker），二者都只在主线程碰：回主线程再落
        withContext(Dispatchers.Main) {
            showSpread(spread, countCharsRead = false)
            if (scrollPages.isNotEmpty()) enterScrollMode()
        }
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
        val rightPage = if (dual && _bilingualCompare.value) {
            // 视角 2：右页改由译本分页器按 BilingualSync 同步产出（未就绪时右页留白）
            bilingualRightPage(anchor)
        } else if (dual && leftPage.charEnd < total) {
            paginator.pageAt(leftPage.charEnd).takeIf { it.charEnd > it.charStart }
        } else {
            null
        }
        // span 渲染期挂载（不进任何分页缓存）+ 图片位图预取（IO，未就绪则画占位灰框）。
        // 双页对照的右页是译本坐标系：不挂原文 span（挂载必错位），也无图片可预取
        val bilingualRight = dual && _bilingualCompare.value
        val left = attachSpans(leftPage)
        val right = rightPage?.let { if (bilingualRight) it else attachSpans(it) }
        prefetchImages(left)
        if (!bilingualRight) right?.let { prefetchImages(it) }
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
            forward && dual && _bilingualCompare.value -> {
                // 视角 2：右页是译本坐标，不能用它推进——原文下一跨页锚点从左侧分页器实算
                if (current.left.charEnd >= total) return null
                val origRightEnd = originalSpreadEnd(current)
                if (origRightEnd <= current.left.charEnd) return null
                origRightEnd
            }
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
        // 段落对照模式：页流不在屏上，跳转 = 锚点落账 + 通知对照列表滚到目标单位
        if (_paragraphCompare.value) {
            val source = content ?: return
            val target = offset.coerceIn(0L, source.charCount)
            charsReadTracker.jump(target)
            anchorOffset.value = target
            trackSpeed(target)
            pendingSave.value = target
            _readingPosition.value = positionAt(target, _uiState.value.totalChars)
            val index = locateUnit(_translationUnits.value, target)?.first ?: 0
            paragraphCompareJumps.emit(index)
            return
        }
        currentSpreadFrom(offset)?.let { showSpread(it) }
    }

    suspend fun relocate() {
        seekToOffset(anchorOffset.value)
    }

    fun enterScrollMode() {
        if (_paragraphCompare.value) return // 对照列表自持滚动位置，普通页流等退出时再重建
        val current = _uiState.value.spread ?: return
        charsReadTracker.jump(current.left.charStart)
        scrollPages.clear()
        scrollPages.add(current.left)
    }

    /**
     * 到边后按 [SCROLL_PREFETCH_PAGES] 页成批取：原来一次只补一页，快速滑动时
     * 常出现「已经滑到列表尾、下一页还没排出来」的空窗。
     * 取出的页先入列表，图片解码再异步补（未就绪时该行画占位灰框）。
     */
    suspend fun scrollExtend(forward: Boolean) {
        val count = scrollPages.size
        if (count == 0) return
        val edge = if (forward) scrollPages[count - 1].charStart else scrollPages[0].charStart - 1
        val p = paginatorFor(edge) ?: return
        val fetched = withContext(Dispatchers.Default) {
            collectScrollPages(
                startCursor = edge,
                limit = SCROLL_PREFETCH_PAGES,
                next = { cursor -> if (forward) p.pageAfter(cursor) else p.pageBefore(cursor) },
                advance = { page -> if (forward) page.charEnd else page.charStart - 1 },
            )
        }
        if (fetched.isEmpty()) return
        val attached = fetched.map { attachSpans(it) }
        if (forward) {
            scrollPages.addAll(attached)
        } else {
            // 向前取到的顺序是「由近及远」，前插要倒过来才是正确阅读顺序
            scrollPages.addAll(0, attached.asReversed())
        }
        attached.forEach { prefetchImages(it) }
    }

    fun scrollAnchorTo(charStart: Long) {
        anchorOffset.value = charStart
        charsReadTracker.advance(charStart)
        trackSpeed(charStart)
        pendingSave.value = charStart
        // 只更新阅读位置流：写 uiState 会让整棵阅读树每跨一页重组一次
        _readingPosition.value = positionAt(charStart, _uiState.value.totalChars)
    }

    /**
     * 按锚点算出当前位置展示态。chapterIndexAt 只算一次——
     * 原实现同一次更新里对同一锚点重复调用 3~4 次。
     */
    private fun positionAt(anchor: Long, totalChars: Long): ReadingPosition {
        val chapterIndex = chapterIndexAt(chapters, anchor)
        return ReadingPosition(
            progressFraction = progressPercentOf(anchor, totalChars),
            chapterTitle = chapters.getOrNull(chapterIndex)?.title.orEmpty(),
            chapterIndex = chapterIndex,
            chapterCount = chapters.size,
            pageNumber = (paginatorLeft?.pageIndexOf(anchor) ?: -1) + 1,
            inChapterFraction = inChapterFraction(chapters, chapterIndex, anchor),
            paperPageLabel = paperPageLabels?.let { pageLabelAt(it, anchor)?.label },
        )
    }

    suspend fun chapterAt(index: Int): Chapter? = chapters.getOrNull(index)

    suspend fun seekChapter(delta: Int) {
        if (chapters.isEmpty()) return
        val idx = (_readingPosition.value.chapterIndex + delta).coerceIn(0, chapters.lastIndex)
        chapters.getOrNull(idx)?.let { seekToOffset(it.charStart) }
    }

    fun chapterList(): List<Chapter> = chapters

    fun setPageTurnMode(mode: PageTurnMode) {
        viewModelScope.launch {
            bookPrefsRepository.update(bookId) {
                it.copy(pageTurnMode = mode.name)
            }
        }
    }

    /** 双页模式是应用级偏好（设置页也有）：写全局，否则设置页改了这里不生效。 */
    fun setDualPageMode(mode: DualPageMode) {
        viewModelScope.launch { settingsRepository.setDualPageMode(mode) }
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

    fun setNormalizeWhitespaceEnabled(enabled: Boolean) {
        viewModelScope.launch {
            bookPrefsRepository.update(bookId) { it.copy(normalizeWhitespaceEnabled = enabled) }
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

    /** 章节表更新后按当前位置刷新章节标题/序号/章内进度（页码等由分页器算出的值保持不变）。 */
    private fun refreshChapterState() {
        val anchor = _uiState.value.spread?.left?.charStart ?: anchorOffset.value
        val chapterIndex = chapterIndexAt(chapters, anchor)
        _readingPosition.update {
            it.copy(
                chapterTitle = chapters.getOrNull(chapterIndex)?.title.orEmpty(),
                chapterIndex = chapterIndex,
                chapterCount = chapters.size,
                inChapterFraction = inChapterFraction(chapters, chapterIndex, anchor),
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
            )
        }
        _readingPosition.value = positionAt(spread.left.charStart, _uiState.value.totalChars)
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
                    val existing = bookshelfRepository.getProgress(bookId)
                    bookshelfRepository.saveProgress(progressForSave(offset, nowMs, existing))
                }
                bookshelfRepository.touchLastRead(bookId, nowMs)
                flushReadingSession(nowMs)
            }
            runCatching { exact?.persistBounds() }
            runCatching { active?.persistBounds() }
            // 关闭内容源，连带取消未完成的后台索引构建
            runCatching { (content as? java.io.Closeable)?.close() }
            content = null
            // 译文模式下原文源被暂存在 originalContent，也要一并关掉
            runCatching { (originalContent as? java.io.Closeable)?.close() }
            originalContent = null
        }
    }

    companion object {
        /** 锚点深于该字数且页边界缓存未覆盖时，才启用段首播种起排（浅位置从 0 排足够快）。 */
        private const val SEED_MIN_ANCHOR_CHARS = 30_000L

        /** 正文源变化后的合并窗口：智能整理连写「副本路径 + 编码」两行，等它写完再重开一次。 */
        private const val REOPEN_SETTLE_MS = 100L

        /** 首帧上屏后再延迟该时长，才启动全书后台分页，避开首次翻页。 */
        private const val FULL_BOUNDS_IDLE_DELAY_MS = 400L

        /** 后台每多排多少页就把页边界增量落盘一次。 */
        private const val INCREMENTAL_PERSIST_PAGES = 64

        /** 滚动模式到边时一次预取的页数：覆盖快速滑动的视口推进速度。 */
        private const val SCROLL_PREFETCH_PAGES = 5

        /** 段落对照单位内容 LRU 容量（单位数）。 */
        private const val PARAGRAPH_COMPARE_CACHE_UNITS = 24

        /** 内嵌图片位图 LRU 容量（字节数）。 */
        private const val IMAGE_CACHE_BYTES = 16 * 1024 * 1024

        /** 弹注读取窗口：远大于截取上限，保证段落过滤后仍有足够内容。 */
        private const val NOTE_EXCERPT_READ_CHARS = 2_000L

        /** 选中即译「存为批注」的默认高亮色（标注色板第一个，黄）。 */
        private const val TRANSLATION_NOTE_COLOR = 0xFFFFF176L

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
                    settingsRepository = container.settingsRepository,
                    parsers = container.bookParsers,
                    fontManager = container.fontManager,
                    pageDiskCache = container.pageDiskCache,
                    ttsController = container.ttsController,
                    initialAnchor = initialAnchor,
                    aiProvider = container::aiProvider,
                    recordOutbound = container.aiContentGate::record,
                    translationStore = container.translationStore,
                    translationDao = container.database.translationDao(),
                    translateEngine = { container.translateEngine() },
                    translationQueue = container.bookTranslationQueue,
                )
            }
        }
    }
}

package com.llzx373.foldreader.feature.comic

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.SystemClock
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.llzx373.foldreader.AppContainer
import com.llzx373.foldreader.core.comic.ComicArchive
import com.llzx373.foldreader.core.comic.ComicArchiveFactory
import com.llzx373.foldreader.core.comic.ComicContainer
import com.llzx373.foldreader.core.comic.ComicExtractionStore
import com.llzx373.foldreader.core.comic.ComicImageDecoder
import com.llzx373.foldreader.core.comic.ComicSeriesCandidate
import com.llzx373.foldreader.core.format.Chapter
import com.llzx373.foldreader.core.paged.PagedImageSource
import com.llzx373.foldreader.core.paged.PagedPageImage
import com.llzx373.foldreader.core.paged.PagedReaderFeatures
import com.llzx373.foldreader.core.paged.PagedSearchHit
import com.llzx373.foldreader.core.paged.PagedSourcePasswordRequired
import com.llzx373.foldreader.core.data.db.AnnotationEntity
import com.llzx373.foldreader.core.data.db.BookEntity
import com.llzx373.foldreader.core.data.db.BookFormat
import com.llzx373.foldreader.core.data.db.BookmarkEntity
import com.llzx373.foldreader.core.data.db.ReadingProgressEntity
import com.llzx373.foldreader.core.data.repository.BookPrefsRepository
import com.llzx373.foldreader.core.data.repository.BookshelfRepository
import com.llzx373.foldreader.core.data.settings.AutoPageMode
import com.llzx373.foldreader.core.data.settings.ComicDirection
import com.llzx373.foldreader.core.data.settings.ComicFitMode
import com.llzx373.foldreader.core.data.settings.ReadingPreferences
import com.llzx373.foldreader.core.data.settings.ReadingTheme
import com.llzx373.foldreader.core.data.settings.enumOrDefault
import com.llzx373.foldreader.core.data.settings.PdfReadingMode
import com.llzx373.foldreader.core.data.settings.PageTurnMode
import com.llzx373.foldreader.core.data.settings.SettingsRepository
import com.llzx373.foldreader.core.reader.dayStartMs
import com.llzx373.foldreader.feature.reader.AutoPageClock
import com.llzx373.foldreader.feature.reader.AutoPageTurnRequests
import com.llzx373.foldreader.feature.reader.ReadingTimer
import com.llzx373.foldreader.feature.reader.findPageBookmarkAt
import com.llzx373.foldreader.feature.reader.nextAutoPageIntervalSec
import com.llzx373.foldreader.feature.reader.nextAutoPageMode
import com.llzx373.foldreader.feature.reader.nextAutoPageSpeedPx
import com.llzx373.foldreader.feature.reader.pageLabelOf
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Immutable
data class ComicUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val bookTitle: String = "",
    val container: ComicContainer? = null,
    val pageCount: Int = 0,
    val pageIndex: Int = 0,
    val dual: Boolean = false,
    /** 加密文档等待输入密码：界面弹密码框而不是显示错误。 */
    val passwordRequired: Boolean = false,
    /** 文件大小（MB）：超过阈值时菜单里给一句性能提示。 */
    val largeFileMb: Int? = null,
)

@Immutable
data class ComicReadingPosition(
    val progressFraction: Float = 0f,
    val pageNumber: Int = 0,
    val totalPages: Int = 0,
)

/** 页式搜索的运行状态（关键词、是否在搜、结果）。 */
@Immutable
data class ComicSearchState(
    val query: String = "",
    val running: Boolean = false,
    val hits: List<PagedSearchHit> = emptyList(),
)

class ComicReaderViewModel(
    private val bookId: Long,
    private val bookshelfRepository: BookshelfRepository,
    private val bookPrefsRepository: BookPrefsRepository,
    /** 应用级偏好（常亮/跨页配对/滚动页间距）直写全局：这些项不随书独立演化。 */
    private val settingsRepository: SettingsRepository,
    /** 打开内容来源时用；漫画走容器，PDF 走沙箱文档。 */
    private val openSource: suspend (BookEntity, String?) -> PagedImageSource,
    private val extractionStore: ComicExtractionStore,
    /** 同系列候选（同目录优先、库内兜底）；纯匹配在 core.comic.ComicSeriesMatch。 */
    private val seriesCandidates: suspend (BookEntity) -> List<ComicSeriesCandidate> = { emptyList() },
    /** 按需导入同系列里的一个未入库文件，返回书籍 id。 */
    private val importSeriesEntry: suspend (Uri, Boolean) -> Long? = { _, _ -> null },
    /** 文件大小（MB）：只用于超大文档的性能提示。 */
    private val fileSizeMb: suspend (BookEntity) -> Int? = { null },
    private val initialPage: Int = -1,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ComicUiState())
    val uiState: StateFlow<ComicUiState> = _uiState.asStateFlow()

    private val _readingPosition = MutableStateFlow(ComicReadingPosition())
    val readingPosition: StateFlow<ComicReadingPosition> = _readingPosition.asStateFlow()

    /** 已解码的页。用 SnapshotStateMap 让解码完成自动触发重组，省掉手工拷贝整张表。 */
    val images: SnapshotStateMap<Int, PagedPageImage> = mutableStateMapOf()

    /** 解不出来的页（损坏页 / 不支持的编码）：界面据此显示「无法显示此页」而不是一直转圈。 */
    val failedPages: SnapshotStateMap<Int, Boolean> = mutableStateMapOf()

    /** 缩略图（网格跳转面板用）。小图单独一张表，不占正文页的内存预算。 */
    val thumbnails: SnapshotStateMap<Int, ImageBitmap> = mutableStateMapOf()

    private val _preferencesLoaded = MutableStateFlow(false)

    /** 首帧前偏好未就绪时不要按默认值渲染，避免闪一下错误的背景色/翻页方式。 */
    val preferencesLoaded: StateFlow<Boolean> = _preferencesLoaded.asStateFlow()

    // 就绪标记挂在上游的首次真实发射上：stateIn 之后 first() 只会立刻拿到默认值
    val preferences: StateFlow<ReadingPreferences> = bookPrefsRepository.observe(bookId)
        .onEach { _preferencesLoaded.value = true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ReadingPreferences())

    private var source: PagedImageSource? = null
    private var contentHash: String? = null
    private var imageBytes = 0
    private var prefetchJob: Job? = null
    private var sizeProbeJob: Job? = null
    private val decodeMutex = Mutex()
    private val thumbnailLoading = mutableSetOf<Int>()
    private val thumbnailOrder = ArrayDeque<Int>()

    /**
     * 缩略图的登记与淘汰锁。
     *
     * 网格快速滚动时每个进入视口的条目都会起一个 IO 协程回填，`thumbnailLoading` /
     * `thumbnailOrder` 都是普通集合：并发 `addLast` 会撞坏 ArrayDeque（`removeFirst` 抛
     * NoSuchElementException / 扩容期下标越界），所以整段登记 + 写表 + 淘汰都放进这把锁。
     */
    private val thumbnailLock = Any()

    private val _spreadIndex = MutableStateFlow(ComicSpreadIndex.EMPTY)
    private val _features = MutableStateFlow(PagedReaderFeatures())

    /** 当前格式的能力差异（PDF 没有左右方向 / 跨页配对 / 无缝拼接）。菜单据此隐藏开关。 */
    val features: StateFlow<PagedReaderFeatures> = _features.asStateFlow()

    /**
     * 文档内嵌目录（PDF 由 PdfBox 预热时写入 chapters，锚点是 pageIndex）。
     * 漫画没有这一步，恒为空列表——面板与入口都据此隐藏。
     */
    val outline: StateFlow<List<Chapter>> = bookshelfRepository.observeChapters(bookId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(PANEL_SUBSCRIBE_MS), emptyList())

    /** 当前页落在目录的第几项：最后一个 `pageIndex <= page` 的项（没有则 -1）。 */
    fun outlineIndexFor(page: Int): Int = outline.value.indexOfLast {
        val anchor = it.pageIndex ?: return@indexOfLast false
        anchor <= page
    }

    /** 点目录跳页。目录项没有解析出页序号（坏目标）时保持原地，不做任何事。 */
    fun jumpToOutline(index: Int) {
        val anchor = outline.value.getOrNull(index)?.pageIndex ?: return
        goToPage(anchor.toInt())
    }

    /** 当前跨页配对（含封面单独 / 宽图独占整宽的错位）。 */
    val spreadIndex: StateFlow<ComicSpreadIndex> = _spreadIndex.asStateFlow()

    /**
     * 页式锚点：书签与高亮。
     *
     * 坐标是「页序号 + 归一化页内坐标（0..1）」，与文本模式的字符偏移是两套**不可互换**
     * 的锚点，所以这里只取 `pageIndex != null` 的那些；文本锚点归文本阅读器渲染。
     */
    val bookmarks: StateFlow<List<BookmarkEntity>> = bookshelfRepository.observeBookmarks(bookId)
        .map { list -> list.filter { it.pageIndex != null } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(PANEL_SUBSCRIBE_MS), emptyList())

    val annotations: StateFlow<List<AnnotationEntity>> = bookshelfRepository.observeAnnotations(bookId)
        .map { list -> list.filter { it.pageIndex != null } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(PANEL_SUBSCRIBE_MS), emptyList())

    /** 该页是否已经有无页内坐标的「页级书签」（顶栏书签图标的填充状态）。 */
    fun isPageBookmarked(pageIndex: Int): Boolean =
        findPageBookmarkAt(bookmarks.value, pageIndex.toLong(), null, null) != null

    /**
     * 书签 toggle。
     *
     * 不给坐标 = 只锚到页（顶栏按钮）；给了坐标 = 锚到页内某点或某个区域（长按 / 框选）。
     * 页内坐标是归一化的，所以缩放、切适配模式、换窗口之后锚点依然贴在原来的位置上。
     */
    fun toggleBookmark(
        pageIndex: Int,
        x: Float? = null,
        y: Float? = null,
        w: Float? = null,
        h: Float? = null,
    ) {
        viewModelScope.launch {
            val existing = findPageBookmarkAt(bookmarks.value, pageIndex.toLong(), x, y)
            if (existing != null) {
                bookshelfRepository.deleteBookmark(existing.id)
            } else {
                bookshelfRepository.addBookmark(
                    BookmarkEntity(
                        bookId = bookId,
                        charOffset = 0L,
                        chapterIndex = outlineIndexFor(pageIndex).coerceAtLeast(0),
                        snapshotText = pageLabelOf(pageIndex),
                        createdAt = System.currentTimeMillis(),
                        pageIndex = pageIndex.toLong(),
                        anchorX = x,
                        anchorY = y,
                        anchorW = w,
                        anchorH = h,
                    ),
                )
            }
        }
    }

    /** 页式高亮/下划线：区域是**归一化**矩形，样式沿用文本标注的同一套取值。 */
    fun addPageAnnotation(
        pageIndex: Int,
        region: PageRect,
        style: String,
        color: Long,
        selectedText: String = "",
    ) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            bookshelfRepository.addAnnotation(
                AnnotationEntity(
                    bookId = bookId,
                    startCharOffset = 0L,
                    endCharOffset = 0L,
                    selectedText = selectedText,
                    color = color,
                    note = null,
                    style = style,
                    createdAt = now,
                    updatedAt = now,
                    pageIndex = pageIndex.toLong(),
                    regionX = region.left,
                    regionY = region.top,
                    regionW = region.width,
                    regionH = region.height,
                ),
            )
        }
    }

    fun deleteAnnotation(id: Long) {
        viewModelScope.launch { bookshelfRepository.deleteAnnotation(id) }
    }

    fun updateAnnotation(annotation: AnnotationEntity, color: Long, note: String?, style: String) {
        viewModelScope.launch {
            bookshelfRepository.updateAnnotation(
                annotation.copy(
                    color = color,
                    note = note,
                    style = style,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    /** 标注跳转：页式标注只认页序号。 */
    fun jumpToAnnotation(annotation: AnnotationEntity) {
        annotation.pageIndex?.let { goToPage(it.toInt()) }
    }

    // ---- 搜索（文本型 PDF）----

    private val _search = MutableStateFlow(ComicSearchState())
    val search: StateFlow<ComicSearchState> = _search.asStateFlow()

    /** 逐页搜索。结果按页升序，点一条跳过去即可——不需要页内命中坐标（那是选字那条路的事）。 */
    fun search(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            _search.value = ComicSearchState()
            return
        }
        _search.value = ComicSearchState(query = trimmed, running = true)
        viewModelScope.launch {
            val source = source
            val hits = if (source == null) {
                emptyList()
            } else {
                runCatching { source.search(trimmed) }.getOrDefault(emptyList())
            }
            // 期间用户可能又改了关键词：只落最后一次的结果
            if (_search.value.query == trimmed) {
                _search.value = ComicSearchState(query = trimmed, running = false, hits = hits)
            }
        }
    }

    fun clearSearch() {
        _search.value = ComicSearchState()
    }

    // ---- 同系列（前后卷切换）----

    private val _series = MutableStateFlow<List<ComicSeriesCandidate>>(emptyList())
    val series: StateFlow<List<ComicSeriesCandidate>> = _series.asStateFlow()

    private var seriesLoaded = false

    /**
     * 载入同系列候选。同目录枚举要走 SAF、库内比对要查库，所以放在 IO 且只做一次——
     * 它是「方便前后切换」的辅助信息，不该挡住开书。
     */
    fun loadSeries() {
        if (seriesLoaded) return
        seriesLoaded = true
        viewModelScope.launch {
            val book = runCatching { bookshelfRepository.getBook(bookId) }.getOrNull() ?: return@launch
            val loaded = runCatching { seriesCandidates(book) }.getOrDefault(emptyList())
            _series.value = loaded
        }
    }

    /**
     * 打开系列里的某一卷：已入库直接返回它的 id；未入库先按需导入（复用外部导入链路），
     * 导入失败返回 null（界面提示而不是跳到一个不存在的书）。
     */
    suspend fun resolveSeriesEntry(candidate: ComicSeriesCandidate): Long? {
        candidate.bookId?.let { return it }
        return runCatching {
            importSeriesEntry(Uri.parse(candidate.uri), candidate.isDirectory)
        }.getOrNull()?.also { newId ->
            // 导入成功后把它并入列表，返回时前后卷按钮立刻能用
            _series.value = _series.value.map {
                if (it.uri == candidate.uri) it.copy(bookId = newId) else it
            }
        }
    }

    /**
     * 用文档自己的选区替换手指画的框（选字）。
     *
     * 只有有文字层的格式才有结果：拿不到或失败返回 null，调用方保留原始矩形框选。
     * 这是长按松手后的一次性调用（最多一次 IPC），不在拖动过程中回源。
     */
    suspend fun snapSelectionToText(pageIndex: Int, region: PageRect): PageSelection? {
        // 已确认是扫描件就别白跑一次 IPC：文档本来就没有可选的字
        if (features.value.scanned) return null
        val source = source ?: return null
        val hit = runCatching {
            source.selectText(
                index = pageIndex,
                startX = region.left,
                startY = region.top,
                stopX = region.right,
                stopY = region.bottom,
            )
        }.getOrNull() ?: return null
        if (hit.text.isBlank()) return null
        return PageSelection(
            pageIndex = pageIndex,
            rect = PageRect.between(hit.left, hit.top, hit.right, hit.bottom),
            text = hit.text,
        )
    }

    /** 书签跳转：页式书签只认页序号，跳过去即可。 */
    fun jumpToBookmark(bookmark: BookmarkEntity) {
        bookmark.pageIndex?.let { goToPage(it.toInt()) }
    }

    fun renameBookmark(bookmark: BookmarkEntity, label: String) {
        viewModelScope.launch { bookshelfRepository.renameBookmark(bookmark.copy(label = label.trim())) }
    }

    fun deleteBookmark(id: Long) {
        viewModelScope.launch { bookshelfRepository.deleteBookmark(id) }
    }

    /** 中间点击区/顶栏触发的书签 toggle：作用于当前跨页的第一页，无页内坐标 = 页级书签。 */
    fun toggleBookmarkOnCurrentPage(anchorX: Float? = null, anchorY: Float? = null) {
        val page = currentPages().firstOrNull() ?: return
        toggleBookmark(page, anchorX, anchorY)
    }

    /**
     * 双击缩放：在「整页」与「宽度」之间切换。
     *
     * 用适配模式而不是临时倍率——本阅读器的缩放是**每页重置**的（换页即复位），
     * 临时倍率翻一页就没了；而「适应宽度」正是扫描版小字漫画最实用的那一档。
     */
    fun toggleZoomFit() {
        viewModelScope.launch {
            val next = if (preferences.value.comicFitMode == ComicFitMode.FIT_WIDTH) {
                ComicFitMode.FIT_PAGE
            } else {
                ComicFitMode.FIT_WIDTH
            }
            bookPrefsRepository.update(bookId) { it.copy(comicFitMode = next.name) }
        }
    }

    /** 每页宽高比（w/h）；探测完成前为空数组（不做宽图独占，滚动模式用占位高度）。 */
    private val pageAspects = MutableStateFlow(FloatArray(0))

    /** 解码目标尺寸（由界面按视口尺寸回填）：决定降采样倍率。 */
    private var targetWidthPx = 0
    private var targetHeightPx = 0

    private val timer = ReadingTimer()
    private var baseReadingMillis = 0L
    private var firstReadAt = 0L
    private var lastSessionFlushTotalMs = 0L
    private val pendingSave = MutableStateFlow<Int?>(null)
    private val closed = AtomicBoolean(false)

    init {
        viewModelScope.launch {
            runCatching { bookPrefsRepository.ensureInitialized(bookId) }
        }
        viewModelScope.launch {
            pendingSave.filterNotNull().debounce(PROGRESS_SAVE_DEBOUNCE_MS).collect { page ->
                runCatching { persistProgress(page) }
            }
        }
        // 封面单独 / 跨页识别是配对规则的一部分：改了就得重建索引并让当前页重新落位
        viewModelScope.launch {
            preferences
                .map { it.comicDualPageCoverAlone to it.comicSpreadAutoDetect }
                .distinctUntilChanged()
                .drop(1)
                .collect {
                    rebuildSpreadIndex()
                    decodeVisiblePages()
                }
        }
        openBook()
        loadSeries()
        viewModelScope.launch { autoPageLoop() }
    }

    private fun openBook(password: String? = null) {
        viewModelScope.launch {
            try {
                val book = bookshelfRepository.getBook(bookId) ?: throw IOException("书籍不存在")
                // 来源由调用方按格式打开：漫画容器 / PDF 文档，密码错误等也在这里抛出
                val opened = openSource(book, password)
                source = opened
                _features.value = featuresFor(book)
                _uiState.update { it.copy(passwordRequired = false) }
                contentHash = book.contentHash
                // 超大文档只在菜单里提示一句：不挡开书，但先让人知道慢是有原因的
                val sizeMb = runCatching { fileSizeMb(book) }.getOrNull()
                _uiState.update {
                    it.copy(largeFileMb = sizeMb?.takeIf { mb -> mb >= LARGE_FILE_HINT_MB })
                }
                val pageCount = opened.pageCount
                val progress = bookshelfRepository.getProgress(bookId)
                baseReadingMillis = progress?.totalReadingMillis ?: 0L
                firstReadAt = progress?.firstReadAt ?: 0L
                val start = when {
                    initialPage >= 0 -> initialPage
                    else -> progress?.comicPage ?: 0
                }
                _uiState.update {
                    it.copy(
                        loading = false,
                        error = null,
                        bookTitle = book.title,
                        container = book.comicContainer,
                        pageCount = pageCount,
                        pageIndex = start.coerceIn(0, (pageCount - 1).coerceAtLeast(0)),
                    )
                }
                // 页数在导入时未知（rar/tar/7z）或源发生了变化：回填一次
                if (book.comicPageCount != pageCount) {
                    runCatching { bookshelfRepository.updateComicPageCount(bookId, pageCount) }
                }
                runCatching { bookshelfRepository.markContentPrepared(bookId) }
                rebuildSpreadIndex()
                publishPosition()
                decodeVisiblePages()
                probePageSizes()
            } catch (e: PagedSourcePasswordRequired) {
                // 需要密码不是错误：交给界面弹密码框，输入后原样重试
                _uiState.update {
                    it.copy(loading = false, error = null, passwordRequired = true)
                }
            } catch (t: Throwable) {
                _uiState.update {
                    it.copy(loading = false, error = t.message ?: "无法打开这本漫画")
                }
            }
        }
    }

    /**
     * 重建跨页配对。页数、单/双页、封面单独、宽图识别任一变化都要走这里，
     * 并把当前页重新落到新索引的跨页起点上（否则会停在半页位置）。
     */
    private fun rebuildSpreadIndex() {
        val state = _uiState.value
        val aspects = pageAspects.value
        // 跨页配对（封面单独 / 宽图独占整宽）是漫画的习惯；PDF 一律朴素配对。
        // 注意「双页」本身不受影响：那是折叠屏的版式选择，PDF 一样用得上。
        val pairing = _features.value.spreadPairing
        val wide = if (pairing && preferences.value.comicSpreadAutoDetect &&
            aspects.size == state.pageCount
        ) {
            BooleanArray(state.pageCount) { index ->
                aspects[index] > 0f && aspects[index] >= COMIC_WIDE_ASPECT
            }
        } else {
            ComicSpreadIndex.EMPTY_WIDE
        }
        _spreadIndex.value = buildComicSpreadIndex(
            pageCount = state.pageCount,
            dual = state.dual,
            coverAlone = pairing && preferences.value.comicDualPageCoverAlone,
            wide = wide,
        )
        if (state.pageCount > 0) {
            val normalized = _spreadIndex.value.startOf(state.pageIndex)
            if (normalized != state.pageIndex) _uiState.update { it.copy(pageIndex = normalized) }
        }
    }

    /**
     * 后台探测每页宽高比：跨页大图识别与滚动模式的条目高度都要它。
     *
     * 只读图片头（几十 KB）而不是整页，整本扫一遍是可接受的；
     * 页数极大时（几千页的合集）收益递减，直接放弃探测。
     */
    private fun probePageSizes() {
        val opened = source ?: return
        if (opened.pageCount > MAX_SIZE_PROBE_PAGES) return
        sizeProbeJob?.cancel()
        sizeProbeJob = viewModelScope.launch(Dispatchers.IO) {
            val aspects = runCatching { opened.probeAspects() }.getOrDefault(FloatArray(0))
            if (closed.get()) return@launch
            pageAspects.value = aspects
            viewModelScope.launch { rebuildSpreadIndex() }
        }
    }

    /**
     * 各格式的能力差异。
     * PDF 没有左右阅读方向可言，页面自带留白也不适合「无缝拼接」，跨页配对同样少见，
     * 所以整片关掉——菜单里就不该出现这些按了没意义的开关。
     *
     * 文本/扫描的判定依据是"压平产物在不在"：预热跑完后 `cleanedFilePath != null`
     * 就是抽出了正文，扫不出来则只在**准备完成之后**才敢说是扫描件
     * （还没预热完就断言是扫描件会冤枉一本正常书）。
     */
    private fun featuresFor(book: BookEntity): PagedReaderFeatures {
        if (book.format != BookFormat.PDF) return PagedReaderFeatures()
        val hasText = book.cleanedFilePath != null
        return PagedReaderFeatures(
            rtl = false,
            spreadPairing = false,
            seamlessScroll = false,
            textLayer = hasText,
            scanned = !hasText && book.contentPreparedAt != null,
        )
    }

    /** PDF：切到文字模式（走文本阅读器读抽出来的正文）。持久化到这本书的偏好里。 */
    fun setPdfReadingMode(mode: PdfReadingMode) {
        viewModelScope.launch {
            bookPrefsRepository.update(bookId) { it.copy(pdfReadingMode = mode.name) }
        }
    }

    /** 界面按当前视口尺寸回填：决定降采样倍率，尺寸变化（折叠/旋转）后重解码可见页。 */
    fun setDecodeTarget(widthPx: Int, heightPx: Int) {
        if (widthPx < 64 || heightPx < 64) return
        if (widthPx == targetWidthPx && heightPx == targetHeightPx) return
        if (targetWidthPx > 0 && targetHeightPx > 0) {
            val slopW = maxOf(32, (targetWidthPx * 0.05f).toInt())
            val slopH = maxOf(32, (targetHeightPx * 0.05f).toInt())
            if (abs(widthPx - targetWidthPx) <= slopW && abs(heightPx - targetHeightPx) <= slopH) {
                return
            }
        }
        targetWidthPx = widthPx
        targetHeightPx = heightPx
        // 目标尺寸变了，已解码的页要么太小要么过大：整表清掉重来。
        // 清表与解码回填共用同一把锁，否则两边会互相覆盖、字节计数也会漂
        viewModelScope.launch {
            decodeMutex.withLock { clearImages() }
            decodeVisiblePages()
        }
    }

    /** 单页/双页由界面按折叠姿态与偏好判定后回填。 */
    fun setDualPage(dual: Boolean) {
        if (_uiState.value.dual == dual) return
        _uiState.update { it.copy(dual = dual) }
        rebuildSpreadIndex()
        publishPosition()
        decodeVisiblePages()
    }

    /** 当前跨页应显示的页（已按封面单独 / 宽图独占整宽配对）。 */
    fun currentPages(): List<Int> = _spreadIndex.value.pagesOf(_uiState.value.pageIndex)

    /**
     * 该页是否被识别为横向跨页大图（渲染时独占整宽）。
     * 读的是尺寸探测结果——探测完成会同时刷新 [spreadIndex]，界面因此会重组并读到新值。
     */
    fun isWideSpan(pageIndex: Int): Boolean =
        _features.value.spreadPairing &&
            preferences.value.comicSpreadAutoDetect &&
            (pageAspects.value.getOrNull(pageIndex) ?: 0f) >= COMIC_WIDE_ASPECT

    /**
     * 该页的宽高比（w/h）；探测未覆盖时返回 null。
     * 滚动模式用它定条目高度——高度稳定才不会在滚动中跳位。
     */
    fun aspectOf(pageIndex: Int): Float? =
        pageAspects.value.getOrNull(pageIndex)?.takeIf { it > 0f }

    /**
     * 单页按需解码（滚动模式由条目自己调用）。
     * 与 [decodeVisiblePages] 的批量预取不同：这里不取消别的解码任务，
     * 因为滚动时条目是逐个进入视口的，互相取消只会让每一页都白解一遍。
     */
    fun ensurePage(index: Int) {
        if (index !in 0 until _uiState.value.pageCount) return
        if (images.containsKey(index)) return
        viewModelScope.launch(Dispatchers.IO) {
            val image = runCatching { decode(index) }.getOrNull()
            if (image == null) {
                markPageFailed(index)
                return@launch
            }
            clearPageFailed(index)
            putImage(index, image)
        }
    }

    /**
     * 等到某一页解码完成（或失败/超时）。仿真翻页要把现成位图烘进离屏缓存，
     * 预取窗口通常已经覆盖相邻跨页，这里只是补上竞态。
     */
    suspend fun awaitPage(index: Int, timeoutMs: Long = 1500L): PagedPageImage? {
        if (index !in 0 until _uiState.value.pageCount) return null
        images[index]?.let { return it }
        if (failedPages[index] == true) return null
        ensurePage(index)
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            images[index]?.let { return it }
            if (failedPages[index] == true) return null
            delay(16)
        }
        return images[index]
    }

    /**
     * 解码失败标记 / 清除。滚动模式下每个可见条目一个 IO 协程、翻页预取又是另一条，
     * 它们会同时改这张快照表，所以与 [putImage] 共用 [decodeMutex] 串行化。
     */
    private suspend fun markPageFailed(index: Int) {
        decodeMutex.withLock {
            Snapshot.withMutableSnapshot { failedPages[index] = true }
        }
    }

    private suspend fun clearPageFailed(index: Int) {
        decodeMutex.withLock {
            Snapshot.withMutableSnapshot { failedPages.remove(index) }
        }
    }

    /** 滚动模式的滚动位置：只更新进度，预取由各条目自己按需触发。 */
    fun onScrollAnchor(index: Int) {
        val state = _uiState.value
        if (state.pageCount <= 0) return
        val target = index.coerceIn(0, state.pageCount - 1)
        if (target == state.pageIndex) return
        _uiState.update { it.copy(pageIndex = target) }
        publishPosition()
        pendingSave.value = target
    }

    /**
     * 按需加载一张缩略图（网格条目自己调用）。
     *
     * 三级来源：内存表 → 磁盘缓存 → 从容器读一页再降采样。磁盘缓存让大书的网格
     * 第二次打开就是即时的，也让"滚动网格 → 回到前面"不必重新解压。
     */
    fun ensureThumbnail(index: Int) {
        if (index !in 0 until _uiState.value.pageCount) return
        synchronized(thumbnailLock) {
            if (thumbnails.containsKey(index)) return
            if (!thumbnailLoading.add(index)) return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val image = runCatching { loadThumbnail(index) }.getOrNull()
            synchronized(thumbnailLock) {
                // 写回与摘除登记必须在同一临界区：否则「已摘登记但还没写表」的窗口里
                // 重组会再起一个协程，同一个 index 被写两次，淘汰队列随之错位
                thumbnailLoading.remove(index)
                if (image == null) return@launch
                // 从 IO 线程改 SnapshotStateMap：走可变快照，界面侧才会被正确通知
                Snapshot.withMutableSnapshot { putThumbnail(index, image) }
            }
        }
    }

    private suspend fun loadThumbnail(index: Int): ImageBitmap? {
        val file = thumbnailFile(index)
        if (file != null && file.isFile && file.length() > 0L) {
            BitmapFactory.decodeFile(file.absolutePath)?.let { return it.asImageBitmap() }
        }
        val opened = source ?: return null
        val bitmap = opened.loadThumbnail(index, THUMB_W, THUMB_H) ?: return null
        // 落盘：编成 JPEG 体积小、解码快，网格滚动时基本是纯 IO
        if (file != null) {
            runCatching {
                file.parentFile?.mkdirs()
                file.outputStream().use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, THUMB_QUALITY, out)
                }
            }
        }
        return bitmap.asImageBitmap()
    }

    private fun thumbnailFile(index: Int): File? {
        val hash = contentHash ?: return null
        return File(extractionStore.thumbsDir(hash), "$index.jpg")
    }

    /** 缩略图表按插入序限量淘汰：网格来回滚不该把几百张小图全留在内存里。调用方须持有 [thumbnailLock]。 */
    private fun putThumbnail(index: Int, image: ImageBitmap) {
        if (thumbnails.put(index, image) == null) thumbnailOrder.addLast(index)
        while (thumbnailOrder.size > MAX_THUMBNAILS) {
            val victim = thumbnailOrder.removeFirst()
            if (victim == _uiState.value.pageIndex) {
                thumbnailOrder.addLast(victim)
                break
            }
            thumbnails.remove(victim)
        }
    }

    fun goToPage(index: Int, countRead: Boolean = false) {
        val state = _uiState.value
        if (state.pageCount <= 0) return
        val clamped = index.coerceIn(0, state.pageCount - 1)
        // 双页模式下位置一律落在跨页起点：否则「第 2 页」和「第 1–2 页」会来回打架
        val target = if (state.dual) _spreadIndex.value.startOf(clamped) else clamped
        if (target == state.pageIndex) return
        _uiState.update { it.copy(pageIndex = target) }
        publishPosition()
        pendingSave.value = target
        decodeVisiblePages()
    }

    /** @return 是否真的翻了页（到边界返回 false，界面据此决定是否放行滑动）。 */
    fun turnPage(forward: Boolean): Boolean {
        val state = _uiState.value
        val target = if (forward) {
            _spreadIndex.value.next(state.pageIndex)
        } else {
            _spreadIndex.value.previous(state.pageIndex)
        } ?: return false
        goToPage(target, countRead = true)
        return true
    }

    /**
     * 提交密码重试打开。密码只在这一条链路上传递，既不入库也不进备份。
     */
    fun submitPassword(password: String) {
        _uiState.update { it.copy(loading = true, error = null, passwordRequired = false) }
        openBook(password)
    }

    /** 放弃输入密码：退回错误态（界面有「返回」出口）。 */
    fun cancelPassword() {
        _uiState.update {
            it.copy(loading = false, passwordRequired = false, error = "需要密码才能打开")
        }
    }

    fun seekToFraction(fraction: Float) {
        val state = _uiState.value
        goToPage(comicPageFromFraction(fraction, state.pageCount))
    }

    // ---- 每书偏好 ----

    fun setPageTurnMode(mode: PageTurnMode) {
        viewModelScope.launch { bookPrefsRepository.update(bookId) { it.copy(pageTurnMode = mode.name) } }
    }

    fun setReaderBrightness(brightness: Float) {
        viewModelScope.launch {
            bookPrefsRepository.update(bookId) {
                it.copy(readerBrightness = brightness.coerceIn(-1f, 1f))
            }
        }
    }

    /** 屏幕常亮是应用级偏好（设置页也有）：写全局，否则设置页改了这里不生效。 */
    fun setKeepScreenOn(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setKeepScreenOn(enabled) }
    }

    fun setTheme(theme: ReadingTheme) {
        viewModelScope.launch { bookPrefsRepository.update(bookId) { it.copy(themeId = theme.name) } }
    }

    fun setCustomColors(backgroundArgb: Int?, textArgb: Int?) {
        viewModelScope.launch {
            bookPrefsRepository.update(bookId) {
                it.copy(customBackgroundArgb = backgroundArgb, customTextArgb = textArgb)
            }
        }
    }

    fun setComicDirection(direction: ComicDirection) {
        viewModelScope.launch {
            bookPrefsRepository.update(bookId) { it.copy(comicDirection = direction.name) }
        }
    }

    /** 跨页配对开关是应用级偏好：写全局。改完要重建跨页索引。 */
    fun setComicDualPageCoverAlone(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setComicDualPageCoverAlone(enabled) }
    }

    fun setComicSpreadAutoDetect(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setComicSpreadAutoDetect(enabled) }
    }

    fun setComicFitMode(mode: ComicFitMode) {
        viewModelScope.launch {
            bookPrefsRepository.update(bookId) { it.copy(comicFitMode = mode.name) }
        }
    }

    /** 滚动页间距是应用级偏好：写全局。 */
    fun setComicScrollGapDp(gapDp: Int) {
        viewModelScope.launch { settingsRepository.setComicScrollGapDp(gapDp.coerceIn(0, 64)) }
    }

    // ---- 解码与缓存 ----

    private fun visiblePages(): List<Int> {
        val state = _uiState.value
        return comicPrefetchPages(
            visible = _spreadIndex.value.pagesOf(state.pageIndex),
            pageCount = state.pageCount,
        )
    }

    private fun decodeVisiblePages() {
        val wanted = visiblePages()
        if (wanted.isEmpty()) return
        // 正在解码的批次直接作废：新位置优先级更高，避免快速翻页时排队解码旧页
        prefetchJob?.cancel()
        prefetchJob = viewModelScope.launch(Dispatchers.IO) {
            for (index in wanted) {
                if (images.containsKey(index)) continue
                val image = runCatching { decode(index) }.getOrNull()
                if (image == null) {
                    markPageFailed(index)
                    continue
                }
                clearPageFailed(index)
                putImage(index, image)
            }
        }
    }

    private suspend fun decode(index: Int): PagedPageImage? {
        val opened = source ?: return null
        // 目标尺寸只在这里给：漫画据此降采样解码，PDF 据此渲染成对应大小的位图
        return opened.loadPage(index, targetWidthPx, targetHeightPx)
    }

    private suspend fun putImage(index: Int, image: PagedPageImage) {
        decodeMutex.withLock {
            if (closed.get()) return
            // 从 IO 线程改 SnapshotStateMap：走可变快照，界面侧才会被正确通知
            Snapshot.withMutableSnapshot {
                images[index] = image
                imageBytes += image.byteCount
                evictFarImages()
            }
        }
    }

    /**
     * 按字节预算淘汰离当前页最远的已解码页。
     * 双页 + 预取窗口本来就只有几页，这里防的是长时间浏览后整本都被解出来。
     */
    private fun evictFarImages() {
        if (imageBytes <= MAX_IMAGE_BYTES || images.size <= KEEP_NEAREST_PAGES) return
        val current = _uiState.value.pageIndex
        val victim = images.keys.maxByOrNull { abs(it - current) } ?: return
        if (victim == current) return
        images.remove(victim)?.let { imageBytes -= it.byteCount }
    }

    /**
     * 系统内存吃紧时由界面调用：丢掉已解码页，再把可见页解回来。
     *
     * 只清不补的话当前页会一直停在转圈占位上——翻页与列表都不会替你重来一次。
     */
    fun clearImageBitmaps() {
        // 与 putImage 共用同一把锁：否则清表与并发解码回填会互相覆盖，字节计数也会漂
        viewModelScope.launch {
            decodeMutex.withLock { clearImages() }
            decodeVisiblePages()
        }
    }

    /** 清空已解码页表与失败标记。调用方须持有 [decodeMutex]。 */
    private fun clearImages() {
        // 不 recycle：位图可能正被当前帧的绘制持有，回收会直接崩在绘制阶段，
        // 这里只断开引用让 GC 回收（与 ReaderViewModel 的插图缓存同一策略）。
        // 失败标记一并清掉：低内存导致的解码失败多是暂时的，留着会让那一页永远显示「无法显示此页」
        Snapshot.withMutableSnapshot {
            images.clear()
            failedPages.clear()
        }
        imageBytes = 0
    }

    // ---- 进度与计时 ----

    private fun publishPosition() {
        val state = _uiState.value
        _readingPosition.value = ComicReadingPosition(
            progressFraction = comicProgressOf(state.pageIndex, state.pageCount),
            pageNumber = state.pageIndex + 1,
            totalPages = state.pageCount,
        )
    }

    private fun totalReadingMillis(): Long =
        baseReadingMillis + timer.totalMs(SystemClock.elapsedRealtime())

    private suspend fun persistProgress(page: Int) {
        val existing = bookshelfRepository.getProgress(bookId)
        val now = System.currentTimeMillis()
        bookshelfRepository.saveProgress(
            ReadingProgressEntity(
                bookId = bookId,
                charOffset = 0L,
                chapterIndex = 0,
                totalReadingMillis = totalReadingMillis(),
                firstReadAt = existing?.firstReadAt?.takeIf { it > 0 } ?: now,
                charsReadTotal = existing?.charsReadTotal ?: 0L,
                comicPage = page,
                updatedAt = now,
                // 文本锚点（偏移 + 章节）原样保留：同一本书的另一模式位置不能被整行 REPLACE 抹掉
            ).keepTextAnchor(existing),
        )
        bookshelfRepository.touchLastRead(bookId, now)
    }

    // ---- 自动翻页 ----

    private val autoPageClock = AutoPageClock()
    private val autoPageTurnRequests = AutoPageTurnRequests()

    /** 到点请求翻页：界面收集后走**正常翻页动画**，与手动翻页完全同一条路。 */
    val autoPageTurns: SharedFlow<Boolean> = autoPageTurnRequests.requests

    /** 阅读是否活跃（在前台且没开菜单）：菜单开着时不自动翻。 */
    private var readingActive = false

    /** 手动操作（点按/翻页）后暂停自动翻页一段时间，与文本阅读器同一套时钟。 */
    fun noteManualInteraction() {
        autoPageClock.noteManualInteraction(SystemClock.elapsedRealtime())
    }

    fun setAutoPageEnabled(enabled: Boolean) {
        viewModelScope.launch { bookPrefsRepository.update(bookId) { it.copy(autoPageEnabled = enabled) } }
    }

    /** 循环档位：间隔模式调秒数，滚动模式调速度（复用文本阅读器那套步进表，行为一致）。 */
    fun cycleAutoPageSetting() {
        viewModelScope.launch {
            bookPrefsRepository.update(bookId) { prefs ->
                when (prefs.autoPageMode) {
                    AutoPageMode.INTERVAL.name -> prefs.copy(
                        autoPageIntervalSec = nextAutoPageIntervalSec(prefs.autoPageIntervalSec),
                    )
                    else -> prefs.copy(autoPageSpeedPx = nextAutoPageSpeedPx(prefs.autoPageSpeedPx))
                }
            }
        }
    }

    fun cycleAutoPageMode() {
        viewModelScope.launch {
            bookPrefsRepository.update(bookId) { prefs ->
                val current = enumOrDefault(prefs.autoPageMode, AutoPageMode.INTERVAL)
                prefs.copy(autoPageMode = nextAutoPageMode(current).name)
            }
        }
    }

    /**
     * 自动翻页·间隔模式。
     *
     * 只负责按间隔发请求，翻页动画由界面走正常翻页路径；到末页自然停下，
     * 菜单打开 / 退到后台 / 刚手动操作过都不发。
     */
    private suspend fun autoPageLoop() {
        var lastTurnMs = SystemClock.elapsedRealtime()
        while (true) {
            delay(AUTO_PAGE_TICK_MS)
            val now = SystemClock.elapsedRealtime()
            val prefs = preferences.value
            val state = _uiState.value
            val ready = prefs.autoPageEnabled &&
                prefs.autoPageMode == AutoPageMode.INTERVAL &&
                readingActive &&
                !state.loading &&
                state.error == null &&
                !state.passwordRequired &&
                state.pageCount > 0 &&
                !spreadIndex.value.isLastSpread(state.pageIndex) &&
                !autoPageClock.isManualPaused(now)
            if (!ready) {
                lastTurnMs = now
                continue
            }
            val intervalMs = prefs.autoPageIntervalSec.coerceIn(3, 30) * 1000L
            if (now - lastTurnMs < intervalMs) continue
            lastTurnMs = now
            autoPageTurnRequests.request(forward = true)
        }
    }

    fun setReadingActive(active: Boolean) {
        readingActive = active
        val now = SystemClock.elapsedRealtime()
        if (active) {
            timer.start(now)
        } else {
            timer.stop(now)
        }
    }

    /** 退到后台（ON_STOP）时立即落一次时长，避免长时间停留不翻页丢整段。 */
    fun flushReadingSessionNow() {
        val now = SystemClock.elapsedRealtime()
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { flushReadingSession(now) }
        }
    }

    private suspend fun flushReadingSession(nowElapsedMs: Long) {
        val total = timer.totalMs(nowElapsedMs)
        val delta = total - lastSessionFlushTotalMs
        if (delta <= 0L) return
        lastSessionFlushTotalMs = total
        bookshelfRepository.addReadingSession(
            bookId,
            dayStartMs(System.currentTimeMillis(), java.time.ZoneId.systemDefault()),
            delta,
        )
    }

    override fun onCleared() {
        closed.set(true)
        prefetchJob?.cancel()
        val page = pendingSave.value ?: _uiState.value.pageIndex
        val opened = source
        // 收尾落库 + 关闭来源放 IO 异步做：进度在阅读期间已由防抖保存覆盖，
        // 这里只是最后一笔，不值得在返回转场的收尾帧上挡主线程
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                if (_uiState.value.pageCount > 0) persistProgress(page)
            }
            runCatching { flushReadingSession(SystemClock.elapsedRealtime()) }
            runCatching { opened?.close() }
        }
    }

    companion object {
        private const val PROGRESS_SAVE_DEBOUNCE_MS = 500L

        /** 面板类 Flow 的订阅保活时长：面板开关之间来回切换时不必重查库。 */
        private const val PANEL_SUBSCRIBE_MS = 5_000L

        /** 已解码页的内存预算上限。 */
        const val MAX_IMAGE_BYTES = 64 * 1024 * 1024

        /** 无论预算如何，至少保留当前可见跨页再前后各两页（最多 6 张）加一点余量。 */
        const val KEEP_NEAREST_PAGES = 8

        /** 超过这个页数就不再整本探测宽高比（收益递减，几千页的合集扫一遍不值得）。 */
        const val MAX_SIZE_PROBE_PAGES = 2000

        /** 文件超过这个大小（MB）就在菜单里给一句性能提示。 */
        const val LARGE_FILE_HINT_MB = 250

        /** 自动翻页的轮询步长：够密到不误判"还没到点"，也不至于空转太勤。 */
        private const val AUTO_PAGE_TICK_MS = 250L

        /** 缩略图目标尺寸与内存中保留的张数。 */
        const val THUMB_W = 160
        const val THUMB_H = 240
        const val THUMB_QUALITY = 80
        const val MAX_THUMBNAILS = 160

        fun factory(
            container: AppContainer,
            bookId: Long,
            initialPage: Int = -1,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ComicReaderViewModel(
                    bookId = bookId,
                    bookshelfRepository = container.bookshelfRepository,
                    bookPrefsRepository = container.bookPrefsRepository,
                    settingsRepository = container.settingsRepository,
                    openSource = container::openPagedSource,
                    extractionStore = container.comicExtractionStore,
                    seriesCandidates = container::comicSeriesCandidates,
                    importSeriesEntry = container::importSeriesComic,
                    fileSizeMb = container::fileSizeMb,
                    initialPage = initialPage,
                )
            }
        }
    }
}

package com.llzx373.foldreader.feature.reader

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.llzx373.foldreader.AppContainer
import com.llzx373.foldreader.core.data.db.ReadingProgressEntity
import com.llzx373.foldreader.core.data.repository.BookshelfRepository
import com.llzx373.foldreader.core.data.settings.ReadingPreferences
import com.llzx373.foldreader.core.data.settings.ReadingTheme
import com.llzx373.foldreader.core.data.settings.SettingsRepository
import com.llzx373.foldreader.core.format.BookContent
import com.llzx373.foldreader.core.format.BookParser
import com.llzx373.foldreader.core.format.Chapter
import com.llzx373.foldreader.core.reader.FontManager
import com.llzx373.foldreader.core.reader.LayoutConfig
import com.llzx373.foldreader.core.reader.Page
import com.llzx373.foldreader.core.reader.Paginator
import com.llzx373.foldreader.core.reader.StaticLayoutTextMeasurer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class ReaderUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val bookTitle: String = "",
    val totalChars: Long = 0,
    val page: Page? = null,
    val chapterTitle: String = "",
    val chapterIndex: Int = 0,
    val chapterCount: Int = 0,
    val progressFraction: Float = 0f,
    val layoutConfig: LayoutConfig = LayoutConfig(),
    val scrollPages: List<Page> = emptyList(),
)

private data class Viewport(
    val widthPx: Int,
    val heightPx: Int,
    val density: Float,
    val scaledDensity: Float,
)

@OptIn(FlowPreview::class)
class ReaderViewModel(
    private val bookId: Long,
    private val bookshelfRepository: BookshelfRepository,
    private val settingsRepository: SettingsRepository,
    private val parser: BookParser,
    private val fontManager: FontManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    val preferences: StateFlow<ReadingPreferences> = settingsRepository.preferences
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReadingPreferences())

    private var content: BookContent? = null
    private var chapters: List<Chapter> = emptyList()
    private var baseReadingMillis = 0L
    private var paginator: Paginator? = null
    private val pageMutex = Mutex()
    private val anchorOffset = MutableStateFlow(0L)
    private val viewport = MutableStateFlow<Viewport?>(null)
    private val pendingSave = MutableStateFlow<Long?>(null)
    private val timer = ReadingTimer()
    private val speedTracker = ReadingSpeedTracker()

    init {
        viewModelScope.launch {
            val book = bookshelfRepository.getBook(bookId)
            if (book == null) {
                _uiState.update { it.copy(loading = false, error = "书籍不存在") }
                return@launch
            }
            try {
                val uri = Uri.parse(book.fileUri)
                val opened = withContext(Dispatchers.IO) {
                    val stored = bookshelfRepository.getChapters(bookId)
                    val resolved = stored.ifEmpty {
                        parser.parseChapters(uri).also { scanned ->
                            runCatching { bookshelfRepository.saveChapters(bookId, scanned) }
                        }
                    }
                    parser.openContent(uri) to resolved
                }
                content = opened.first
                chapters = opened.second
                val progress = bookshelfRepository.getProgress(bookId)
                baseReadingMillis = progress?.totalReadingMillis ?: 0L
                anchorOffset.value = progress?.charOffset ?: 0L
                _uiState.update {
                    it.copy(bookTitle = book.title, totalChars = opened.first.charCount)
                }
                collectViewport()
            } catch (t: Throwable) {
                _uiState.update { it.copy(loading = false, error = t.message ?: "打开失败") }
            }
        }
        viewModelScope.launch {
            pendingSave.filterNotNull().debounce(500L).collect { offset ->
                runCatching { persistProgress(offset) }
            }
        }
    }

    private fun buildProgress(offset: Long, nowMs: Long) = ReadingProgressEntity(
        bookId = bookId,
        charOffset = offset,
        chapterIndex = chapterIndexAt(chapters, offset),
        totalReadingMillis = baseReadingMillis + timer.totalMs(nowMs),
        updatedAt = nowMs,
    )

    private suspend fun persistProgress(offset: Long) {
        bookshelfRepository.saveProgress(buildProgress(offset, System.currentTimeMillis()))
        bookshelfRepository.touchLastRead(bookId)
    }

    private suspend fun collectViewport() {
        kotlinx.coroutines.flow.combine(
            viewport.filterNotNull(),
            settingsRepository.preferences
                .distinctUntilChanged { a, b ->
                    a.fontSizeSp == b.fontSizeSp &&
                        a.lineSpacingMultiplier == b.lineSpacingMultiplier &&
                        a.marginLevel == b.marginLevel &&
                        a.fontKey == b.fontKey
                },
        ) { v, p -> v to p }.collectLatest { (v, p) ->
            val source = content ?: return@collectLatest
            val (marginH, marginV) = marginDpFor(p.marginLevel)
            val config = LayoutConfig(
                fontSizeSp = p.fontSizeSp,
                lineSpacingMultiplier = p.lineSpacingMultiplier,
                marginLeftDp = marginH,
                marginRightDp = marginH,
                marginTopDp = marginV,
                marginBottomDp = marginV,
                fontKey = p.fontKey,
                typeface = fontManager.resolve(p.fontKey),
            )
            val newPaginator = Paginator(
                content = source,
                config = config,
                measurer = StaticLayoutTextMeasurer(),
                widthPx = v.widthPx,
                heightPx = v.heightPx,
                density = v.density,
                scaledDensity = v.scaledDensity,
            )
            val page = withContext(Dispatchers.Default) {
                newPaginator.pageAt(anchorOffset.value)
            }
            pageMutex.withLock { paginator = newPaginator }
            publish(page, config)
        }
    }

    fun setViewport(widthPx: Int, heightPx: Int, density: Float, scaledDensity: Float) {
        if (widthPx <= 0 || heightPx <= 0) return
        viewport.value = Viewport(widthPx, heightPx, density, scaledDensity)
    }

    suspend fun adjacentPage(forward: Boolean): Page? {
        val current = _uiState.value.page ?: return null
        val p = pageMutex.withLock { paginator } ?: return null
        return withContext(Dispatchers.Default) {
            if (forward) p.pageAfter(current.charStart) else p.pageBefore(current.charStart)
        }
    }

    fun showPage(page: Page) {
        anchorOffset.value = page.charStart
        trackSpeed(page.charStart)
        publish(page, _uiState.value.layoutConfig)
        pendingSave.value = page.charStart
    }

    suspend fun seekToFraction(fraction: Float) {
        val total = _uiState.value.totalChars
        if (total <= 0L) return
        val target = (total * fraction.coerceIn(0f, 1f)).toLong()
        seekToOffset(target)
    }

    suspend fun seekToOffset(offset: Long) {
        val p = pageMutex.withLock { paginator } ?: return
        val page = withContext(Dispatchers.Default) { p.pageAt(offset) }
        showPage(page)
    }

    suspend fun relocate() {
        seekToOffset(anchorOffset.value)
    }

    fun enterScrollMode() {
        val current = _uiState.value.page ?: return
        _uiState.update { it.copy(scrollPages = listOf(current)) }
    }

    suspend fun scrollExtend(forward: Boolean) {
        val pages = _uiState.value.scrollPages
        if (pages.isEmpty()) return
        val p = pageMutex.withLock { paginator } ?: return
        val next = withContext(Dispatchers.Default) {
            if (forward) p.pageAfter(pages.last().charStart) else p.pageBefore(pages.first().charStart)
        } ?: return
        _uiState.update {
            it.copy(scrollPages = if (forward) it.scrollPages + next else listOf(next) + it.scrollPages)
        }
    }

    fun scrollAnchorTo(charStart: Long) {
        anchorOffset.value = charStart
        trackSpeed(charStart)
        pendingSave.value = charStart
        _uiState.update {
            it.copy(
                progressFraction = progressPercentOf(charStart, it.totalChars),
                chapterTitle = chapters.getOrNull(chapterIndexAt(chapters, charStart))?.title.orEmpty(),
                chapterIndex = chapterIndexAt(chapters, charStart),
            )
        }
    }

    suspend fun chapterAt(index: Int): Chapter? = chapters.getOrNull(index)

    fun chapterList(): List<Chapter> = chapters

    fun setPageTurnMode(mode: com.llzx373.foldreader.core.data.settings.PageTurnMode) {
        viewModelScope.launch { settingsRepository.setPageTurnMode(mode) }
    }

    fun setReaderBrightness(brightness: Float) {
        viewModelScope.launch { settingsRepository.setReaderBrightness(brightness) }
    }

    fun setFontSize(sizeSp: Float) {
        viewModelScope.launch { settingsRepository.setFontSize(sizeSp) }
    }

    fun setLineSpacing(multiplier: Float) {
        viewModelScope.launch { settingsRepository.setLineSpacing(multiplier) }
    }

    fun setMarginLevel(level: Int) {
        viewModelScope.launch { settingsRepository.setMarginLevel(level) }
    }

    fun setTheme(theme: ReadingTheme) {
        viewModelScope.launch { settingsRepository.setTheme(theme) }
    }

    fun setCustomColors(backgroundArgb: Int?, textArgb: Int?) {
        viewModelScope.launch { settingsRepository.setCustomColors(backgroundArgb, textArgb) }
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

    private fun publish(page: Page, config: LayoutConfig) {
        _uiState.update {
            it.copy(
                loading = false,
                error = null,
                page = page,
                layoutConfig = config,
                progressFraction = progressPercentOf(page.charStart, it.totalChars),
                chapterTitle = chapters.getOrNull(chapterIndexAt(chapters, page.charStart))?.title.orEmpty(),
                chapterIndex = chapterIndexAt(chapters, page.charStart),
                chapterCount = chapters.size,
            )
        }
    }

    override fun onCleared() {
        val offset = pendingSave.value
        if (offset != null) {
            kotlinx.coroutines.runBlocking {
                runCatching {
                    bookshelfRepository.saveProgress(buildProgress(offset, System.currentTimeMillis()))
                }
            }
        }
    }

    companion object {
        fun factory(container: AppContainer, bookId: Long): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ReaderViewModel(
                    bookId = bookId,
                    bookshelfRepository = container.bookshelfRepository,
                    settingsRepository = container.settingsRepository,
                    parser = container.txtBookParser,
                    fontManager = container.fontManager,
                )
            }
        }
    }
}

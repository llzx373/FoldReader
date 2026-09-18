package com.llzx373.foldreader.feature.bookshelf

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.llzx373.foldreader.AppContainer
import com.llzx373.foldreader.core.data.db.BookWithProgress
import com.llzx373.foldreader.core.data.repository.BookshelfRepository
import com.llzx373.foldreader.core.data.settings.BookshelfSort
import com.llzx373.foldreader.core.data.settings.SettingsRepository
import com.llzx373.foldreader.core.format.BookParsers
import com.llzx373.foldreader.core.format.EncodingDetector
import com.llzx373.foldreader.core.format.OffsetIndexStore
import com.llzx373.foldreader.core.format.clean.CleanLevel
import com.llzx373.foldreader.core.format.clean.CleanProfile
import com.llzx373.foldreader.core.format.clean.CleanReport
import com.llzx373.foldreader.core.format.clean.CleanToggles
import com.llzx373.foldreader.feature.importer.BatchImportUseCase
import com.llzx373.foldreader.feature.importer.ComicImportUseCase
import com.llzx373.foldreader.feature.importer.ImportBookUseCase
import com.llzx373.foldreader.feature.importer.RecleanBookUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 书架客户端排序：均为稳定键，阅读行为不会改变列表顺序（进度排序仅在进度百分比变化跨越他书时移动）。 */
private fun sortBookshelf(books: List<BookWithProgress>, sort: BookshelfSort): List<BookWithProgress> =
    when (sort) {
        BookshelfSort.IMPORT_TIME -> books.sortedByDescending { it.book.importedAt }
        BookshelfSort.TITLE -> books.sortedBy { it.book.title }
        BookshelfSort.PROGRESS -> books.sortedByDescending { item ->
            val total = item.book.totalChars
            if (total > 0L) (item.charOffset ?: 0L).toDouble() / total else 0.0
        }
    }

sealed interface ImportUiState {
    data object Idle : ImportUiState

    /** [progress] 小于 0 表示不确定进度（未启用清理）。 */
    data class Importing(val progress: Float = -1f) : ImportUiState
    data class Imported(
        val bookId: Long,
        val title: String,
        val lowEncodingConfidence: Boolean,
        val openAfter: Boolean,
        /** 清洗改动摘要；未清洗时为 null。 */
        val cleanSummary: String? = null,
    ) : ImportUiState
    data class Duplicate(
        val bookId: Long,
        val title: String,
        val sameFile: Boolean,
        val openAfter: Boolean,
    ) : ImportUiState
    data class Error(val message: String) : ImportUiState
}

/** 目录批量导入的状态机，与单书 [ImportUiState] 完全分离。 */
sealed interface BatchImportUiState {
    data object Idle : BatchImportUiState
    data object Enumerating : BatchImportUiState
    data class Confirming(
        val defaultGroupName: String,
        val result: BatchImportUseCase.EnumerateResult,
    ) : BatchImportUiState
    data class Importing(val done: Int, val total: Int, val currentName: String) : BatchImportUiState
    data class Done(val result: BatchImportUseCase.BatchResult) : BatchImportUiState
    data class Error(val message: String) : BatchImportUiState
}

class BookshelfViewModel(
    private val importBook: ImportBookUseCase,
    private val batchImport: BatchImportUseCase,
    private val bookshelfRepository: BookshelfRepository,
    private val settingsRepository: SettingsRepository,
    private val parsers: BookParsers,
    private val offsetIndexStore: OffsetIndexStore,
    private val comicImport: ComicImportUseCase,
    private val reclean: RecleanBookUseCase,
) : ViewModel() {

    val books: StateFlow<List<BookWithProgress>> = combine(
        bookshelfRepository.observeBookshelfWithProgress(),
        settingsRepository.preferences
            .map { it.bookshelfSort }
            .distinctUntilChanged(),
    ) { list, sort -> sortBookshelf(list, sort) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val sortOrder: StateFlow<BookshelfSort> = settingsRepository.preferences
        .map { it.bookshelfSort }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BookshelfSort.IMPORT_TIME)

    fun setSortOrder(sort: BookshelfSort) {
        viewModelScope.launch { settingsRepository.setBookshelfSort(sort) }
    }

    // 首个书架快照到达前视为加载中，区分"加载中"与"空书架"
    val loading: StateFlow<Boolean> = bookshelfRepository.observeBookshelfWithProgress()
        .map { false }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val gridView: StateFlow<Boolean> = settingsRepository.preferences
        .map { it.bookshelfGridView }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val groups: StateFlow<List<String>> = bookshelfRepository.observeGroupNames()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _selectedIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedIds: StateFlow<Set<Long>> = _selectedIds.asStateFlow()

    private val _importState = MutableStateFlow<ImportUiState>(ImportUiState.Idle)
    val importState: StateFlow<ImportUiState> = _importState.asStateFlow()

    val allBookmarks: StateFlow<List<com.llzx373.foldreader.core.data.db.BookmarkEntity>> =
        bookshelfRepository.observeAllBookmarks()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val allAnnotations: StateFlow<List<com.llzx373.foldreader.core.data.db.AnnotationEntity>> =
        bookshelfRepository.observeAllAnnotations()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun import(
        uri: Uri,
        openAfterImport: Boolean,
        cleanLevel: CleanLevel?,
        convertTraditional: Boolean,
    ) {
        if (_importState.value is ImportUiState.Importing) return
        viewModelScope.launch {
            val profile = buildCleanProfile(cleanLevel, convertTraditional)
            _importState.value = ImportUiState.Importing(if (profile.isNoop) -1f else 0f)
            val result = importBook.import(uri, profile) { progress ->
                _importState.value = ImportUiState.Importing(progress)
            }
            _importState.value = when (result) {
                is ImportBookUseCase.Result.Imported ->
                    ImportUiState.Imported(
                        result.bookId,
                        result.title,
                        lowEncodingConfidence = result.encodingConfidence < 0.5f,
                        openAfter = openAfterImport,
                        cleanSummary = result.cleanReport?.summary(),
                    )
                is ImportBookUseCase.Result.DuplicateSameUri ->
                    ImportUiState.Duplicate(result.bookId, result.title, sameFile = true, openAfter = openAfterImport)
                is ImportBookUseCase.Result.DuplicateSameHash ->
                    ImportUiState.Duplicate(result.bookId, result.title, sameFile = false, openAfter = false)
                is ImportBookUseCase.Result.Failure ->
                    ImportUiState.Error(result.message ?: "未知错误")
            }
        }
    }

    fun consumeImportState() {
        _importState.value = ImportUiState.Idle
    }

    /** 清洗预览状态：[report] 为 null 且 [loading] 为真时表示正在采样。 */
    data class CleanPreview(val loading: Boolean, val report: CleanReport?)

    /** 导入对话框的默认清洗设置。 */
    data class CleanDefaults(val level: CleanLevel, val convertTraditional: Boolean)

    val cleanDefaults: StateFlow<CleanDefaults> = settingsRepository.preferences
        .map { CleanDefaults(it.cleanLevel, it.cleanToggles.traditionalToSimplified) }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            CleanDefaults(CleanLevel.STANDARD, false),
        )

    private val _cleanPreview = MutableStateFlow<CleanPreview?>(null)
    val cleanPreview: StateFlow<CleanPreview?> = _cleanPreview.asStateFlow()

    fun previewClean(uri: Uri, cleanLevel: CleanLevel?, convertTraditional: Boolean) {
        _cleanPreview.value = CleanPreview(loading = true, report = null)
        viewModelScope.launch {
            val report = importBook.preview(uri, buildCleanProfile(cleanLevel, convertTraditional))
            _cleanPreview.value = CleanPreview(loading = false, report = report)
        }
    }

    fun consumeCleanPreview() {
        _cleanPreview.value = null
    }

    /**
     * 组装一次导入要用的清洗配方。
     *
     * - [cleanLevel] 为 null = 这次不清理（[CleanProfile.NONE]，不物化副本）。
     * - 繁简是正交偏好，单独由 [convertTraditional] 决定，不随档位走。
     * - 自定义广告正则始终参与——那是用户显式配的规则，不该再要一个开关去启用。
     */
    private suspend fun buildCleanProfile(
        cleanLevel: CleanLevel?,
        convertTraditional: Boolean,
    ): CleanProfile {
        if (cleanLevel == null) return CleanProfile.NONE
        val prefs = settingsRepository.preferences.first()
        val base = if (cleanLevel == CleanLevel.CUSTOM) {
            prefs.cleanToggles
        } else {
            CleanToggles.preset(cleanLevel)
        }
        return CleanProfile(
            level = cleanLevel,
            toggles = base.copy(traditionalToSimplified = convertTraditional),
            adPatterns = prefs.adCleanRules.mapNotNull { runCatching { Regex(it) }.getOrNull() },
        )
    }

    /**
     * 「智能整理」的预览：按 [cleanLevel] 只跑清洗、不落盘，报告走 [cleanPreview] 状态。
     * [cleanLevel] 为 null 表示「撤销清理」，此时没有可预览的改动。
     */
    fun previewReclean(bookId: Long, cleanLevel: CleanLevel?, convertTraditional: Boolean) {
        if (cleanLevel == null) {
            _cleanPreview.value = CleanPreview(loading = false, report = CleanReport())
            return
        }
        _cleanPreview.value = CleanPreview(loading = true, report = null)
        viewModelScope.launch {
            val book = bookshelfRepository.getBook(bookId)
            val report = if (book == null) {
                null
            } else {
                importBook.preview(book.fileUri, buildCleanProfile(cleanLevel, convertTraditional))
            }
            _cleanPreview.value = CleanPreview(loading = false, report = report)
        }
    }

    /**
     * 对已导入的书重新清洗。会**重建目录**；进度与书签/标注的字符偏移可能变化。
     *
     * 每次都是从**原始源文件**重洗，所以在同一本书上反复换档位是安全的：
     * 结果只取决于「原文 + 本次配方」，不会在上一版副本上叠加。[cleanLevel] 为 null 时撤销清理，
     * 阅读器回到直接读原文件。
     */
    fun recleanBook(
        bookId: Long,
        cleanLevel: CleanLevel?,
        convertTraditional: Boolean,
        onResult: (String) -> Unit,
    ) {
        viewModelScope.launch {
            val profile = buildCleanProfile(cleanLevel, convertTraditional)
            val message = when (val outcome = reclean.reclean(bookId, profile)) {
                is RecleanBookUseCase.Outcome.Done ->
                    if (!outcome.changed) {
                        "按当前设置没有需要改动的内容"
                    } else if (cleanLevel == null) {
                        rebuildChaptersNow(bookId)
                        "已撤销清理，阅读器恢复读取原文件；目录已重建"
                    } else {
                        rebuildChaptersNow(bookId)
                        "智能整理完成：${outcome.report.summary()}。目录已重建，进度与书签位置可能变化"
                    }

                is RecleanBookUseCase.Outcome.Failure -> "智能整理失败：${outcome.message}"
            }
            onResult(message)
        }
    }

    private val _batchImportState = MutableStateFlow<BatchImportUiState>(BatchImportUiState.Idle)
    val batchImportState: StateFlow<BatchImportUiState> = _batchImportState.asStateFlow()
    private var batchJob: kotlinx.coroutines.Job? = null

    fun enumerateBatchDirectory(treeUri: Uri) {
        if (_batchImportState.value != BatchImportUiState.Idle) return
        _batchImportState.value = BatchImportUiState.Enumerating
        batchJob = viewModelScope.launch {
            _batchImportState.value = runCatching { batchImport.enumerate(treeUri) }.fold(
                onSuccess = { result ->
                    BatchImportUiState.Confirming(
                        defaultGroupName = BatchImportUseCase.defaultGroupName(
                            batchImport.treeDisplayName(treeUri),
                        ),
                        result = result,
                    )
                },
                onFailure = { BatchImportUiState.Error(it.message ?: "目录扫描失败") },
            )
        }
    }

    fun startBatchImport(entries: List<BatchImportUseCase.DocEntry>, groupName: String) {
        if (_batchImportState.value !is BatchImportUiState.Confirming) return
        _batchImportState.value = BatchImportUiState.Importing(0, entries.size, "")
        batchJob = viewModelScope.launch {
            val result = batchImport.importDirectory(entries, groupName) { done, total, name ->
                _batchImportState.value = BatchImportUiState.Importing(done, total, name)
            }
            _batchImportState.value = BatchImportUiState.Done(result)
        }
    }

    fun cancelBatchImport() {
        batchJob?.cancel()
    }

    fun consumeBatchImportState() {
        _batchImportState.value = BatchImportUiState.Idle
    }

    fun toggleViewMode() {
        viewModelScope.launch { settingsRepository.setBookshelfGridView(!gridView.value) }
    }

    fun toggleSelection(bookId: Long) {
        _selectedIds.value = _selectedIds.value.let { ids ->
            if (bookId in ids) ids - bookId else ids + bookId
        }
    }

    fun clearSelection() {
        _selectedIds.value = emptySet()
    }

    suspend fun bookDetail(bookId: Long): Triple<com.llzx373.foldreader.core.data.db.BookEntity?, com.llzx373.foldreader.core.data.db.ReadingProgressEntity?, Int> =
        Triple(
            bookshelfRepository.getBook(bookId),
            bookshelfRepository.getProgress(bookId),
            bookshelfRepository.getReadingDayCount(bookId),
        )

    fun deleteSelected(deleteLocalData: Boolean = true) {
        val ids = _selectedIds.value.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            bookshelfRepository.deleteBooks(ids, deleteLocalData)
            _selectedIds.value = emptySet()
        }
    }

    fun moveSelectedToGroup(groupName: String?) {
        val ids = _selectedIds.value.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            bookshelfRepository.updateGroup(ids, groupName)
            _selectedIds.value = emptySet()
        }
    }

    fun deleteGroup(groupName: String) {
        viewModelScope.launch { bookshelfRepository.clearGroup(groupName) }
    }

    /** [charsetName] 为 null 表示恢复自动检测（库存空串）。 */
    fun setEncoding(bookId: Long, charsetName: String?) {
        viewModelScope.launch { bookshelfRepository.updateEncoding(bookId, charsetName ?: "") }
    }

    fun rebuildChapters(bookId: Long) {
        viewModelScope.launch { rebuildChaptersNow(bookId) }
    }

    /** 重建目录的实体。作废偏移索引 → 清空章节 → 用最新规则重扫（此时读的是当前副本）。 */
    private suspend fun rebuildChaptersNow(bookId: Long) {
        val book = bookshelfRepository.getBook(bookId) ?: return
        offsetIndexStore.invalidate(bookId.toString())
        bookshelfRepository.saveChapters(bookId, emptyList())
        val override = EncodingDetector.forNameOrNull(book.encoding)
        val scanned = runCatching {
            parsers.parserFor(book.format).parseChapters(Uri.parse(book.fileUri), override)
        }.getOrDefault(emptyList())
        bookshelfRepository.saveChapters(bookId, scanned)
    }

    /**
     * 「复制到本地」：把漫画内容解包进应用私有目录，此后不再依赖外部授权。
     * [onResult] 回传提示文案（成功/失败都要让用户知道）。
     */
    fun copyComicLocal(bookId: Long, onResult: (String) -> Unit) {
        viewModelScope.launch {
            val message = runCatching { comicImport.copyLocal(bookId) }
                .fold(
                    onSuccess = { "已复制到本地，源文件不再需要保持可读" },
                    onFailure = { "复制失败：${it.message ?: "未知错误"}" },
                )
            onResult(message)
        }
    }

    /** 删除本地副本，回到引用外部源。 */
    fun removeComicLocal(bookId: Long, onResult: (String) -> Unit) {
        viewModelScope.launch {
            val message = runCatching { comicImport.removeLocalCopy(bookId) }
                .fold(
                    onSuccess = { "已删除本地副本" },
                    onFailure = { "删除失败：${it.message ?: "未知错误"}" },
                )
            onResult(message)
        }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                BookshelfViewModel(
                    importBook = container.importBookUseCase,
                    batchImport = container.batchImportUseCase,
                    bookshelfRepository = container.bookshelfRepository,
                    settingsRepository = container.settingsRepository,
                    parsers = container.bookParsers,
                    offsetIndexStore = container.offsetIndexStore,
                    comicImport = container.comicImportUseCase,
                    reclean = container.recleanBookUseCase,
                )
            }
        }
    }
}

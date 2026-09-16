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
import com.llzx373.foldreader.core.format.TextCleaner
import com.llzx373.foldreader.feature.importer.BatchImportUseCase
import com.llzx373.foldreader.feature.importer.ImportBookUseCase
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

sealed interface ImportUiState {    data object Idle : ImportUiState

    /** [progress] 小于 0 表示不确定进度（未启用清理）。 */
    data class Importing(val progress: Float = -1f) : ImportUiState
    data class Imported(
        val bookId: Long,
        val title: String,
        val lowEncodingConfidence: Boolean,
        val openAfter: Boolean,
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
        removeBlankLines: Boolean = false,
        removeAdLines: Boolean = false,
        traditionalToSimplified: Boolean = false,
    ) {
        if (_importState.value is ImportUiState.Importing) return
        viewModelScope.launch {
            val adPatterns = if (removeAdLines) {
                settingsRepository.preferences.first().adCleanRules
                    .mapNotNull { runCatching { Regex(it) }.getOrNull() }
            } else {
                emptyList()
            }
            val options = TextCleaner.CleanOptions(
                removeBlankLines = removeBlankLines,
                adPatterns = adPatterns,
                traditionalToSimplified = traditionalToSimplified,
            )
            _importState.value = ImportUiState.Importing(if (options.isNoop) -1f else 0f)
            val result = importBook.import(uri, options) { progress ->
                _importState.value = ImportUiState.Importing(progress)
            }
            _importState.value = when (result) {
                is ImportBookUseCase.Result.Imported ->
                    ImportUiState.Imported(
                        result.bookId,
                        result.title,
                        lowEncodingConfidence = result.encodingConfidence < 0.5f,
                        openAfter = openAfterImport,
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
        viewModelScope.launch {
            val book = bookshelfRepository.getBook(bookId) ?: return@launch
            offsetIndexStore.invalidate(bookId.toString())
            bookshelfRepository.saveChapters(bookId, emptyList())
            val override = EncodingDetector.forNameOrNull(book.encoding)
            val scanned = runCatching {
                parsers.parserFor(book.format).parseChapters(Uri.parse(book.fileUri), override)
            }.getOrDefault(emptyList())
            bookshelfRepository.saveChapters(bookId, scanned)
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
                )
            }
        }
    }
}

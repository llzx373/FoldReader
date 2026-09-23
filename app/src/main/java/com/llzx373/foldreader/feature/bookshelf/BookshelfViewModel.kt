package com.llzx373.foldreader.feature.bookshelf

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.llzx373.foldreader.AppContainer
import com.llzx373.foldreader.core.data.db.BookFormat
import com.llzx373.foldreader.core.data.db.BookWithProgress
import com.llzx373.foldreader.core.data.repository.BookshelfRepository
import com.llzx373.foldreader.core.data.settings.BookshelfSort
import com.llzx373.foldreader.core.data.settings.SettingsRepository
import com.llzx373.foldreader.core.format.EncodingDetector
import com.llzx373.foldreader.core.format.FormatDetector
import com.llzx373.foldreader.core.format.clean.CleanLevel
import com.llzx373.foldreader.core.format.clean.CleanProfile
import com.llzx373.foldreader.core.format.clean.CleanReport
import com.llzx373.foldreader.core.format.txt.UriChannels
import com.llzx373.foldreader.feature.importer.BatchImportUseCase
import com.llzx373.foldreader.feature.importer.CleanProfileFactory
import com.llzx373.foldreader.feature.importer.ComicImportUseCase
import com.llzx373.foldreader.feature.importer.ImportBookUseCase
import com.llzx373.foldreader.feature.importer.RecleanBookUseCase
import java.io.File
import java.nio.channels.Channels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private val appContext: Context,
    private val importBook: ImportBookUseCase,
    private val batchImport: BatchImportUseCase,
    private val bookshelfRepository: BookshelfRepository,
    private val settingsRepository: SettingsRepository,
    private val comicImport: ComicImportUseCase,
    private val reclean: RecleanBookUseCase,
    private val cleanProfileFactory: CleanProfileFactory,
    /** 重建目录（作废索引 → 重扫 → 落库），实现挂在容器上，与 M15 AI 章节规则共用。 */
    private val rebuildBookChapters: suspend (Long) -> Unit,
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
                // 「同一路径」与「内容相同」现在都只是"已在书架"：来源 URI 已改成私有副本，
                // 判定本身是内容判定。外部送进来的文件照样要打开，所以两者都用 openAfterImport。
                is ImportBookUseCase.Result.DuplicateSameUri ->
                    ImportUiState.Duplicate(result.bookId, result.title, openAfter = openAfterImport)
                is ImportBookUseCase.Result.DuplicateSameHash ->
                    ImportUiState.Duplicate(result.bookId, result.title, openAfter = openAfterImport)
                is ImportBookUseCase.Result.Failure ->
                    ImportUiState.Error(result.message ?: "未知错误")
            }
        }
    }

    fun consumeImportState() {
        _importState.value = ImportUiState.Idle
    }

    /**
     * 非 TXT 格式（PDF/EPUB/漫画…）没有文本可「整理」，跳过清洗选项对话框直接导入。
     *
     * 返回 true 表示已按原样直接导入；false 表示是 TXT（或识别不出），调用方照常弹导入选项。
     */
    suspend fun importNonTxtDirectly(uri: Uri, openAfterImport: Boolean): Boolean {
        val format = withContext(Dispatchers.IO) {
            runCatching {
                UriChannels.open(appContext, uri).use { channel ->
                    FormatDetector.detect(
                        displayName = UriChannels.displayName(appContext, uri),
                        mimeType = appContext.contentResolver.getType(uri),
                        head = UriChannels.readHead(channel, EncodingDetector.SAMPLE_SIZE),
                    )
                }
            }.getOrNull()
        }
        if (format == null || format == BookFormat.TXT) return false
        import(uri, openAfterImport, cleanLevel = null, convertTraditional = false)
        return true
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
     * 把这本书的正文另存到用户选的位置。
     *
     * [cleanedCopy] = true 导出**清洗副本**（阅读器实际读的那一份），false 导出原文。
     * 库里的副本看不见摸不着，光看阅读页的排版很难判断清洗到底生效没有——留这个出口，
     * 就是为了能在手机上把两份文本各导一份出来并排比（或传回电脑上 diff）。
     */
    fun exportText(
        bookId: Long,
        cleanedCopy: Boolean,
        target: Uri,
        onResult: (String) -> Unit,
    ) {
        viewModelScope.launch {
            val message = withContext(Dispatchers.IO) {
                runCatching {
                    val book = bookshelfRepository.getBook(bookId) ?: error("书籍不存在")
                    val cleanedPath = book.cleanedFilePath
                    if (cleanedCopy && cleanedPath == null) error("这本书没有清洗副本")
                    val sourceKey = if (cleanedCopy) {
                        Uri.fromFile(File(cleanedPath!!)).toString()
                    } else {
                        book.fileUri
                    }
                    val output = appContext.contentResolver.openOutputStream(target)
                        ?: error("无法写入所选位置")
                    UriChannels.open(appContext, Uri.parse(sourceKey)).use { channel ->
                        output.use { out ->
                            val copied = Channels.newInputStream(channel).copyTo(out)
                            out.flush()
                            copied
                        }
                    }
                }.fold(
                    onSuccess = { bytes -> "已导出 ${bytes / 1024} KB" },
                    onFailure = { "导出失败：${it.message ?: "未知错误"}" },
                )
            }
            onResult(message)
        }
    }

    /**
     * 组装一次导入要用的清洗配方。
     *
     * 规则本体在 [CleanProfileFactory]（浏览与批量导入共用同一份），这里只是把对话框的选择转过去。
     */
    private suspend fun buildCleanProfile(
        cleanLevel: CleanLevel?,
        convertTraditional: Boolean,
    ): CleanProfile = cleanProfileFactory.forLevel(cleanLevel, convertTraditional)

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
                        rebuildBookChapters(bookId)
                        "已撤销清理，阅读器恢复读取原文件；目录已重建"
                    } else {
                        rebuildBookChapters(bookId)
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
        viewModelScope.launch { rebuildBookChapters(bookId) }
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
                    appContext = container.appContext,
                    importBook = container.importBookUseCase,
                    batchImport = container.batchImportUseCase,
                    bookshelfRepository = container.bookshelfRepository,
                    settingsRepository = container.settingsRepository,
                    comicImport = container.comicImportUseCase,
                    reclean = container.recleanBookUseCase,
                    cleanProfileFactory = container.cleanProfileFactory,
                    rebuildBookChapters = container::rebuildBookChapters,
                )
            }
        }
    }
}

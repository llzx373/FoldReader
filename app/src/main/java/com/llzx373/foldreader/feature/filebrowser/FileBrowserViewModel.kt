package com.llzx373.foldreader.feature.filebrowser

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.llzx373.foldreader.AppContainer
import com.llzx373.foldreader.core.comic.ComicContainer
import com.llzx373.foldreader.core.comic.ComicContainers
import com.llzx373.foldreader.core.data.db.BookSource
import com.llzx373.foldreader.core.data.settings.FileBrowserRootsStore
import com.llzx373.foldreader.core.format.clean.CleanProfile
import com.llzx373.foldreader.core.format.isSupportedBookName
import com.llzx373.foldreader.core.format.saf.SafTree
import com.llzx373.foldreader.feature.importer.CleanProfileFactory
import com.llzx373.foldreader.feature.importer.ComicImportUseCase
import com.llzx373.foldreader.feature.importer.ImportBookUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 已授权的 SAF 根目录。 */
data class BrowserRoot(val treeUri: String, val name: String)

/** 目录栈中的一层（根层 = path 为空）。 */
data class BrowserDir(val name: String, val treeUri: String, val documentId: String)

/** 当前目录中的一个条目：子目录或 TXT 文件。 */
data class BrowserEntry(
    val name: String,
    val uri: Uri,
    val documentId: String,
    val isDirectory: Boolean,
)

sealed interface FileBrowserEvent {
    data class OpenBook(val bookId: Long, val title: String) : FileBrowserEvent
    data class Error(val message: String) : FileBrowserEvent
}

class FileBrowserViewModel(
    private val context: Context,
    private val rootsStore: FileBrowserRootsStore,
    private val importBook: ImportBookUseCase,
    private val safTree: SafTree,
    private val comicImport: ComicImportUseCase,
    private val cleanProfileFactory: CleanProfileFactory,
) : ViewModel() {

    // null = 尚未加载（DataStore 首次发射前），用于区分"加载中"与"无授权根"
    val roots: StateFlow<List<BrowserRoot>?> = rootsStore.roots
        .map { uris -> uris.mapNotNull { rootOf(it) }.sortedBy { it.name.lowercase() } }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _path = MutableStateFlow<List<BrowserDir>>(emptyList())
    val path: StateFlow<List<BrowserDir>> = _path.asStateFlow()

    private val _entries = MutableStateFlow<List<BrowserEntry>>(emptyList())
    val entries: StateFlow<List<BrowserEntry>> = _entries.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _openingFile = MutableStateFlow(false)
    val openingFile: StateFlow<Boolean> = _openingFile.asStateFlow()

    /** 打开文件的进度（0..1）；负数表示不确定（未启用清洗时没有进度可报）。 */
    private val _openingProgress = MutableStateFlow(-1f)
    val openingProgress: StateFlow<Float> = _openingProgress.asStateFlow()

    private val _events = MutableSharedFlow<FileBrowserEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<FileBrowserEvent> = _events.asSharedFlow()

    fun addRoot(treeUri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        viewModelScope.launch { rootsStore.addRoot(treeUri.toString()) }
    }

    fun removeRoot(root: BrowserRoot) {
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                Uri.parse(root.treeUri),
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        viewModelScope.launch { rootsStore.removeRoot(root.treeUri) }
        // 正在浏览被移除的授权根内部时，退回根层
        if (_path.value.isNotEmpty() && _path.value.first().treeUri == root.treeUri) {
            _path.value = emptyList()
            _entries.value = emptyList()
        }
    }

    fun enterRoot(root: BrowserRoot) {
        val treeUri = Uri.parse(root.treeUri)
        _path.value = listOf(
            BrowserDir(root.name, root.treeUri, safTree.treeDocumentId(treeUri)),
        )
        refresh()
    }

    fun enterDirectory(entry: BrowserEntry) {
        if (!entry.isDirectory) return
        val current = _path.value.lastOrNull() ?: return
        _path.value = _path.value + BrowserDir(entry.name, current.treeUri, entry.documentId)
        refresh()
    }

    fun goUp() {
        if (_path.value.isEmpty()) return
        _path.value = _path.value.dropLast(1)
        if (_path.value.isEmpty()) {
            _entries.value = emptyList()
        } else {
            refresh()
        }
    }

    fun refresh() {
        val dir = _path.value.lastOrNull() ?: return
        viewModelScope.launch {
            _loading.value = true
            _entries.value = withContext(Dispatchers.IO) {
                listEntries(Uri.parse(dir.treeUri), dir.documentId)
            }
            _loading.value = false
        }
    }

    /**
     * 打开一本小说。
     *
     * 走的是和书架导入同一条清洗链路（跟随设置页的档位）——原先这里传的是默认的
     * `CleanProfile.NONE`，于是同一个文件从「浏览」进来永远读不到清洗结果。
     * 导入会把原文件复制一份进应用私有目录（`filesDir/source/`），库里只引用这一份，
     * 之后不受外部授权存续的影响。
     * 大文件清洗要花时间，进度接到 [openingProgress] 上，不再是一动不动地「正在打开…」。
     */
    fun openFile(entry: BrowserEntry) {
        if (entry.isDirectory || _openingFile.value) return
        viewModelScope.launch {
            _openingFile.value = true
            _openingProgress.value = -1f
            // 树授权已覆盖子文档，这里尽力再取一次持久化权限，失败不影响后续读取
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    entry.uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            val profile = runCatching { cleanProfileFactory.fromSettings() }
                .getOrDefault(CleanProfile.NONE)
            val result = importBook.import(
                uri = entry.uri,
                profile = profile,
                source = BookSource.EXTERNAL,
            ) { progress -> _openingProgress.value = progress }
            _openingFile.value = false
            _openingProgress.value = -1f
            when (result) {
                is ImportBookUseCase.Result.Imported ->
                    _events.emit(FileBrowserEvent.OpenBook(result.bookId, result.title))
                is ImportBookUseCase.Result.DuplicateSameUri ->
                    _events.emit(FileBrowserEvent.OpenBook(result.bookId, result.title))
                is ImportBookUseCase.Result.DuplicateSameHash ->
                    _events.emit(FileBrowserEvent.OpenBook(result.bookId, result.title))
                is ImportBookUseCase.Result.Failure ->
                    _events.emit(FileBrowserEvent.Error(result.message ?: "未知错误"))
            }
        }
    }

    /**
     * 「以漫画打开」：目录 = 一本目录漫画，容器文件 = 一本压缩包漫画。
     * 只登记元数据（引用外部，不复制），登记完直接进阅读器。
     */
    fun openAsComic(entry: BrowserEntry) {
        if (_openingFile.value) return
        viewModelScope.launch {
            _openingFile.value = true
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    entry.uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            val container = if (entry.isDirectory) {
                ComicContainer.FOLDER
            } else {
                ComicContainers.fromExtension(entry.name) ?: ComicContainer.ZIP
            }
            val result = comicImport.register(
                uri = entry.uri,
                container = container,
                source = BookSource.EXTERNAL,
            )
            _openingFile.value = false
            when (result) {
                is ComicImportUseCase.Outcome.Registered ->
                    _events.emit(FileBrowserEvent.OpenBook(result.bookId, result.title))
                is ComicImportUseCase.Outcome.Duplicate ->
                    _events.emit(FileBrowserEvent.OpenBook(result.bookId, result.title))
                is ComicImportUseCase.Outcome.Failure ->
                    _events.emit(FileBrowserEvent.Error(result.message ?: "无法作为漫画打开"))
            }
        }
    }

    private fun rootOf(treeUri: String): BrowserRoot? = runCatching {
        val uri = Uri.parse(treeUri)
        val docUri = safTree.documentUri(uri, safTree.treeDocumentId(uri))
        val name = safTree.displayName(docUri) ?: uri.lastPathSegment ?: treeUri
        BrowserRoot(treeUri, name)
    }.getOrNull()

    /**
     * 单次批量查询拿全目录元数据，再按支持格式过滤。
     * 过滤规则与批量导入共用（`isSupportedBookName`），避免「浏览里看得到、导入时收不到」。
     */
    private fun listEntries(treeUri: Uri, documentId: String): List<BrowserEntry> {
        val t0 = System.nanoTime()
        val children = safTree.listChildren(treeUri, documentId)
        val tQuery = System.nanoTime()
        val entries = children
            .filter { it.isDirectory || isSupportedBook(it.name, it.mimeType) }
            .map {
                BrowserEntry(
                    name = it.name,
                    uri = it.uri,
                    documentId = it.documentId,
                    isDirectory = it.isDirectory,
                )
            }
        val result = entries.sortedWith(
            compareByDescending<BrowserEntry> { it.isDirectory }.thenBy { it.name.lowercase() },
        )
        logListTiming(t0, tQuery, result.size)
        return result
    }

    private fun logListTiming(t0Nanos: Long, tQueryNanos: Long = t0Nanos, count: Int) {
        if (!com.llzx373.foldreader.BuildConfig.DEBUG) return
        val queryMs = (tQueryNanos - t0Nanos) / 1_000_000L
        val ms = (System.nanoTime() - t0Nanos) / 1_000_000L
        android.util.Log.d("FileBrowserPerf", "listed $count entries in ${ms}ms (query ${queryMs}ms)")
    }

    private fun isSupportedBook(name: String, mimeType: String?): Boolean =
        isSupportedBookName(name, mimeType)

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                FileBrowserViewModel(
                    context = container.appContext,
                    rootsStore = container.fileBrowserRootsStore,
                    importBook = container.importBookUseCase,
                    safTree = container.safTree,
                    comicImport = container.comicImportUseCase,
                    cleanProfileFactory = container.cleanProfileFactory,
                )
            }
        }
    }
}

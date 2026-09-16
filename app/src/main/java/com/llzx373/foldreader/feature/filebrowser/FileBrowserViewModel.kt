package com.llzx373.foldreader.feature.filebrowser

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.llzx373.foldreader.AppContainer
import com.llzx373.foldreader.core.data.db.BookSource
import com.llzx373.foldreader.core.data.settings.FileBrowserRootsStore
import com.llzx373.foldreader.core.format.TextCleaner
import com.llzx373.foldreader.core.format.isSupportedBookName
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
            BrowserDir(root.name, root.treeUri, DocumentsContract.getTreeDocumentId(treeUri)),
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

    fun openFile(entry: BrowserEntry) {
        if (entry.isDirectory || _openingFile.value) return
        viewModelScope.launch {
            _openingFile.value = true
            // 树授权已覆盖子文档，这里尽力再取一次持久化权限，失败不影响后续读取
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    entry.uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            val result = importBook.import(
                uri = entry.uri,
                options = TextCleaner.CleanOptions(),
                source = BookSource.EXTERNAL,
            )
            _openingFile.value = false
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

    private fun rootOf(treeUri: String): BrowserRoot? = runCatching {
        val uri = Uri.parse(treeUri)
        val docUri = DocumentsContract.buildDocumentUriUsingTree(
            uri,
            DocumentsContract.getTreeDocumentId(uri),
        )
        val name = queryDisplayName(docUri) ?: uri.lastPathSegment ?: treeUri
        BrowserRoot(treeUri, name)
    }.getOrNull()

    private fun queryDisplayName(docUri: Uri): String? =
        context.contentResolver.query(
            docUri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null,
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    /**
     * 单次批量查询拿全目录元数据：一次 ContentResolver IPC 返回全部子项的
     * id/名称/MIME（DocumentFile 路线每个子项每个属性都是一次独立 IPC，大目录会卡数秒）。
     */
    private fun listEntries(treeUri: Uri, documentId: String): List<BrowserEntry> {
        val t0 = System.nanoTime()
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val entries = ArrayList<BrowserEntry>()
        val cursor = context.contentResolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            ),
            null, null, null,
        )
        val tQuery = System.nanoTime()
        cursor?.use { cursor ->
            while (cursor.moveToNext()) {
                val childId = cursor.getString(0) ?: continue
                val name = cursor.getString(1) ?: continue
                val mime = cursor.getString(2)
                val isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR
                if (!isDirectory && !isSupportedBook(name, mime)) continue
                entries += BrowserEntry(
                    name = name,
                    uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId),
                    documentId = childId,
                    isDirectory = isDirectory,
                )
            }
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
                )
            }
        }
    }
}

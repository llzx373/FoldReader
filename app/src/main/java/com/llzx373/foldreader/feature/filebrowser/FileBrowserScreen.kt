package com.llzx373.foldreader.feature.filebrowser

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.llzx373.foldreader.FoldReaderApplication
import com.llzx373.foldreader.feature.importer.BatchImportConfirmDialog
import com.llzx373.foldreader.feature.importer.BatchImportProgressOverlay
import com.llzx373.foldreader.feature.importer.BatchImportSummaryDialog
import com.llzx373.foldreader.feature.importer.BatchImportUseCase
import com.llzx373.foldreader.ui.EmptyState
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FileBrowserScreen(
    onOpenBook: (bookId: Long, title: String) -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as FoldReaderApplication
    val viewModel: FileBrowserViewModel = viewModel(factory = FileBrowserViewModel.factory(app.container))
    val roots by viewModel.roots.collectAsState()
    val path by viewModel.path.collectAsState()
    val entries by viewModel.entries.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val openingFile by viewModel.openingFile.collectAsState()
    val openingProgress by viewModel.openingProgress.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var removeRootTarget by remember { mutableStateOf<BrowserRoot?>(null) }
    // 目录批量导入的本地状态机（枚举中/待确认/进度/汇总），复用书架同款对话框
    val batchImport = remember { app.container.batchImportUseCase }
    var batchEnumerating by remember { mutableStateOf(false) }
    var batchConfirm by remember {
        mutableStateOf<Pair<String, BatchImportUseCase.EnumerateResult>?>(null)
    }
    var batchProgress by remember { mutableStateOf<Triple<Int, Int, String>?>(null) }
    var batchSummary by remember { mutableStateOf<BatchImportUseCase.BatchResult?>(null) }
    var batchJob by remember { mutableStateOf<Job?>(null) }

    val startDirectoryImport: (BrowserEntry) -> Unit = { entry ->
        val treeUriString = path.lastOrNull()?.treeUri
        if (treeUriString != null && !batchEnumerating && batchProgress == null) {
            batchEnumerating = true
            scope.launch {
                val result = runCatching {
                    batchImport.enumerate(Uri.parse(treeUriString), entry.documentId)
                }
                batchEnumerating = false
                result.onSuccess { batchConfirm = entry.name to it }
                    .onFailure {
                        snackbarHostState.showSnackbar("目录扫描失败：${it.message ?: "未知错误"}")
                    }
            }
        }
    }

    val treeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) viewModel.addRoot(uri)
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is FileBrowserEvent.OpenBook -> onOpenBook(event.bookId, event.title)
                is FileBrowserEvent.Error ->
                    snackbarHostState.showSnackbar("打开失败：${event.message}")
            }
        }
    }

    BackHandler(enabled = path.isNotEmpty()) { viewModel.goUp() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (path.isEmpty()) "浏览" else path.joinToString(" / ") { it.name },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    if (path.isNotEmpty()) {
                        IconButton(onClick = viewModel::goUp) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回上一级")
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { treeLauncher.launch(null) }) {
                        Icon(Icons.Filled.Add, contentDescription = "添加文件夹")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            val currentRoots = roots
            when {
                currentRoots == null ->
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        LoadingIndicator()
                    }
                currentRoots.isEmpty() && path.isEmpty() -> EmptyState(
                    title = "还没有授权文件夹",
                    description = "授权一个文件夹后直接浏览其中的电子书；打开时会把它复制一份进" +
                        "应用内并入库（按设置页的清洗档位处理），原文件不会被改动",
                    actionLabel = "选择文件夹",
                    onAction = { treeLauncher.launch(null) },
                    illustration = { BrowserFolderIllustration() },
                )
                path.isEmpty() -> RootList(
                    roots = currentRoots,
                    onEnter = viewModel::enterRoot,
                    onRemove = { removeRootTarget = it },
                )
                else -> EntryList(
                    entries = entries,
                    loading = loading,
                    onEnter = viewModel::enterDirectory,
                    onOpen = viewModel::openFile,
                    onImportDirectory = startDirectoryImport,
                    onOpenAsComic = viewModel::openAsComic,
                )
            }
            if (openingFile || batchEnumerating) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        LoadingIndicator()
                        Text(
                            text = when {
                                batchEnumerating -> "正在扫描目录…"
                                openingProgress >= 0f -> "正在打开… ${(openingProgress * 100).toInt()}%"
                                else -> "正在打开…"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                }
            }
            batchProgress?.let { (done, total, name) ->
                BatchImportProgressOverlay(
                    done = done,
                    total = total,
                    currentName = name,
                    onCancel = { batchJob?.cancel() },
                )
            }
        }
    }

    batchConfirm?.let { (dirName, enumResult) ->
        BatchImportConfirmDialog(
            defaultGroupName = BatchImportUseCase.defaultGroupName(dirName),
            foundCount = enumResult.entries.size,
            truncated = enumResult.truncated,
            onConfirm = { groupName ->
                batchConfirm = null
                batchProgress = Triple(0, enumResult.entries.size, "")
                batchJob = scope.launch {
                    val batchResult = batchImport.importDirectory(
                        enumResult.entries,
                        groupName,
                    ) { done, total, name ->
                        batchProgress = Triple(done, total, name)
                    }
                    batchProgress = null
                    batchSummary = batchResult
                }
            },
            onDismiss = { batchConfirm = null },
        )
    }

    batchSummary?.let { result ->
        BatchImportSummaryDialog(
            result = result,
            onDismiss = { batchSummary = null },
        )
    }

    removeRootTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { removeRootTarget = null },
            title = { Text("移除授权") },
            text = { Text("不再访问文件夹“${target.name}”？已加入书架的书籍不受影响。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.removeRoot(target)
                        removeRootTarget = null
                    },
                ) {
                    Text("移除")
                }
            },
            dismissButton = {
                TextButton(onClick = { removeRootTarget = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun RootList(
    roots: List<BrowserRoot>,
    onEnter: (BrowserRoot) -> Unit,
    onRemove: (BrowserRoot) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(roots, key = { it.treeUri }) { root ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onEnter(root) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                BrowserFolderIcon(contentDescription = null)
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = root.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { onRemove(root) }) {
                    Icon(Icons.Filled.Close, contentDescription = "移除授权 ${root.name}")
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryList(
    entries: List<BrowserEntry>,
    loading: Boolean,
    onEnter: (BrowserEntry) -> Unit,
    onOpen: (BrowserEntry) -> Unit,
    onImportDirectory: (BrowserEntry) -> Unit,
    onOpenAsComic: (BrowserEntry) -> Unit,
) {
    if (!loading && entries.isEmpty()) {
        EmptyState(
            title = "此文件夹没有可导入的内容",
            description = "仅显示子文件夹与支持的书籍/漫画（TXT / EPUB / FB2 / PDF / CBZ / CBR / CBT / CB7）",
        )
        return
    }
    // 长按弹出的操作菜单目标
    var menuTarget by remember { mutableStateOf<BrowserEntry?>(null) }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(entries, key = { it.documentId }) { entry ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = { if (entry.isDirectory) onEnter(entry) else onOpen(entry) },
                        onLongClick = { menuTarget = entry },
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                if (entry.isDirectory) {
                    BrowserFolderIcon(contentDescription = null)
                } else {
                    BrowserDocumentIcon(contentDescription = null)
                }
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (entry.isDirectory) {
                    IconButton(onClick = { onImportDirectory(entry) }) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = "将「${entry.name}」全部导入为分组",
                        )
                    }
                }
            }
            // 目录也能"以漫画打开"（一个图片目录 = 一本），文件则是容器的直接入口
            DropdownMenu(
                expanded = menuTarget?.documentId == entry.documentId,
                onDismissRequest = { menuTarget = null },
            ) {
                DropdownMenuItem(
                    text = { Text("以漫画打开") },
                    onClick = {
                        menuTarget = null
                        onOpenAsComic(entry)
                    },
                )
                if (entry.isDirectory) {
                    DropdownMenuItem(
                        text = { Text("整个目录导入为分组") },
                        onClick = {
                            menuTarget = null
                            onImportDirectory(entry)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun BrowserFolderIcon(contentDescription: String?) {
    val tint = MaterialTheme.colorScheme.primary
    Canvas(
        modifier = Modifier
            .size(24.dp)
            .semantics { if (contentDescription != null) this.contentDescription = contentDescription },
    ) {
        val w = size.width
        val h = size.height
        val corner = CornerRadius(h * 0.1f, h * 0.1f)
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.08f, h * 0.2f),
            size = Size(w * 0.42f, h * 0.18f),
            cornerRadius = corner,
        )
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.08f, h * 0.32f),
            size = Size(w * 0.84f, h * 0.52f),
            cornerRadius = corner,
        )
    }
}

@Composable
private fun BrowserDocumentIcon(contentDescription: String?) {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(
        modifier = Modifier
            .size(24.dp)
            .semantics { if (contentDescription != null) this.contentDescription = contentDescription },
    ) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.22f, h * 0.08f)
            lineTo(w * 0.62f, h * 0.08f)
            lineTo(w * 0.78f, h * 0.26f)
            lineTo(w * 0.78f, h * 0.92f)
            lineTo(w * 0.22f, h * 0.92f)
            close()
        }
        drawPath(path, color = tint)
    }
}

@Composable
private fun BrowserFolderIllustration() {
    val tint = MaterialTheme.colorScheme.surfaceVariant
    Canvas(modifier = Modifier.size(96.dp)) {
        val w = size.width
        val h = size.height
        val corner = CornerRadius(h * 0.06f, h * 0.06f)
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.08f, h * 0.18f),
            size = Size(w * 0.42f, h * 0.16f),
            cornerRadius = corner,
        )
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.08f, h * 0.3f),
            size = Size(w * 0.84f, h * 0.56f),
            cornerRadius = corner,
        )
    }
}

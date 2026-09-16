package com.llzx373.foldreader.feature.filebrowser

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
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
import com.llzx373.foldreader.ui.EmptyState

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
    val snackbarHostState = remember { SnackbarHostState() }
    var removeRootTarget by remember { mutableStateOf<BrowserRoot?>(null) }

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
                    description = "授权一个文件夹，直接浏览并打开其中的 TXT 电子书，不会复制原文件",
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
                )
            }
            if (openingFile) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        LoadingIndicator()
                        Text(
                            text = "正在打开…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                }
            }
        }
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

@Composable
private fun EntryList(
    entries: List<BrowserEntry>,
    loading: Boolean,
    onEnter: (BrowserEntry) -> Unit,
    onOpen: (BrowserEntry) -> Unit,
) {
    if (!loading && entries.isEmpty()) {
        EmptyState(
            title = "此文件夹没有 TXT 书籍",
            description = "仅显示子文件夹和 .txt 文件",
        )
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(entries, key = { it.documentId }) { entry ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { if (entry.isDirectory) onEnter(entry) else onOpen(entry) }
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
                )
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

package com.llzx373.foldreader.feature.bookshelf

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.llzx373.foldreader.FoldReaderApplication
import com.llzx373.foldreader.core.data.db.BookFormat
import com.llzx373.foldreader.core.data.db.BookSource
import com.llzx373.foldreader.core.data.db.BookWithProgress
import com.llzx373.foldreader.core.data.db.needsContentPreparation
import com.llzx373.foldreader.core.data.settings.BookshelfSort
import com.llzx373.foldreader.core.debug.ReturnTrace
import com.llzx373.foldreader.core.foldable.FoldableUiState
import com.llzx373.foldreader.core.foldable.WidthCategory
import com.llzx373.foldreader.feature.importer.BatchImportConfirmDialog
import com.llzx373.foldreader.feature.importer.BatchImportProgressOverlay
import com.llzx373.foldreader.feature.importer.BatchImportSummaryDialog
import com.llzx373.foldreader.ui.EmptyState
import kotlinx.coroutines.launch

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalSharedTransitionApi::class,
)
@Composable
fun BookshelfScreen(
    foldableUiState: FoldableUiState,
    onOpenBook: (bookId: Long, title: String) -> Unit,
    onOpenBookAt: (bookId: Long, anchor: Long, title: String) -> Unit,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
) {
    val context = LocalContext.current
    val app = context.applicationContext as FoldReaderApplication
    val viewModel: BookshelfViewModel = viewModel(factory = BookshelfViewModel.factory(app.container))
    val books by viewModel.books.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val gridView by viewModel.gridView.collectAsState()
    val sortOrder by viewModel.sortOrder.collectAsState()
    val selectedIds by viewModel.selectedIds.collectAsState()
    val importState by viewModel.importState.collectAsState()
    val batchImportState by viewModel.batchImportState.collectAsState()
    val groups by viewModel.groups.collectAsState()
    val allBookmarks by viewModel.allBookmarks.collectAsState()
    val allAnnotations by viewModel.allAnnotations.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }
    var fabMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var searchActive by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var detailBookId by rememberSaveable { mutableStateOf(0L) }
    var showBookmarkOverview by rememberSaveable { mutableStateOf(false) }
    var showMoveToGroupDialog by rememberSaveable { mutableStateOf(false) }
    var importRequest by remember { mutableStateOf<Pair<Uri, Boolean>?>(null) }
    // null = 全部；"" = 未分组；其余为分组名
    var groupFilter by rememberSaveable { mutableStateOf<String?>(null) }
    val selectionMode = selectedIds.isNotEmpty()
    val displayBooks = remember(books, searchQuery, groupFilter) {
        val q = searchQuery.trim()
        val gf = groupFilter
        books.filter {
            (q.isEmpty() ||
                it.book.title.contains(q, ignoreCase = true) ||
                it.book.author?.contains(q, ignoreCase = true) == true) &&
                (gf == null || (if (gf.isEmpty()) it.book.groupName == null else it.book.groupName == gf))
        }
    }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val openDocumentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            importRequest = uri to false
        }
    }
    val openTreeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            viewModel.enumerateBatchDirectory(uri)
        }
    }
    val launchImport = {
        openDocumentLauncher.launch(
            arrayOf(
                "text/plain",
                "application/epub+zip",
                // FB2 无标准注册 MIME，靠扩展名 + octet-stream 兜底
                "application/x-fictionbook+xml",
                "application/octet-stream",
            ),
        )
    }

    LaunchedEffect(Unit) {
        app.container.pendingImportUri.collect { uri ->
            if (uri != null) {
                app.container.pendingImportUri.value = null
                importRequest = uri to true
            }
        }
    }

    LaunchedEffect(importState) {
        when (val state = importState) {
            is ImportUiState.Imported -> {
                if (state.openAfter) {
                    onOpenBook(state.bookId, state.title)
                } else {
                    snackbarHostState.showSnackbar("《${state.title}》已加入书架")
                    if (state.lowEncodingConfidence) {
                        snackbarHostState.showSnackbar("编码识别置信度低，如乱码可在书籍详情切换编码")
                    }
                }
                viewModel.consumeImportState()
            }
            is ImportUiState.Duplicate -> {
                if (state.sameFile && state.openAfter) onOpenBook(state.bookId, state.title)
                else snackbarHostState.showSnackbar("《${state.title}》已在书架")
                viewModel.consumeImportState()
            }
            is ImportUiState.Error -> {
                snackbarHostState.showSnackbar("导入失败：${state.message}")
                viewModel.consumeImportState()
            }
            else -> Unit
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            if (selectionMode) {
                TopAppBar(
                    title = { Text("已选 ${selectedIds.size} 本") },
                    navigationIcon = {
                        IconButton(onClick = viewModel::clearSelection) {
                            Icon(Icons.Filled.Close, contentDescription = "退出多选")
                        }
                    },
                    actions = {
                        if (selectedIds.size == 1) {
                            IconButton(onClick = { detailBookId = selectedIds.first() }) {
                                Icon(Icons.Filled.Info, contentDescription = "书籍详情")
                            }
                        }
                        IconButton(onClick = { showMoveToGroupDialog = true }) {
                            FolderIcon(contentDescription = "移动到分组")
                        }
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = "删除")
                        }
                    },
                )
            } else if (searchActive) {
                TopAppBar(
                    title = {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("搜索书名 / 作者") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            searchActive = false
                            searchQuery = ""
                        }) {
                            Icon(Icons.Filled.Close, contentDescription = "关闭搜索")
                        }
                    },
                )
            } else {
                LargeTopAppBar(
                    title = { Text("书架") },
                    actions = {
                        IconButton(onClick = { showBookmarkOverview = true }) {
                            BookmarkOverviewIcon(contentDescription = "书签与标注")
                        }
                        IconButton(onClick = { searchActive = true }) {
                            Icon(Icons.Filled.Search, contentDescription = "搜索书架")
                        }
                        var sortMenuExpanded by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { sortMenuExpanded = true }) {
                                SortIcon(contentDescription = "排序方式")
                            }
                            DropdownMenu(
                                expanded = sortMenuExpanded,
                                onDismissRequest = { sortMenuExpanded = false },
                            ) {
                                BookshelfSort.entries.forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(bookshelfSortLabel(option)) },
                                        onClick = {
                                            viewModel.setSortOrder(option)
                                            sortMenuExpanded = false
                                        },
                                        trailingIcon = {
                                            if (option == sortOrder) {
                                                Icon(Icons.Filled.Check, contentDescription = null)
                                            }
                                        },
                                    )
                                }
                            }
                        }
                        IconButton(onClick = viewModel::toggleViewMode) {
                            if (gridView) {
                                Icon(
                                    Icons.AutoMirrored.Filled.List,
                                    contentDescription = "切换为列表视图",
                                )
                            } else {
                                GridViewIcon(contentDescription = "切换为网格视图")
                            }
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            if (!selectionMode) {
                FloatingActionButtonMenu(
                    expanded = fabMenuExpanded,
                    button = {
                        ToggleFloatingActionButton(
                            checked = fabMenuExpanded,
                            onCheckedChange = { fabMenuExpanded = it },
                        ) {
                            Icon(
                                if (fabMenuExpanded) Icons.Filled.Close else Icons.Filled.Add,
                                contentDescription = "导入",
                            )
                        }
                    },
                ) {
                    FloatingActionButtonMenuItem(
                        onClick = {
                            fabMenuExpanded = false
                            launchImport()
                        },
                        icon = { Icon(Icons.Filled.Create, contentDescription = null) },
                        text = { Text("导入本地书籍") },
                    )
                    FloatingActionButtonMenuItem(
                        onClick = {
                            fabMenuExpanded = false
                            openTreeLauncher.launch(null)
                        },
                        icon = { FolderIcon(contentDescription = "导入目录为分组") },
                        text = { Text("导入目录为分组") },
                    )
                }
            }
        },
    ) { innerPadding ->
        // 返回书架跳动的诊断（仅 debug）：innerPadding 四边实际值 + 内容区在窗口中的
        // 位置。跳动的本质是外层 rail 宽度 = 80dp + start 系统栏 inset 变化，把整个内容区
        // （网格封面、右上动作、FAB）整体推移，所以必须同时看 padding 与 pos。
        val traceLayoutDirection = LocalLayoutDirection.current
        ReturnTrace.log(
            "bookshelf: innerPadding=(" +
                "${innerPadding.calculateLeftPadding(traceLayoutDirection)}," +
                "${innerPadding.calculateTopPadding()}," +
                "${innerPadding.calculateRightPadding(traceLayoutDirection)}," +
                "${innerPadding.calculateBottomPadding()}) books=${books.size}",
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .onSizeChanged { ReturnTrace.log("bookshelf: content size=$it") }
                .onGloballyPositioned {
                    ReturnTrace.log(
                        "bookshelf: content pos=${it.positionInWindow()} size=${it.size}",
                    )
                },
        ) {
            when {
                loading -> LoadingIndicator(modifier = Modifier.align(Alignment.Center))
                books.isEmpty() -> EmptyBookshelf(onImportClick = launchImport)
                else -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        if (!selectionMode) {
                            GroupFilterChips(
                                groups = groups,
                                selected = groupFilter,
                                onSelect = { groupFilter = it },
                            )
                        }
                        when {
                            displayBooks.isEmpty() && searchQuery.isNotBlank() -> Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "没有匹配「$searchQuery」的书籍",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            displayBooks.isEmpty() -> EmptyState(
                                title = "该分组暂无书籍",
                                description = "长按书籍多选后可移动到分组",
                                modifier = Modifier.weight(1f),
                            )
                            else -> {
                                Box(modifier = Modifier.weight(1f)) {
                                    if (gridView) {
                                        BookGrid(
                                            books = displayBooks,
                                            selectedIds = selectedIds,
                                            selectionMode = selectionMode,
                                            minColumnWidth = when (foldableUiState.widthCategory) {
                                                WidthCategory.COMPACT -> 160.dp
                                                WidthCategory.MEDIUM -> 140.dp
                                                WidthCategory.EXPANDED -> 170.dp
                                            },
                                            onOpenBook = onOpenBook,
                                            onToggleSelection = viewModel::toggleSelection,
                                            sharedTransitionScope = sharedTransitionScope,
                                            animatedVisibilityScope = animatedVisibilityScope,
                                        )
                                    } else {
                                        BookList(
                                            books = displayBooks,
                                            selectedIds = selectedIds,
                                            selectionMode = selectionMode,
                                            onOpenBook = onOpenBook,
                                            onToggleSelection = viewModel::toggleSelection,
                                            sharedTransitionScope = sharedTransitionScope,
                                            animatedVisibilityScope = animatedVisibilityScope,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (importState is ImportUiState.Importing) {
                val progress = (importState as ImportUiState.Importing).progress
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            // 导入期间拦截一切触摸，防止误操作底层书架
                            awaitEachGesture {
                                awaitPointerEvent()
                            }
                        },
                    color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f),
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        LoadingIndicator()
                        Text(
                            text = if (progress >= 0f) {
                                "正在导入… ${(progress * 100).toInt()}%"
                            } else {
                                "正在导入…"
                            },
                            modifier = Modifier.padding(top = 16.dp),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
            when (val batch = batchImportState) {
                BatchImportUiState.Enumerating -> Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitPointerEvent()
                            }
                        },
                    color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f),
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        LoadingIndicator()
                        Text(
                            text = "正在扫描目录…",
                            modifier = Modifier.padding(top = 16.dp),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
                is BatchImportUiState.Importing -> BatchImportProgressOverlay(
                    done = batch.done,
                    total = batch.total,
                    currentName = batch.currentName,
                    onCancel = viewModel::cancelBatchImport,
                )
                else -> Unit
            }
        }
    }

    when (val batch = batchImportState) {
        is BatchImportUiState.Confirming -> BatchImportConfirmDialog(
            defaultGroupName = batch.defaultGroupName,
            foundCount = batch.result.entries.size,
            truncated = batch.result.truncated,
            onConfirm = { viewModel.startBatchImport(batch.result.entries, it) },
            onDismiss = viewModel::consumeBatchImportState,
        )
        is BatchImportUiState.Done -> BatchImportSummaryDialog(
            result = batch.result,
            onDismiss = {
                val group = batch.result.groupName
                viewModel.consumeBatchImportState()
                if (group != null) groupFilter = group
            },
        )
        is BatchImportUiState.Error -> LaunchedEffect(batch) {
            snackbarHostState.showSnackbar(batch.message)
            viewModel.consumeBatchImportState()
        }
        else -> Unit
    }

    importRequest?.let { (uri, openAfter) ->
        ImportOptionsDialog(
            onConfirm = { removeBlankLines, removeAdLines, traditionalToSimplified ->
                importRequest = null
                viewModel.import(
                    uri = uri,
                    openAfterImport = openAfter,
                    removeBlankLines = removeBlankLines,
                    removeAdLines = removeAdLines,
                    traditionalToSimplified = traditionalToSimplified,
                )
            },
            onDismiss = { importRequest = null },
        )
    }

    if (detailBookId != 0L) {
        BookDetailDialog(
            bookId = detailBookId,
            viewModel = viewModel,
            onDismiss = {
                detailBookId = 0L
                viewModel.clearSelection()
            },
        )
    }

    if (showBookmarkOverview) {
        BookmarkOverviewDialog(
            books = books,
            bookmarks = allBookmarks,
            annotations = allAnnotations,
            onJump = { bookId, anchor ->
                showBookmarkOverview = false
                val title = books.firstOrNull { it.book.id == bookId }?.book?.title.orEmpty()
                onOpenBookAt(bookId, anchor, title)
            },
            onDismiss = { showBookmarkOverview = false },
        )
    }

    if (showMoveToGroupDialog) {
        MoveToGroupDialog(
            groups = groups,
            onMove = { name ->
                viewModel.moveSelectedToGroup(name)
                showMoveToGroupDialog = false
            },
            onDeleteGroup = viewModel::deleteGroup,
            onDismiss = { showMoveToGroupDialog = false },
        )
    }

    if (showDeleteDialog) {
        var deleteLocalData by remember { mutableStateOf(true) }
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("删除书籍") },
            text = {
                Column {
                    Text("将删除 ${selectedIds.size} 本书，此操作不可撤销。")
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = deleteLocalData,
                            onCheckedChange = { deleteLocalData = it },
                        )
                        Text("同时删除本地阅读进度与标注")
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteSelected(deleteLocalData)
                        showDeleteDialog = false
                    },
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
private fun ImportOptionsDialog(
    onConfirm: (removeBlankLines: Boolean, removeAdLines: Boolean, traditionalToSimplified: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var removeBlankLines by remember { mutableStateOf(false) }
    var removeAdLines by remember { mutableStateOf(false) }
    var traditionalToSimplified by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导入选项") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = removeBlankLines, onCheckedChange = { removeBlankLines = it })
                    Text("去空行")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = removeAdLines, onCheckedChange = { removeAdLines = it })
                    Text("去广告行")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = traditionalToSimplified,
                        onCheckedChange = { traditionalToSimplified = it },
                    )
                    Text("繁体转简体")
                }
                Text(
                    text = "勾选后清理结果保存为副本，原文件不受影响；去广告行使用「设置 → 智能清理」中的正则规则，繁简转换为单字级映射。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(removeBlankLines, removeAdLines, traditionalToSimplified) },
            ) {
                Text("导入")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

@Composable
private fun GroupFilterChips(
    groups: List<String>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = selected == null,
            onClick = { onSelect(null) },
            label = { Text("全部") },
        )
        FilterChip(
            selected = selected == "",
            onClick = { onSelect("") },
            label = { Text("未分组") },
        )
        groups.forEach { name ->
            FilterChip(
                selected = selected == name,
                onClick = { onSelect(name) },
                label = { Text(name) },
            )
        }
    }
}

@Composable
private fun MoveToGroupDialog(
    groups: List<String>,
    onMove: (String?) -> Unit,
    onDeleteGroup: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var newGroup by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("移动到分组") },
        text = {
            Column {
                OutlinedTextField(
                    value = newGroup,
                    onValueChange = { newGroup = it },
                    label = { Text("新建分组") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (groups.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    groups.forEach { name ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onMove(name) },
                        ) {
                            Text(
                                text = name,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { onDeleteGroup(name) }) {
                                Icon(Icons.Filled.Delete, contentDescription = "删除分组 $name")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = newGroup.trim().isNotEmpty(),
                onClick = { onMove(newGroup.trim()) },
            ) {
                Text("新建并移动")
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onMove(null) }) {
                    Text("移出分组")
                }
                TextButton(onClick = onDismiss) {
                    Text("取消")
                }
            }
        },
    )
}

@Composable
private fun FolderIcon(contentDescription: String) {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(
        modifier = Modifier
            .size(24.dp)
            .semantics { this.contentDescription = contentDescription },
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
private fun ExternalSourceBadge(modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = MaterialTheme.shapes.small,
        modifier = modifier,
    ) {
        Text(
            text = "外",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
        )
    }
}

/**
 * 待解析角标：EPUB/FB2 的整本压平、漫画的 rar/tar/7z 解压尚未完成时的提示。
 * 导入后由后台预热队列处理，完成即回写 `books.contentPreparedAt`，角标随书架刷新自动消失。
 */
@Composable
private fun PendingParseBadge(modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.small,
        modifier = modifier,
    ) {
        Text(
            text = "待解析",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
        )
    }
}

/** 漫画容器角标（CBZ/CBR/CBT/CB7/文件夹），与电子书架同架时用来区分。 */
@Composable
private fun ComicBadge(label: String, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = MaterialTheme.shapes.small,
        modifier = modifier,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
        )
    }
}

@Composable
private fun EmptyBookshelf(onImportClick: () -> Unit) {
    EmptyState(
        title = "书架空空如也",
        description = "导入 TXT 开始阅读",
        actionLabel = "导入书籍",
        onAction = onImportClick,
        illustration = {
            val fillColor = MaterialTheme.colorScheme.surfaceVariant
            val lineColor = MaterialTheme.colorScheme.outline
            Canvas(modifier = Modifier.size(96.dp)) {
                val w = size.width
                val h = size.height
                val topLeft = Offset(w * 0.2f, h * 0.05f)
                val bookSize = Size(w * 0.6f, h * 0.9f)
                val corner = CornerRadius(12f, 12f)
                drawRoundRect(color = fillColor, topLeft = topLeft, size = bookSize, cornerRadius = corner)
                drawRoundRect(
                    color = lineColor,
                    topLeft = topLeft,
                    size = bookSize,
                    cornerRadius = corner,
                    style = Stroke(width = 5f),
                )
                drawLine(
                    color = lineColor,
                    start = Offset(w * 0.32f, h * 0.05f),
                    end = Offset(w * 0.32f, h * 0.95f),
                    strokeWidth = 5f,
                )
            }
        },
    )
}

@Composable
private fun SortIcon(contentDescription: String) {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(
        modifier = Modifier
            .size(24.dp)
            .semantics { this.contentDescription = contentDescription },
    ) {
        val w = size.width
        val h = size.height
        val stroke = h * 0.09f
        // 三条长度递减的横线 + 左侧向下箭头，表示排序
        val rows = listOf(0.86f, 0.62f, 0.38f)
        rows.forEachIndexed { i, frac ->
            drawLine(
                color = tint,
                start = Offset(w * 0.32f, h * (0.22f + i * 0.28f)),
                end = Offset(w * (0.32f + frac * 0.62f), h * (0.22f + i * 0.28f)),
                strokeWidth = stroke,
            )
        }
        drawLine(
            color = tint,
            start = Offset(w * 0.14f, h * 0.18f),
            end = Offset(w * 0.14f, h * 0.72f),
            strokeWidth = stroke,
        )
        drawLine(
            color = tint,
            start = Offset(w * 0.05f, h * 0.58f),
            end = Offset(w * 0.14f, h * 0.72f),
            strokeWidth = stroke,
        )
        drawLine(
            color = tint,
            start = Offset(w * 0.23f, h * 0.58f),
            end = Offset(w * 0.14f, h * 0.72f),
            strokeWidth = stroke,
        )
    }
}

@Composable
private fun GridViewIcon(contentDescription: String) {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(
        modifier = Modifier
            .size(24.dp)
            .semantics { this.contentDescription = contentDescription },
    ) {
        val cell = size.width * 0.34f
        val gap = size.width * 0.14f
        val corner = CornerRadius(cell * 0.25f, cell * 0.25f)
        for (row in 0..1) {
            for (col in 0..1) {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(col * (cell + gap), row * (cell + gap)),
                    size = Size(cell, cell),
                    cornerRadius = corner,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalSharedTransitionApi::class)
@Composable
private fun BookGridItem(
    item: BookWithProgress,
    selected: Boolean,
    selectionMode: Boolean,
    onOpenBook: (bookId: Long, title: String) -> Unit,
    onToggleSelection: (Long) -> Unit,
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?,
) {
    val book = item.book
    Column {
        Box(
            modifier = Modifier.combinedClickable(
                onClick = {
                    if (selectionMode) onToggleSelection(book.id) else onOpenBook(book.id, book.title)
                },
                onLongClick = { onToggleSelection(book.id) },
            ),
        ) {
            BookCover(
                title = book.title,
                modifier = Modifier.fillMaxWidth(),
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope,
                bookId = book.id,
                coverPath = book.coverPath,
            )
            if (selected) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clip(MaterialTheme.shapes.largeIncreased)
                        .background(Color.Black.copy(alpha = 0.45f)),
                )
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = "已选中",
                    tint = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp),
                )
            } else if (book.source == BookSource.EXTERNAL) {
                ExternalSourceBadge(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp),
                )
            }
            if (book.needsContentPreparation()) {
                PendingParseBadge(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp),
                )
            }
            if (isPagedFormat(book.format)) {
                ComicBadge(
                    label = pagedFormatLabel(book),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp),
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = book.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = bookSubtitle(item),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun BookGrid(
    books: List<BookWithProgress>,
    selectedIds: Set<Long>,
    selectionMode: Boolean,
    minColumnWidth: Dp,
    onOpenBook: (bookId: Long, title: String) -> Unit,
    onToggleSelection: (Long) -> Unit,
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = minColumnWidth),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(books, key = { it.book.id }) { item ->
            BookGridItem(
                item = item,
                selected = item.book.id in selectedIds,
                selectionMode = selectionMode,
                onOpenBook = onOpenBook,
                onToggleSelection = onToggleSelection,
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalSharedTransitionApi::class)
@Composable
private fun BookList(
    books: List<BookWithProgress>,
    selectedIds: Set<Long>,
    selectionMode: Boolean,
    onOpenBook: (bookId: Long, title: String) -> Unit,
    onToggleSelection: (Long) -> Unit,
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(books, key = { it.book.id }) { item ->
            val book = item.book
            val selected = book.id in selectedIds
            Surface(
                shape = MaterialTheme.shapes.large,
                tonalElevation = if (selected) 3.dp else 0.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = {
                            if (selectionMode) onToggleSelection(book.id) else onOpenBook(book.id, book.title)
                        },
                        onLongClick = { onToggleSelection(book.id) },
                    ),
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box {
                        BookCover(
                            title = book.title,
                            modifier = Modifier.width(46.dp),
                            sharedTransitionScope = sharedTransitionScope,
                            animatedVisibilityScope = animatedVisibilityScope,
                            bookId = book.id,
                            coverPath = book.coverPath,
                        )
                        if (book.source == BookSource.EXTERNAL) {
                            ExternalSourceBadge(modifier = Modifier.align(Alignment.TopEnd))
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = book.title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            // 列表视图封面只有 46dp 放不下角标，漫画改在这里标出容器类型
                            text = if (isPagedFormat(book.format)) {
                                pagedFormatLabel(book)
                            } else {
                                book.author ?: "未知作者"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = bookSubtitle(item),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        // 列表视图封面只有 46dp，放不下角标，改在副标题下补一行
                        if (book.needsContentPreparation()) {
                            Text(
                                text = "待解析",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                        }
                    }
                    if (selectionMode) {
                        Checkbox(checked = selected, onCheckedChange = null)
                    }
                }
            }
        }
    }
}

/**
 * 书架副标题：页式格式（漫画 / PDF）按页数算进度，文本按字符偏移算。
 * 两类位置语义完全不同，这里按格式分派而不是把页序号塞进 charOffset。
 */
private fun bookSubtitle(item: BookWithProgress): String {
    val book = item.book
    val progress = if (isPagedFormat(book.format)) {
        formatComicProgress(item.comicPage, book.comicPageCount)
    } else {
        formatReadingProgress(item.charOffset, book.totalChars)
    }
    val lastRead = formatLastRead(book.lastReadAt)
    return if (lastRead != null) "$progress · $lastRead" else progress
}

private fun bookshelfSortLabel(sort: BookshelfSort): String = when (sort) {
    BookshelfSort.IMPORT_TIME -> "按导入时间"
    BookshelfSort.TITLE -> "按书名"
    BookshelfSort.PROGRESS -> "按阅读进度"
}

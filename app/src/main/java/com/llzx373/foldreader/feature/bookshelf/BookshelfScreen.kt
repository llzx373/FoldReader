package com.llzx373.foldreader.feature.bookshelf

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
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
import androidx.compose.material3.carousel.HorizontalMultiBrowseCarousel
import androidx.compose.material3.carousel.rememberCarouselState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.llzx373.foldreader.FoldReaderApplication
import com.llzx373.foldreader.core.data.db.BookWithProgress
import com.llzx373.foldreader.core.foldable.FoldableUiState
import com.llzx373.foldreader.core.foldable.WidthCategory
import com.llzx373.foldreader.ui.EmptyState
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BookshelfScreen(
    foldableUiState: FoldableUiState,
    onOpenBook: (Long) -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as FoldReaderApplication
    val viewModel: BookshelfViewModel = viewModel(factory = BookshelfViewModel.factory(app.container))
    val books by viewModel.books.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val gridView by viewModel.gridView.collectAsState()
    val selectedIds by viewModel.selectedIds.collectAsState()
    val importState by viewModel.importState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }
    var fabMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var searchActive by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var detailBookId by rememberSaveable { mutableStateOf(0L) }
    val selectionMode = selectedIds.isNotEmpty()
    val displayBooks = remember(books, searchQuery) {
        val q = searchQuery.trim()
        if (q.isEmpty()) {
            books
        } else {
            books.filter {
                it.book.title.contains(q, ignoreCase = true) ||
                    it.book.author?.contains(q, ignoreCase = true) == true
            }
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
            viewModel.import(uri, openAfterImport = false)
        }
    }
    val launchImport = {
        openDocumentLauncher.launch(arrayOf("text/plain", "application/octet-stream"))
    }

    LaunchedEffect(Unit) {
        app.container.pendingImportUri.collect { uri ->
            if (uri != null) {
                app.container.pendingImportUri.value = null
                viewModel.import(uri, openAfterImport = true)
            }
        }
    }

    LaunchedEffect(importState) {
        when (val state = importState) {
            is ImportUiState.Imported -> {
                if (state.openAfter) onOpenBook(state.bookId)
                else snackbarHostState.showSnackbar("《${state.title}》已加入书架")
                viewModel.consumeImportState()
            }
            is ImportUiState.Duplicate -> {
                if (state.sameFile && state.openAfter) onOpenBook(state.bookId)
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
                        IconButton(onClick = { searchActive = true }) {
                            Icon(Icons.Filled.Search, contentDescription = "搜索书架")
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
                            scope.launch { snackbarHostState.showSnackbar("WiFi 传书敬请期待") }
                        },
                        icon = { Icon(Icons.Filled.Share, contentDescription = null) },
                        text = { Text("WiFi 传书") },
                    )
                }
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when {
                loading -> LoadingIndicator(modifier = Modifier.align(Alignment.Center))
                books.isEmpty() -> EmptyBookshelf(onImportClick = launchImport)
                displayBooks.isEmpty() -> Text(
                    text = "没有匹配「$searchQuery」的书籍",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )
                else -> {
                    val recent = remember(books) {
                        books.filter { it.book.lastReadAt != null }
                            .sortedByDescending { it.book.lastReadAt }
                            .take(10)
                    }
                    Column(modifier = Modifier.fillMaxSize()) {
                        if (!selectionMode && searchQuery.isBlank() && recent.isNotEmpty()) {
                            RecentReadsCarousel(recent = recent, onOpenBook = onOpenBook)
                        }
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
                                )
                            } else {
                                BookList(
                                    books = displayBooks,
                                    selectedIds = selectedIds,
                                    selectionMode = selectionMode,
                                    onOpenBook = onOpenBook,
                                    onToggleSelection = viewModel::toggleSelection,
                                )
                            }
                        }
                    }
                }
            }
            if (importState is ImportUiState.Importing) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f),
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        LoadingIndicator()
                        Text(
                            text = "正在导入…",
                            modifier = Modifier.padding(top = 16.dp),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        }
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

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("删除书籍") },
            text = {
                Column {
                    Text("将删除 ${selectedIds.size} 本书，此操作不可撤销。")
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = true, onCheckedChange = null, enabled = false)
                        Text("同时删除阅读进度与标注（随书籍级联删除）")
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteSelected()
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecentReadsCarousel(
    recent: List<BookWithProgress>,
    onOpenBook: (Long) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "最近阅读",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        HorizontalMultiBrowseCarousel(
            state = rememberCarouselState { recent.size },
            modifier = Modifier.fillMaxWidth(),
            preferredItemWidth = 118.dp,
            itemSpacing = 8.dp,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        ) { index ->
            val item = recent[index]
            Column {
                BookCover(
                    title = item.book.title,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenBook(item.book.id) },
                )
                Text(
                    text = formatReadingProgress(item.charOffset, item.book.totalChars),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookGridItem(
    item: BookWithProgress,
    selected: Boolean,
    selectionMode: Boolean,
    onOpenBook: (Long) -> Unit,
    onToggleSelection: (Long) -> Unit,
) {
    val book = item.book
    Column {
        Box(
            modifier = Modifier.combinedClickable(
                onClick = {
                    if (selectionMode) onToggleSelection(book.id) else onOpenBook(book.id)
                },
                onLongClick = { onToggleSelection(book.id) },
            ),
        ) {
            BookCover(title = book.title, modifier = Modifier.fillMaxWidth())
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

@Composable
private fun BookGrid(
    books: List<BookWithProgress>,
    selectedIds: Set<Long>,
    selectionMode: Boolean,
    minColumnWidth: Dp,
    onOpenBook: (Long) -> Unit,
    onToggleSelection: (Long) -> Unit,
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
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookList(
    books: List<BookWithProgress>,
    selectedIds: Set<Long>,
    selectionMode: Boolean,
    onOpenBook: (Long) -> Unit,
    onToggleSelection: (Long) -> Unit,
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
                            if (selectionMode) onToggleSelection(book.id) else onOpenBook(book.id)
                        },
                        onLongClick = { onToggleSelection(book.id) },
                    ),
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BookCover(title = book.title, modifier = Modifier.width(46.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = book.title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = book.author ?: "未知作者",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = bookSubtitle(item),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (selectionMode) {
                        Checkbox(checked = selected, onCheckedChange = null)
                    }
                }
            }
        }
    }
}

private fun bookSubtitle(item: BookWithProgress): String {
    val progress = formatReadingProgress(item.charOffset, item.book.totalChars)
    val lastRead = formatLastRead(item.book.lastReadAt)
    return if (lastRead != null) "$progress · $lastRead" else progress
}

package com.llzx373.foldreader.feature.reader.markdown

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.llzx373.foldreader.FoldReaderApplication
import com.llzx373.foldreader.core.data.db.BookmarkEntity
import com.llzx373.foldreader.core.data.db.ReadingProgressEntity
import com.llzx373.foldreader.core.data.settings.ReadingPreferences
import com.llzx373.foldreader.core.format.Chapter
import com.llzx373.foldreader.core.format.EncodingDetector
import com.llzx373.foldreader.core.format.markdown.blockIndexAtOffset
import com.llzx373.foldreader.core.format.markdown.blockStartOffsets
import com.llzx373.foldreader.core.format.markdown.chaptersOf
import com.llzx373.foldreader.core.format.markdown.newMarkdownParser
import com.llzx373.foldreader.core.format.markdown.plainText
import com.llzx373.foldreader.core.format.markdown.topLevelBlocks
import com.llzx373.foldreader.feature.reader.BookmarkListDialog
import com.llzx373.foldreader.feature.reader.BookmarkListIcon
import com.llzx373.foldreader.feature.reader.BookmarkRibbonIcon
import com.llzx373.foldreader.feature.reader.ChapterListDialog
import com.llzx373.foldreader.feature.reader.ReaderColors
import com.llzx373.foldreader.feature.reader.bookmarkSnapshotOf
import com.llzx373.foldreader.feature.reader.chapterIndexAt
import com.llzx373.foldreader.feature.reader.findBookmarkAt
import com.llzx373.foldreader.feature.reader.readerColors
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.commonmark.node.Node

/**
 * Markdown 只读阅读器：不压平、不分页——LazyColumn 逐顶层块渲染 commonmark AST。
 *
 * 锚点体系与文本阅读器一致（源文本字符偏移，存 reading_progress.charOffset /
 * bookmarks.charOffset），只是换算单位从「字符 → 页」变成「字符 → 块下标」：
 * 每个顶层块记住自己的起始偏移，首可见块即当前锚点。
 * 书签/目录/进度因此与 TXT/EPUB 走同一张表、同一条导航链路。
 */
@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
    FlowPreview::class,
)
@Composable
fun MarkdownReaderScreen(
    bookId: Long,
    initialAnchor: Long,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as FoldReaderApplication
    val container = app.container
    val scope = rememberCoroutineScope()

    val prefs by container.settingsRepository.preferences
        .collectAsState(initial = ReadingPreferences())
    val colors = readerColors(prefs.themeId, prefs.customBackgroundArgb, prefs.customTextArgb)
    val styles = remember(colors, prefs.fontSizeSp, prefs.lineSpacingMultiplier) {
        MarkdownStyles(colors, prefs.fontSizeSp, prefs.lineSpacingMultiplier)
    }
    val book by container.bookshelfRepository.observeBook(bookId).collectAsState(initial = null)
    val bookmarks by container.bookshelfRepository.observeBookmarks(bookId)
        .collectAsState(initial = emptyList())

    var document by remember { mutableStateOf<MdDocument?>(null) }
    var loadFailed by remember { mutableStateOf(false) }

    LaunchedEffect(bookId) {
        val entity = container.bookshelfRepository.getBook(bookId)
        if (entity == null) {
            loadFailed = true
            return@LaunchedEffect
        }
        document = runCatching {
            val text = container.markdownBookParser.readDecoded(
                Uri.parse(entity.fileUri),
                EncodingDetector.forNameOrNull(entity.encoding),
            )
            val root = newMarkdownParser().parse(text)
            val blocks = topLevelBlocks(root)
            MdDocument(
                text = text,
                blocks = blocks,
                starts = blockStartOffsets(blocks, text.length),
                chapters = chaptersOf(text, root),
            )
        }.getOrElse {
            loadFailed = true
            null
        }
        if (document != null) {
            container.bookshelfRepository.touchLastRead(bookId)
            // 书架进度条的分母：Markdown 没有预热回填，首开时顺手补上
            if (entity.totalChars <= 0L) {
                runCatching {
                    container.bookshelfRepository.updateConvertedFile(
                        bookId,
                        cleanedFilePath = null,
                        totalChars = document?.text?.length?.toLong() ?: 0L,
                    )
                }
            }
        }
    }

    val listState = rememberLazyListState()
    val doc = document
    val currentAnchor = doc?.starts?.getOrElse(listState.firstVisibleItemIndex) { 0L } ?: 0L

    // 打开定位：书签跳转（initialAnchor >= 0）优先，否则回上次的阅读位置
    var restored by remember { mutableStateOf(false) }
    LaunchedEffect(doc) {
        val d = doc ?: return@LaunchedEffect
        if (restored) return@LaunchedEffect
        restored = true
        val progress = container.bookshelfRepository.getProgress(bookId)
        val target = if (initialAnchor >= 0L) initialAnchor else progress?.charOffset ?: 0L
        val index = blockIndexAtOffset(d.starts, target)
        if (index > 0) listState.scrollToItem(index)
    }

    suspend fun saveProgress(offset: Long) {
        val d = document ?: return
        val existing = container.bookshelfRepository.getProgress(bookId)
        val now = System.currentTimeMillis()
        container.bookshelfRepository.saveProgress(
            ReadingProgressEntity(
                bookId = bookId,
                charOffset = offset,
                chapterIndex = chapterIndexAt(d.chapters, offset),
                // 阅读时长/累计字数由文本阅读器的计时器维护；这里只挪锚点，旧值原样保留
                totalReadingMillis = existing?.totalReadingMillis ?: 0L,
                firstReadAt = existing?.firstReadAt ?: now,
                charsReadTotal = existing?.charsReadTotal ?: 0L,
                updatedAt = now,
            ).keepPagedAnchor(existing),
        )
    }

    // 首可见块变化 → 防抖落盘；退出时（onDispose）补最后一笔
    LaunchedEffect(bookId, doc != null) {
        if (doc == null) return@LaunchedEffect
        snapshotFlow { listState.firstVisibleItemIndex }
            .map { index -> document?.starts?.getOrElse(index) { 0L } ?: 0L }
            .distinctUntilChanged()
            .debounce(400)
            .collect { offset -> runCatching { saveProgress(offset) } }
    }
    DisposableEffect(bookId) {
        onDispose {
            val d = document
            if (d != null) {
                val offset = d.starts.getOrElse(listState.firstVisibleItemIndex) { 0L }
                // 组合作用域随退出销毁，进度落盘挂到 App 级 scope 上
                container.parserScope.launch { runCatching { saveProgress(offset) } }
            }
        }
    }

    var showChapters by remember { mutableStateOf(false) }
    var showBookmarks by remember { mutableStateOf(false) }

    fun scrollToOffset(offset: Long) {
        val d = document ?: return
        scope.launch {
            listState.scrollToItem(blockIndexAtOffset(d.starts, offset))
        }
    }

    fun toggleBookmark() {
        val d = doc ?: return
        val anchor = currentAnchor
        scope.launch {
            val existing = findBookmarkAt(bookmarks, anchor)
            if (existing != null) {
                container.bookshelfRepository.deleteBookmark(existing.id)
            } else {
                val excerpt =
                    d.blocks.getOrNull(blockIndexAtOffset(d.starts, anchor))?.plainText() ?: ""
                container.bookshelfRepository.addBookmark(
                    BookmarkEntity(
                        bookId = bookId,
                        charOffset = anchor,
                        chapterIndex = chapterIndexAt(d.chapters, anchor),
                        snapshotText = bookmarkSnapshotOf(excerpt),
                        createdAt = System.currentTimeMillis(),
                    ),
                )
            }
        }
    }

    Scaffold(
        containerColor = colors.background,
        topBar = {
            MarkdownTopBar(
                bookTitle = book?.title ?: "",
                chapterTitle = doc?.chapters
                    ?.getOrNull(chapterIndexAt(doc.chapters, currentAnchor))?.title
                    ?.takeIf { doc.chapters.size > 1 }
                    ?: "",
                colors = colors,
                bookmarked = findBookmarkAt(bookmarks, currentAnchor) != null,
                onBack = onBack,
                onToggleBookmark = ::toggleBookmark,
                onOpenBookmarks = { showBookmarks = true },
                onOpenCatalog = { showChapters = true },
                onOpenSettings = onOpenSettings,
            )
        },
        bottomBar = {
            if (doc != null && doc.text.isNotEmpty()) {
                MarkdownStatusBar(doc, currentAnchor, colors)
            }
        },
    ) { innerPadding ->
        when {
            doc == null && !loadFailed -> Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                LoadingIndicator()
            }

            doc == null -> Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Text("文档加载失败", color = colors.text)
            }

            doc.blocks.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Text("（空文档）", color = colors.text)
            }

            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            ) {
                items(doc.blocks.size, key = { it }) { index ->
                    MarkdownBlockView(doc.blocks[index], styles)
                }
            }
        }
    }

    if (showChapters && doc != null) {
        ChapterListDialog(
            chapters = doc.chapters,
            persons = emptyList(),
            currentIndex = chapterIndexAt(doc.chapters, currentAnchor),
            remainingText = null,
            colors = colors,
            onSelect = { index ->
                showChapters = false
                doc.chapters.getOrNull(index)?.let { scrollToOffset(it.charStart) }
            },
            onDismiss = { showChapters = false },
        )
    }
    if (showBookmarks && doc != null) {
        BookmarkListDialog(
            bookmarks = bookmarks,
            chapters = doc.chapters,
            colors = colors,
            onJump = { bookmark ->
                showBookmarks = false
                scrollToOffset(bookmark.readerAnchor())
            },
            onRename = { bookmark, label ->
                scope.launch {
                    container.bookshelfRepository.renameBookmark(bookmark.copy(label = label.trim()))
                }
            },
            onDelete = { bookmark ->
                scope.launch { container.bookshelfRepository.deleteBookmark(bookmark.id) }
            },
            onDismiss = { showBookmarks = false },
        )
    }
}

/** 已解析的 Markdown 文档：渲染块、块→偏移换算表、目录。 */
private class MdDocument(
    val text: String,
    val blocks: List<Node>,
    val starts: LongArray,
    val chapters: List<Chapter>,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MarkdownTopBar(
    bookTitle: String,
    chapterTitle: String,
    colors: ReaderColors,
    bookmarked: Boolean,
    onBack: () -> Unit,
    onToggleBookmark: () -> Unit,
    onOpenBookmarks: () -> Unit,
    onOpenCatalog: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    TopAppBar(
        title = {
            Column {
                Text(bookTitle, maxLines = 1, style = MaterialTheme.typography.titleMedium)
                if (chapterTitle.isNotEmpty()) {
                    Text(chapterTitle, maxLines = 1, style = MaterialTheme.typography.labelSmall)
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
        },
        actions = {
            IconButton(onClick = onToggleBookmark) {
                BookmarkRibbonIcon(
                    filled = bookmarked,
                    tint = colors.text,
                    contentDescription = if (bookmarked) "移除书签" else "加书签",
                )
            }
            IconButton(onClick = onOpenBookmarks) {
                BookmarkListIcon(tint = colors.text, contentDescription = "书签列表")
            }
            IconButton(onClick = onOpenCatalog) {
                Icon(Icons.Filled.List, contentDescription = "目录")
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Filled.Settings, contentDescription = "设置")
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = colors.background,
            titleContentColor = colors.text,
            navigationIconContentColor = colors.text,
            actionIconContentColor = colors.text,
        ),
    )
}

/** 底部状态行：阅读百分比 + 当前章节（与文本阅读器的底栏同信息密度）。 */
@Composable
private fun MarkdownStatusBar(doc: MdDocument, currentAnchor: Long, colors: ReaderColors) {
    val percent = ((currentAnchor.toFloat() / doc.text.length) * 100).toInt().coerceIn(0, 100)
    Surface(color = colors.background, contentColor = colors.text) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
            Text(
                text = "$percent%",
                style = MaterialTheme.typography.labelSmall,
                color = colors.accent,
            )
            Text(
                text = doc.chapters.getOrNull(chapterIndexAt(doc.chapters, currentAnchor))?.title ?: "",
                style = MaterialTheme.typography.labelSmall,
                color = colors.text.copy(alpha = 0.6f),
                maxLines = 1,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}

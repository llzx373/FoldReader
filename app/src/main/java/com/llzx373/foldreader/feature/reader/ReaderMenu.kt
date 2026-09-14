package com.llzx373.foldreader.feature.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.llzx373.foldreader.core.data.settings.DualPageMode
import com.llzx373.foldreader.core.data.settings.PageTurnMode
import com.llzx373.foldreader.core.data.settings.ReadingPreferences
import com.llzx373.foldreader.core.data.settings.ReadingTheme
import com.llzx373.foldreader.core.format.Chapter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderTopBar(
    bookTitle: String,
    chapterTitle: String,
    colors: ReaderColors,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    dualPage: Boolean = false,
    hasRightPage: Boolean = false,
    leftBookmarked: Boolean = false,
    rightBookmarked: Boolean = false,
    onToggleBookmark: (leftPage: Boolean) -> Unit = {},
    onOpenBookmarks: () -> Unit = {},
) {
    TopAppBar(
        title = {
            Column {
                Text(bookTitle, maxLines = 1, style = MaterialTheme.typography.titleMedium)
                if (chapterTitle.isNotEmpty()) {
                    Text(
                        chapterTitle,
                        maxLines = 1,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
        },
        actions = {
            if (dualPage && hasRightPage) {
                // 双页：左/右页各一枚书签 toggle，锚点取对应页首字符
                IconButton(onClick = { onToggleBookmark(true) }) {
                    BookmarkRibbonIcon(
                        filled = leftBookmarked,
                        tint = colors.text,
                        contentDescription = if (leftBookmarked) "移除左页书签" else "左页加书签",
                    )
                }
                IconButton(onClick = { onToggleBookmark(false) }) {
                    BookmarkRibbonIcon(
                        filled = rightBookmarked,
                        tint = colors.text,
                        contentDescription = if (rightBookmarked) "移除右页书签" else "右页加书签",
                    )
                }
            } else {
                IconButton(onClick = { onToggleBookmark(true) }) {
                    BookmarkRibbonIcon(
                        filled = leftBookmarked,
                        tint = colors.text,
                        contentDescription = if (leftBookmarked) "移除书签" else "加书签",
                    )
                }
            }
            IconButton(onClick = onOpenBookmarks) {
                BookmarkListIcon(
                    tint = colors.text,
                    contentDescription = "书签列表",
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = colors.background,
            titleContentColor = colors.text,
            navigationIconContentColor = colors.text,
            actionIconContentColor = colors.text,
        ),
        modifier = modifier,
    )
}

/** 书签缎带图标：filled 为填充态（已加书签），否则描边。 */
@Composable
private fun BookmarkRibbonIcon(filled: Boolean, tint: Color, contentDescription: String) {
    Canvas(
        modifier = Modifier
            .size(22.dp)
            .semantics { this.contentDescription = contentDescription },
    ) {
        val w = size.width
        val h = size.height
        val left = w * 0.22f
        val right = w * 0.78f
        val top = h * 0.08f
        val bottom = h * 0.92f
        val notch = h * 0.22f
        val path = Path().apply {
            moveTo(left, top)
            lineTo(right, top)
            lineTo(right, bottom)
            lineTo(w / 2f, bottom - notch)
            lineTo(left, bottom)
            close()
        }
        if (filled) {
            drawPath(path, color = tint)
        } else {
            drawPath(path, color = tint, style = Stroke(width = 2.2f.dp.toPx()))
        }
    }
}

/** 书签列表图标：缎带 + 两条列表线。 */
@Composable
private fun BookmarkListIcon(tint: Color, contentDescription: String) {
    Canvas(
        modifier = Modifier
            .size(22.dp)
            .semantics { this.contentDescription = contentDescription },
    ) {
        val w = size.width
        val h = size.height
        val stroke = 2.2f.dp.toPx()
        val path = Path().apply {
            moveTo(w * 0.10f, h * 0.10f)
            lineTo(w * 0.42f, h * 0.10f)
            lineTo(w * 0.42f, h * 0.72f)
            lineTo(w * 0.26f, h * 0.56f)
            lineTo(w * 0.10f, h * 0.72f)
            close()
        }
        drawPath(path, color = tint, style = Stroke(width = stroke))
        drawLine(tint, Offset(w * 0.56f, h * 0.26f), Offset(w * 0.92f, h * 0.26f), strokeWidth = stroke)
        drawLine(tint, Offset(w * 0.56f, h * 0.52f), Offset(w * 0.92f, h * 0.52f), strokeWidth = stroke)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ReaderMenuPanel(
    prefs: ReadingPreferences,
    progressFraction: Float,
    colors: ReaderColors,
    onSeekFraction: (Float) -> Unit,
    onOpenCatalog: () -> Unit,
    onCyclePageTurnMode: () -> Unit,
    onCycleDualPageMode: () -> Unit,
    onSetBrightness: (Float) -> Unit,
    onSetFontSize: (Float) -> Unit,
    onSetLineSpacing: (Float) -> Unit,
    onSetMarginLevel: (Int) -> Unit,
    onSelectTheme: (ReadingTheme) -> Unit,
    onPickCustomBackground: (Int) -> Unit,
    onPickCustomText: (Int) -> Unit,
    autoPageStatus: AutoPageStatus,
    onToggleAutoPage: (Boolean) -> Unit,
    onCycleAutoPageMode: () -> Unit,
    onCycleAutoPageSpeed: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showBrightness by remember { mutableStateOf(false) }
    var showLayout by remember { mutableStateOf(false) }
    var showTheme by remember { mutableStateOf(false) }
    var sliderFraction by remember { mutableStateOf(progressFraction) }
    var fontSizeDraft by remember { mutableStateOf<Float?>(null) }
    var lineSpacingDraft by remember { mutableStateOf<Float?>(null) }
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = colors.background,
        contentColor = colors.text,
        tonalElevation = 4.dp,
        shadowElevation = 8.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Slider(
                value = sliderFraction,
                onValueChange = { sliderFraction = it },
                onValueChangeFinished = { onSeekFraction(sliderFraction) },
            )
            Text(
                text = formatPercent(sliderFraction),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.End),
            )
            if (showLayout) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("字号", style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = fontSizeDraft ?: prefs.fontSizeSp,
                        onValueChange = { fontSizeDraft = it },
                        onValueChangeFinished = {
                            fontSizeDraft?.let(onSetFontSize)
                            fontSizeDraft = null
                        },
                        valueRange = 12f..32f,
                        steps = 9,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp),
                    )
                    Text(
                        text = "%.0f".format(fontSizeDraft ?: prefs.fontSizeSp),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("行距", style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = lineSpacingDraft ?: prefs.lineSpacingMultiplier,
                        onValueChange = { lineSpacingDraft = it },
                        onValueChangeFinished = {
                            lineSpacingDraft?.let(onSetLineSpacing)
                            lineSpacingDraft = null
                        },
                        valueRange = 1.0f..2.2f,
                        steps = 11,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp),
                    )
                    Text(
                        text = "%.1f".format(lineSpacingDraft ?: prefs.lineSpacingMultiplier),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("边距", style = MaterialTheme.typography.labelMedium)
                    ButtonGroup(
                        overflowIndicator = {},
                        modifier = Modifier.padding(start = 12.dp),
                    ) {
                        listOf("小", "中", "大").forEachIndexed { level, label ->
                            toggleableItem(
                                checked = prefs.marginLevel == level,
                                label = label,
                                onCheckedChange = { onSetMarginLevel(level) },
                                weight = 1f)
                        }
                    }
                }
            }
            if (showTheme) {
                ThemePicker(
                    prefs = prefs,
                    onSelectTheme = onSelectTheme,
                    onPickCustomBackground = onPickCustomBackground,
                    onPickCustomText = onPickCustomText,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
            if (showBrightness) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("亮度", style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = if (prefs.readerBrightness < 0f) 0.5f else prefs.readerBrightness,
                        onValueChange = onSetBrightness,
                        valueRange = 0.05f..1f,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp),
                    )
                    TextButton(onClick = { onSetBrightness(-1f) }) { Text("跟随系统") }
                }
            }
            ButtonGroup(
                overflowIndicator = {},
                modifier = Modifier.fillMaxWidth(),
            ) {
                clickableItem(onClick = onOpenCatalog, label = "目录", weight = 1f)
                toggleableItem(
                    checked = showLayout,
                    label = "版式",
                    onCheckedChange = { on ->
                        showLayout = on
                        if (on) {
                            showBrightness = false
                            showTheme = false
                        }
                    },
                    weight = 1f)
                toggleableItem(
                    checked = showTheme,
                    label = "主题",
                    onCheckedChange = { on ->
                        showTheme = on
                        if (on) {
                            showBrightness = false
                            showLayout = false
                        }
                    },
                    weight = 1f)
                toggleableItem(
                    checked = showBrightness,
                    label = "亮度",
                    onCheckedChange = { on ->
                        showBrightness = on
                        if (on) {
                            showLayout = false
                            showTheme = false
                        }
                    },
                    weight = 1f)
            }
            ButtonGroup(
                overflowIndicator = {},
                modifier = Modifier.fillMaxWidth(),
            ) {
                clickableItem(
                    onClick = onCyclePageTurnMode,
                    label = "翻页：${pageTurnModeLabel(prefs.pageTurnMode)}",
                    weight = 1f)
                clickableItem(
                    onClick = onCycleDualPageMode,
                    label = "双页：${dualPageModeLabel(prefs.dualPageMode)}",
                    weight = 1f)
                clickableItem(onClick = onOpenSettings, label = "设置", weight = 1f)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { onToggleAutoPage(!prefs.autoPageEnabled) }) {
                    Text("自动翻页：${if (prefs.autoPageEnabled) "开" else "关"}")
                }
                if (prefs.autoPageEnabled) {
                    TextButton(onClick = onCycleAutoPageMode) {
                        Text(autoPageModeLabel(prefs.autoPageMode))
                    }
                    TextButton(onClick = onCycleAutoPageSpeed) {
                        Text(autoPageSpeedLabel(prefs))
                    }
                }
                if (autoPageStatus.enabled && autoPageStatus.paused) {
                    Text(
                        text = "已暂停",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.accent,
                    )
                }
            }
        }
    }
}

@Composable
fun ChapterListDialog(
    chapters: List<Chapter>,
    currentIndex: Int,
    remainingText: String?,
    colors: ReaderColors,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
        title = { Text("目录") },
        text = {
            Column {
                if (remainingText != null) {
                    Text(
                        text = remainingText,
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.accent,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                if (chapters.size <= 1) {
                    Text(
                        text = "未识别到章节，可用进度条跳转",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                } else {
                    val listState = rememberLazyListState()
                    LaunchedEffect(Unit) {
                        listState.scrollToItem(currentIndex.coerceIn(0, chapters.lastIndex))
                    }
                    LazyColumn(state = listState, modifier = Modifier.height(360.dp)) {
                        itemsIndexed(chapters) { index, chapter ->
                            Text(
                                text = chapter.title,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (index == currentIndex) colors.accent else Color.Unspecified,
                                maxLines = 1,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelect(index) }
                                    .padding(vertical = 10.dp),
                            )
                        }
                    }
                }
            }
        },
    )
}

fun pageTurnModeLabel(mode: PageTurnMode): String = when (mode) {
    PageTurnMode.COVER -> "覆盖"
    PageTurnMode.NONE -> "无动画"
    PageTurnMode.SCROLL -> "上下滚动"
    PageTurnMode.SIMULATION -> "仿真"
}

fun nextPageTurnMode(mode: PageTurnMode): PageTurnMode = when (mode) {
    PageTurnMode.COVER -> PageTurnMode.NONE
    PageTurnMode.NONE -> PageTurnMode.SCROLL
    PageTurnMode.SCROLL -> PageTurnMode.SIMULATION
    PageTurnMode.SIMULATION -> PageTurnMode.COVER
}

fun dualPageModeLabel(mode: DualPageMode): String = when (mode) {
    DualPageMode.AUTO -> "自动"
    DualPageMode.FORCE_DUAL -> "强制"
    DualPageMode.FORCE_SINGLE -> "单栏"
}

fun nextDualPageMode(mode: DualPageMode): DualPageMode = when (mode) {
    DualPageMode.AUTO -> DualPageMode.FORCE_DUAL
    DualPageMode.FORCE_DUAL -> DualPageMode.FORCE_SINGLE
    DualPageMode.FORCE_SINGLE -> DualPageMode.AUTO
}

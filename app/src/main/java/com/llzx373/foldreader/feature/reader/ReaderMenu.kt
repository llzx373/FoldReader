package com.llzx373.foldreader.feature.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.graphics.Color
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
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = colors.background,
            titleContentColor = colors.text,
            navigationIconContentColor = colors.text,
        ),
        modifier = modifier,
    )
}

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
                    Row(modifier = Modifier.padding(start = 12.dp)) {
                        listOf("小", "中", "大").forEachIndexed { level, label ->
                            TextButton(
                                onClick = { onSetMarginLevel(level) },
                                enabled = prefs.marginLevel != level,
                            ) { Text(label) }
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                TextButton(onClick = onOpenCatalog) { Text("目录") }
                TextButton(onClick = {
                    showLayout = !showLayout
                    showBrightness = false
                    showTheme = false
                }) { Text("版式") }
                TextButton(onClick = {
                    showTheme = !showTheme
                    showBrightness = false
                    showLayout = false
                }) { Text("主题") }
                TextButton(onClick = {
                    showBrightness = !showBrightness
                    showLayout = false
                    showTheme = false
                }) { Text("亮度") }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                TextButton(onClick = onCyclePageTurnMode) {
                    Text("翻页：${pageTurnModeLabel(prefs.pageTurnMode)}")
                }
                TextButton(onClick = onCycleDualPageMode) {
                    Text("双页：${dualPageModeLabel(prefs.dualPageMode)}")
                }
                TextButton(onClick = onOpenSettings) { Text("设置") }
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

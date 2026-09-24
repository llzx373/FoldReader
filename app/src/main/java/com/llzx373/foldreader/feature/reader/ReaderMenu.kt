package com.llzx373.foldreader.feature.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SplitButtonDefaults
import androidx.compose.material3.SplitButtonLayout
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.llzx373.foldreader.FoldReaderApplication
import com.llzx373.foldreader.core.data.db.BookEntity
import com.llzx373.foldreader.core.data.db.BookFormat
import com.llzx373.foldreader.core.data.db.PersonAppearanceEntity
import com.llzx373.foldreader.core.data.settings.DualPageMode
import com.llzx373.foldreader.core.data.settings.PageTurnMode
import com.llzx373.foldreader.core.data.settings.ReadingPreferences
import com.llzx373.foldreader.core.data.settings.ReadingTheme
import com.llzx373.foldreader.core.format.Chapter
import com.llzx373.foldreader.feature.bookshelf.ReaderChapterRuleAiEntry
import com.llzx373.foldreader.ui.EncodingPickerDialog
import kotlinx.coroutines.launch

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
    onOpenSearch: () -> Unit = {},
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
            IconButton(onClick = onOpenSearch) {
                Icon(Icons.Filled.Search, contentDescription = "书内搜索")
            }
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
fun BookmarkRibbonIcon(filled: Boolean, tint: Color, contentDescription: String) {
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

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
fun ReaderMenuPanel(
    prefs: ReadingPreferences,
    progressFraction: Float,
    chapterProgress: String? = null,
    displayPageTurnMode: PageTurnMode = prefs.pageTurnMode,
    colors: ReaderColors,
    onSeekFraction: (Float) -> Unit,
    onOpenCatalog: () -> Unit,
    /**
     * PDF 文本模式下的「切到页式」出口；null = 不给这个入口（其它格式没有这个问题）。
     * 与 [onOpenCatalog] 并列放在操作区，因为它同样属于"换一种方式看这本书"。
     */
    onSwitchToPagedMode: (() -> Unit)? = null,
    onOpenAnnotations: () -> Unit,
    onCyclePageTurnMode: () -> Unit,
    onSelectPageTurnMode: (PageTurnMode) -> Unit,
    onCycleDualPageMode: () -> Unit,
    onSetBrightness: (Float) -> Unit,
    onSetFontSize: (Float) -> Unit,
    onSetLineSpacing: (Float) -> Unit,
    onSetMarginLevel: (Int) -> Unit,
    onSetMaxLineChars: (Int) -> Unit = {},
    onSetParagraphSpacing: (Float) -> Unit = {},
    onSetLetterSpacing: (Float) -> Unit = {},
    onSelectTheme: (ReadingTheme) -> Unit,
    onPickCustomBackground: (Int) -> Unit,
    onPickCustomText: (Int) -> Unit,
    autoPageStatus: AutoPageStatus,
    onToggleAutoPage: (Boolean) -> Unit,
    onToggleAutoIndent: (Boolean) -> Unit = {},
    onToggleNormalizeWhitespace: (Boolean) -> Unit = {},
    onCycleAutoPageMode: () -> Unit,
    onCycleAutoPageSpeed: () -> Unit,
    onOpenSettings: () -> Unit,
    /** TTS 听书（M13.1）：朗读中操作区显示「暂停/继续朗读」+「停止朗读」。 */
    ttsPlaying: Boolean,
    ttsPaused: Boolean = false,
    onSpeakFromHere: () -> Unit,
    onSpeakChapter: () -> Unit,
    onToggleSpeakPause: () -> Unit = {},
    onStopSpeaking: () -> Unit,
    /** M19 翻译组：AI 已配置且当前为文本内容时显示整组。 */
    translateAvailable: Boolean = false,
    /** 译文视角下隐藏 TTS 组，翻译组只留视角切换。 */
    viewModeTranslated: Boolean = false,
    /** 目标语言已有可用译文（决定原文模式下是否显示视角切换）。 */
    translationReady: Boolean = false,
    unitTranslateRunning: Boolean = false,
    /** false 时「翻译本章」显示为「翻译本节」（无章节索引的书按固定块切）。 */
    hasChapters: Boolean = false,
    /** M20 视角 2：双页对照可用（当前为双页翻页布局且目标语言已有译本）。 */
    bilingualCompareAvailable: Boolean = false,
    bilingualCompareActive: Boolean = false,
    onToggleBilingualCompare: () -> Unit = {},
    /** M20 视角 3：段落对照可用（滚动模式且目标语言已有译本）。 */
    paragraphCompareAvailable: Boolean = false,
    paragraphCompareActive: Boolean = false,
    onToggleParagraphCompare: () -> Unit = {},
    onTranslatePage: () -> Unit = {},
    onTranslateUnit: () -> Unit = {},
    onToggleViewMode: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var showBrightness by remember { mutableStateOf(false) }
    var showLayout by remember { mutableStateOf(false) }
    var showTheme by remember { mutableStateOf(false) }
    var sliderFraction by remember { mutableStateOf(progressFraction) }
    var fontSizeDraft by remember { mutableStateOf<Float?>(null) }
    var lineSpacingDraft by remember { mutableStateOf<Float?>(null) }
    var maxLineCharsDraft by remember { mutableStateOf<Float?>(null) }
    var paragraphSpacingDraft by remember { mutableStateOf<Float?>(null) }
    var letterSpacingDraft by remember { mutableStateOf<Float?>(null) }
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
            if (chapterProgress != null) {
                Text(
                    text = chapterProgress,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(Alignment.End),
                )
            }
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
                    Text("行长", style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = maxLineCharsDraft ?: prefs.maxLineChars.toFloat(),
                        onValueChange = { maxLineCharsDraft = it },
                        onValueChangeFinished = {
                            maxLineCharsDraft?.let { onSetMaxLineChars(it.toInt()) }
                            maxLineCharsDraft = null
                        },
                        valueRange = 18f..40f,
                        steps = 21,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp),
                    )
                    Text(
                        text = "%.0f".format(maxLineCharsDraft ?: prefs.maxLineChars.toFloat()),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("段距", style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = paragraphSpacingDraft ?: prefs.paragraphSpacingEm,
                        onValueChange = { paragraphSpacingDraft = it },
                        onValueChangeFinished = {
                            paragraphSpacingDraft?.let(onSetParagraphSpacing)
                            paragraphSpacingDraft = null
                        },
                        valueRange = 0f..1.0f,
                        steps = 9,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp),
                    )
                    Text(
                        text = "%.1f".format(paragraphSpacingDraft ?: prefs.paragraphSpacingEm),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("字距", style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = letterSpacingDraft ?: prefs.letterSpacingEm,
                        onValueChange = { letterSpacingDraft = it },
                        onValueChangeFinished = {
                            letterSpacingDraft?.let(onSetLetterSpacing)
                            letterSpacingDraft = null
                        },
                        valueRange = 0f..0.2f,
                        steps = 9,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp),
                    )
                    Text(
                        text = "%.2f".format(letterSpacingDraft ?: prefs.letterSpacingEm),
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
                if (onSwitchToPagedMode != null) {
                    clickableItem(onClick = onSwitchToPagedMode, label = "页式", weight = 1f)
                }
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
            PageTurnModeSplitButton(
                current = displayPageTurnMode,
                onCycle = onCyclePageTurnMode,
                onSelect = onSelectPageTurnMode,
                modifier = Modifier.fillMaxWidth(),
            )
            ButtonGroup(
                overflowIndicator = {},
                modifier = Modifier.fillMaxWidth(),
            ) {
                clickableItem(
                    onClick = onCycleDualPageMode,
                    label = "双页：${dualPageModeLabel(prefs.dualPageMode)}",
                    weight = 1f)
                clickableItem(onClick = onOpenAnnotations, label = "标注", weight = 1f)
                clickableItem(onClick = onOpenSettings, label = "设置", weight = 1f)
            }
            if (!viewModeTranslated) {
                ButtonGroup(
                    overflowIndicator = {},
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (ttsPlaying) {
                        clickableItem(
                            onClick = onToggleSpeakPause,
                            label = if (ttsPaused) "继续朗读" else "暂停朗读",
                            weight = 1f)
                        clickableItem(onClick = onStopSpeaking, label = "停止朗读", weight = 1f)
                    } else {
                        clickableItem(onClick = onSpeakFromHere, label = "从当前位置朗读", weight = 1f)
                        clickableItem(onClick = onSpeakChapter, label = "朗读本章", weight = 1f)
                    }
                }
            }
            if (translateAvailable) {
                ButtonGroup(
                    overflowIndicator = {},
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (!viewModeTranslated) {
                        clickableItem(onClick = onTranslatePage, label = "翻译本页", weight = 1f)
                        clickableItem(
                            onClick = onTranslateUnit,
                            label = when {
                                unitTranslateRunning -> "翻译中…"
                                hasChapters -> "翻译本章"
                                else -> "翻译本节"
                            },
                            weight = 1f)
                    }
                    if (viewModeTranslated || translationReady) {
                        clickableItem(
                            onClick = onToggleViewMode,
                            label = if (viewModeTranslated) "视角：译文" else "视角：原文",
                            weight = 1f)
                    }
                }
                // 视角 2/3：与当前版式互斥的两个对照入口（双页对照只在双页翻页布局，
                // 段落对照只在滚动模式），译文视角下隐藏
                if (!viewModeTranslated && (bilingualCompareAvailable || paragraphCompareAvailable)) {
                    ButtonGroup(
                        overflowIndicator = {},
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (bilingualCompareAvailable) {
                            clickableItem(
                                onClick = onToggleBilingualCompare,
                                label = "双页对照：${if (bilingualCompareActive) "开" else "关"}",
                                weight = 1f)
                        }
                        if (paragraphCompareAvailable) {
                            clickableItem(
                                onClick = onToggleParagraphCompare,
                                label = "段落对照：${if (paragraphCompareActive) "开" else "关"}",
                                weight = 1f)
                        }
                    }
                }
            }
            // 开关都是中文长标签，窄屏下横排会容不下被逐字竖排：改用 FlowRow 自动换行，
            // 每个标签本身强制单行，换不下就整块挪到下一行。
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(0.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                EncodingMenuEntry()
                MenuToggle("自动缩进：${if (prefs.autoIndentEnabled) "开" else "关"}") {
                    onToggleAutoIndent(!prefs.autoIndentEnabled)
                }
                MenuToggle("空白归一化：${if (prefs.normalizeWhitespaceEnabled) "开" else "关"}") {
                    onToggleNormalizeWhitespace(!prefs.normalizeWhitespaceEnabled)
                }
                MenuToggle("自动翻页：${if (prefs.autoPageEnabled) "开" else "关"}") {
                    onToggleAutoPage(!prefs.autoPageEnabled)
                }
                if (prefs.autoPageEnabled) {
                    MenuToggle(autoPageModeLabel(prefs.autoPageMode), onCycleAutoPageMode)
                    MenuToggle(autoPageSpeedLabel(prefs), onCycleAutoPageSpeed)
                }
                if (autoPageStatus.enabled && autoPageStatus.paused) {
                    Text(
                        text = "已暂停",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.accent,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ChapterListDialog(
    chapters: List<Chapter>,
    persons: List<PersonAppearanceEntity>,
    currentIndex: Int,
    remainingText: String?,
    colors: ReaderColors,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
    /** M19：每章的翻译状态标签（null = 不显示），下标与 [chapters] 对齐。 */
    chapterStatus: List<String?> = emptyList(),
    /** M19：该章是否可点「重译」（存在已译/失败单位），下标与 [chapters] 对齐。 */
    chapterRetranslatable: List<Boolean> = emptyList(),
    onRetranslateChapter: ((Int) -> Unit)? = null,
) {
    var showPersons by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
        title = {
            Column {
                Text(if (showPersons) "人物" else "目录")
                ButtonGroup(
                    overflowIndicator = {},
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                ) {
                    toggleableItem(
                        checked = !showPersons,
                        label = "目录",
                        onCheckedChange = { showPersons = false },
                        weight = 1f)
                    toggleableItem(
                        checked = showPersons,
                        label = "人物",
                        onCheckedChange = { showPersons = true },
                        weight = 1f)
                }
            }
        },
        text = {
            Column {
                if (remainingText != null && !showPersons) {
                    Text(
                        text = remainingText,
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.accent,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                if (showPersons) {
                    if (persons.isEmpty()) {
                        Text(
                            text = "未识别到人物",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(vertical = 16.dp),
                        )
                    } else {
                        LazyColumn(modifier = Modifier.height(360.dp)) {
                            itemsIndexed(persons) { _, person ->
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onSelect(person.firstChapterIndex) }
                                        .padding(vertical = 10.dp),
                                ) {
                                    Text(
                                        text = "${person.name} · 出场 ${person.mentionCount} 次",
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                    )
                                    chapters.getOrNull(person.firstChapterIndex)?.let { chapter ->
                                        Text(
                                            text = "首出场：${chapter.title}",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = colors.accent,
                                            maxLines = 1,
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else if (chapters.size <= 1) {
                    Column {
                        Text(
                            text = "未识别到章节，可用进度条跳转",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(vertical = 16.dp),
                        )
                        // M15：仅 TXT + AI 已配置时渲染（组件内部自查，produceState 缓存判据）
                        ReaderChapterRuleAiEntry()
                    }
                } else {
                    val listState = rememberLazyListState()
                    LaunchedEffect(Unit) {
                        listState.scrollToItem(currentIndex.coerceIn(0, chapters.lastIndex))
                    }
                    LazyColumn(state = listState, modifier = Modifier.height(360.dp)) {
                        itemsIndexed(chapters) { index, chapter ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelect(index) }
                                    .padding(
                                        // 目录层级缩进：合并型 EPUB（一个 zip 塞多本书）靠它区分书名与章节。
                                        // 封顶 4 级，免得对话框宽度被吃光。
                                        start = (CHAPTER_INDENT_DP * chapter.depth.coerceAtMost(4)).dp,
                                        top = 10.dp,
                                        bottom = 10.dp,
                                    ),
                            ) {
                                Text(
                                    text = chapter.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (index == currentIndex) colors.accent else Color.Unspecified,
                                    maxLines = 1,
                                    modifier = Modifier.weight(1f),
                                )
                                chapterStatus.getOrNull(index)?.let { status ->
                                    Text(
                                        text = status,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = colors.accent,
                                        maxLines = 1,
                                        modifier = Modifier.padding(start = 8.dp),
                                    )
                                }
                                if (onRetranslateChapter != null &&
                                    chapterRetranslatable.getOrNull(index) == true
                                ) {
                                    Text(
                                        text = "重译",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = colors.accent,
                                        maxLines = 1,
                                        modifier = Modifier
                                            .clickable { onRetranslateChapter(index) }
                                            .padding(start = 8.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
    )
}

/** 目录每级缩进步长（dp），配合 [Chapter.depth] 使用。 */
private const val CHAPTER_INDENT_DP = 14

val pageTurnModes: List<PageTurnMode> = listOf(
    PageTurnMode.COVER,
    PageTurnMode.SIMULATION,
    PageTurnMode.NONE,
    PageTurnMode.SCROLL,
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PageTurnModeSplitButton(
    current: PageTurnMode,
    onCycle: () -> Unit,
    onSelect: (PageTurnMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        SplitButtonLayout(
            leadingButton = {
                SplitButtonDefaults.LeadingButton(
                    onClick = onCycle,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("翻页：${pageTurnModeLabel(current)}")
                }
            },
            trailingButton = {
                SplitButtonDefaults.TrailingButton(onClick = { expanded = !expanded }) {
                    Icon(
                        imageVector = if (expanded) {
                            Icons.Filled.KeyboardArrowUp
                        } else {
                            Icons.Filled.KeyboardArrowDown
                        },
                        contentDescription = "展开翻页方式选项",
                        modifier = Modifier.size(SplitButtonDefaults.TrailingIconSize),
                    )
                }
            },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            pageTurnModes.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(pageTurnModeLabel(mode)) },
                    onClick = {
                        expanded = false
                        onSelect(mode)
                    },
                    trailingIcon = if (mode == current) {
                        { Icon(Icons.Filled.Check, contentDescription = null) }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

fun pageTurnModeLabel(mode: PageTurnMode): String = when (mode) {
    PageTurnMode.COVER -> "覆盖"
    PageTurnMode.SIMULATION -> "仿真"
    PageTurnMode.NONE -> "无动画"
    PageTurnMode.SCROLL -> "上下滚动"
}

fun nextPageTurnMode(mode: PageTurnMode): PageTurnMode = when (mode) {
    PageTurnMode.COVER -> PageTurnMode.SIMULATION
    PageTurnMode.SIMULATION -> PageTurnMode.NONE
    PageTurnMode.NONE -> PageTurnMode.SCROLL
    PageTurnMode.SCROLL -> PageTurnMode.COVER
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

/**
 * 菜单里的一行开关/循环按钮：标签强制单行、内边距收紧。
 * 放在 [FlowRow] 里时，宁可整块换到下一行，也不要把中文标签压成逐字竖排。
 */
@Composable
private fun MenuToggle(label: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(text = label, maxLines = 1, softWrap = false)
    }
}

/**
 * 编码切换入口：写 BookEntity.encoding（空串=自动检测），
 * ReaderViewModel 监听该字段变化后按当前锚点重开书籍。
 */
@Composable
private fun EncodingMenuEntry() {
    val context = LocalContext.current
    val container = remember(context) {
        (context.applicationContext as? FoldReaderApplication)?.container
    } ?: return
    val scope = rememberCoroutineScope()
    val activeBookId by container.activeReaderBookId.collectAsState()
    val bookId = activeBookId ?: return
    var refreshTick by remember { mutableIntStateOf(0) }
    val book by produceState<BookEntity?>(initialValue = null, bookId, refreshTick) {
        value = container.bookshelfRepository.getBook(bookId)
    }
    val currentBook = book ?: return
    // 非 TXT 格式固定 UTF-8（压平产物），不提供编码切换
    if (currentBook.format != BookFormat.TXT) return
    val encoding = currentBook.encoding
    var showPicker by remember { mutableStateOf(false) }
    TextButton(
        onClick = { showPicker = true },
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            text = "编码：${encoding.takeIf { it.isNotBlank() } ?: "自动检测"}",
            maxLines = 1,
            softWrap = false,
        )
    }
    if (showPicker) {
        EncodingPickerDialog(
            currentEncoding = encoding,
            onSelect = { name ->
                showPicker = false
                scope.launch {
                    container.bookshelfRepository.updateEncoding(bookId, name ?: "")
                    refreshTick++
                }
            },
            onDismiss = { showPicker = false },
        )
    }
}

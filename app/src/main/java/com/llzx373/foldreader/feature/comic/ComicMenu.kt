package com.llzx373.foldreader.feature.comic

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.llzx373.foldreader.core.data.settings.AutoPageMode
import com.llzx373.foldreader.core.data.settings.ComicDirection
import com.llzx373.foldreader.core.data.settings.ComicFitMode
import com.llzx373.foldreader.core.data.settings.PageTurnMode
import com.llzx373.foldreader.core.data.settings.ReadingPreferences
import com.llzx373.foldreader.core.data.settings.ReadingTheme
import com.llzx373.foldreader.core.paged.PagedReaderFeatures
import com.llzx373.foldreader.feature.reader.BookmarkRibbonIcon
import com.llzx373.foldreader.feature.reader.ReaderColors
import com.llzx373.foldreader.feature.reader.ThemePicker
import com.llzx373.foldreader.feature.reader.autoPageModeLabel
import com.llzx373.foldreader.feature.reader.autoPageSpeedLabel
import com.llzx373.foldreader.feature.reader.formatPercent

/**
 * 漫画可用的翻页方式：覆盖/仿真/无动画为左右翻页，滚动为纵向连续（条漫）。
 */
private val COMIC_PAGE_TURN_MODES = listOf(
    PageTurnMode.COVER,
    PageTurnMode.SIMULATION,
    PageTurnMode.NONE,
    PageTurnMode.SCROLL,
)

/** 纵向连续滚动的页间距档位（dp）；0 = 无缝，长条漫拼接时用。 */
private val COMIC_SCROLL_GAPS = listOf(0, 8, 24)

private fun comicScrollGapLabel(gapDp: Int): String = when (gapDp) {
    0 -> "无缝"
    8 -> "小间距"
    else -> "大间距"
}

private val COMIC_DIRECTIONS = listOf(ComicDirection.LTR, ComicDirection.RTL)

private val COMIC_FIT_MODES = listOf(
    ComicFitMode.FIT_PAGE,
    ComicFitMode.FIT_WIDTH,
    ComicFitMode.FIT_HEIGHT,
    ComicFitMode.ORIGINAL,
)

private fun comicFitLabel(mode: ComicFitMode): String = when (mode) {
    ComicFitMode.FIT_PAGE -> "整页"
    ComicFitMode.FIT_WIDTH -> "宽度"
    ComicFitMode.FIT_HEIGHT -> "高度"
    ComicFitMode.ORIGINAL -> "原图"
}

private fun comicPageTurnLabel(mode: PageTurnMode): String = when (mode) {
    PageTurnMode.COVER -> "覆盖"
    PageTurnMode.SIMULATION -> "仿真"
    PageTurnMode.NONE -> "无动画"
    PageTurnMode.SCROLL -> "滚动"
}

private fun comicDirectionLabel(direction: ComicDirection): String = when (direction) {
    ComicDirection.LTR -> "左→右"
    ComicDirection.RTL -> "右→左"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComicTopBar(
    title: String,
    pageText: String?,
    colors: ReaderColors,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenThumbnails: () -> Unit,
    /** 当前页是否已有「页级书签」（顶栏缎带就是它的开关）。 */
    bookmarked: Boolean,
    onToggleBookmark: () -> Unit,
    onOpenBookmarks: () -> Unit,
) {
    TopAppBar(
        title = {
            Column {
                Text(
                    text = title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                )
                if (pageText != null) {
                    Text(
                        text = pageText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            IconButton(onClick = onToggleBookmark) {
                BookmarkRibbonIcon(
                    filled = bookmarked,
                    tint = colors.text,
                    contentDescription = if (bookmarked) "取消本页书签" else "给本页加书签",
                )
            }
            IconButton(onClick = onOpenBookmarks) {
                Icon(Icons.AutoMirrored.Filled.List, contentDescription = "书签列表")
            }
            IconButton(onClick = onOpenThumbnails) {
                Icon(Icons.Filled.List, contentDescription = "页缩略图")
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Filled.Settings, contentDescription = "阅读设置")
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.background),
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ComicMenuPanel(
    prefs: ReadingPreferences,
    colors: ReaderColors,
    /** 当前格式的能力差异：方向/跨页/无缝这些对 PDF 无意义，整片隐藏。 */
    features: PagedReaderFeatures,
    progressFraction: Float,
    pageText: String?,
    onSeekFraction: (Float) -> Unit,
    /** 打开「跳到指定页」输入框（页数多的文档拖进度条很难点准）。 */
    onOpenJumpToPage: () -> Unit,
    /** 文件超过阈值时的提示大小（MB）；null = 不提示。 */
    largeFileHintMb: Int?,
    onOpenThumbnails: () -> Unit,
    /** 文档有内嵌目录（PDF）时才给入口；漫画不显示。 */
    hasOutline: Boolean,
    onOpenOutline: () -> Unit,
    /** 打开「标注」列表：页式高亮/下划线都在这里跳转与删除。 */
    onOpenAnnotations: () -> Unit,
    /** 全文搜索（只有文本型 PDF 搜得出东西，没有文字层的会由对话框自己说明）。 */
    onOpenSearch: () -> Unit,
    /** 同系列前后卷：为 null 表示没有相邻的那一卷（按钮禁用）。整片在都为空时不显示。 */
    onPrevVolume: (() -> Unit)?,
    onNextVolume: (() -> Unit)?,
    /** 形如「3/12」的系列位置；单卷或识别不出时为 null。 */
    seriesPosition: String?,
    onOpenSeries: () -> Unit,
    /** 自动翻页：开关、循环档位（间隔秒数 / 滚动速度）、切换间隔/滚动两种模式。 */
    onToggleAutoPage: (Boolean) -> Unit,
    onCycleAutoPageSetting: () -> Unit,
    onCycleAutoPageMode: () -> Unit,
    /**
     * PDF 切到文字模式。null = 不可用（不是 PDF，或正文还没抽出来）。
     * 配合 [PagedReaderFeatures.scanned] 决定是给开关还是给一句说明。
     */
    onSwitchToTextMode: (() -> Unit)?,
    onSelectPageTurnMode: (PageTurnMode) -> Unit,
    onSelectDirection: (ComicDirection) -> Unit,
    onSelectFitMode: (ComicFitMode) -> Unit,
    onSelectScrollGap: (Int) -> Unit,
    onToggleCoverAlone: (Boolean) -> Unit,
    onToggleSpreadAutoDetect: (Boolean) -> Unit,
    onSetBrightness: (Float) -> Unit,
    onToggleKeepScreenOn: (Boolean) -> Unit,
    onSelectTheme: (ReadingTheme) -> Unit,
    onPickCustomBackground: (Int) -> Unit,
    onPickCustomText: (Int) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var sliderFraction by remember { mutableStateOf(progressFraction) }
    var showTheme by remember { mutableStateOf(false) }
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = colors.background,
        contentColor = colors.text,
        tonalElevation = 4.dp,
        shadowElevation = 8.dp,
    ) {
        Column(
            modifier = Modifier
                .heightIn(max = 430.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Slider(
                value = sliderFraction,
                onValueChange = { sliderFraction = it },
                onValueChangeFinished = { onSeekFraction(sliderFraction) },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = pageText ?: "—", style = MaterialTheme.typography.labelSmall)
                Text(text = formatPercent(sliderFraction), style = MaterialTheme.typography.labelSmall)
                TextButton(onClick = onOpenJumpToPage) { Text("跳到…") }
            }
            // 超大 PDF 的官方已知性能问题：先说清楚，别让人以为是卡住了
            if (largeFileHintMb != null) {
                Text(
                    text = "文件较大（${largeFileHintMb}MB），翻页与缩放可能偏慢",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Text("翻页", style = MaterialTheme.typography.labelMedium)
                SingleChoiceSegmentedButtonRow(modifier = Modifier.padding(start = 12.dp)) {
                    COMIC_PAGE_TURN_MODES.forEachIndexed { index, mode ->
                        SegmentedButton(
                            selected = prefs.pageTurnMode == mode,
                            onClick = { onSelectPageTurnMode(mode) },
                            shape = SegmentedButtonDefaults.itemShape(index, COMIC_PAGE_TURN_MODES.size),
                        ) { Text(comicPageTurnLabel(mode), maxLines = 1) }
                    }
                }
            }

            // 滚动模式下面向"书页"的设置没有意义，换成滚动专属的页间距
            if (prefs.pageTurnMode == PageTurnMode.SCROLL) {
                if (features.seamlessScroll) Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    Text("页间距", style = MaterialTheme.typography.labelMedium)
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.padding(start = 12.dp)) {
                        COMIC_SCROLL_GAPS.forEachIndexed { index, gap ->
                            SegmentedButton(
                                selected = prefs.comicScrollGapDp == gap,
                                onClick = { onSelectScrollGap(gap) },
                                shape = SegmentedButtonDefaults.itemShape(index, COMIC_SCROLL_GAPS.size),
                            ) { Text(comicScrollGapLabel(gap), maxLines = 1) }
                        }
                    }
                }
            } else {
                if (features.rtl) Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    Text("方向", style = MaterialTheme.typography.labelMedium)
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.padding(start = 12.dp)) {
                        COMIC_DIRECTIONS.forEachIndexed { index, direction ->
                            SegmentedButton(
                                selected = prefs.comicDirection == direction,
                                onClick = { onSelectDirection(direction) },
                                shape = SegmentedButtonDefaults.itemShape(index, COMIC_DIRECTIONS.size),
                            ) { Text(comicDirectionLabel(direction), maxLines = 1) }
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    Text("适应", style = MaterialTheme.typography.labelMedium)
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.padding(start = 12.dp)) {
                        COMIC_FIT_MODES.forEachIndexed { index, mode ->
                            SegmentedButton(
                                selected = prefs.comicFitMode == mode,
                                onClick = { onSelectFitMode(mode) },
                                shape = SegmentedButtonDefaults.itemShape(index, COMIC_FIT_MODES.size),
                            ) { Text(comicFitLabel(mode), maxLines = 1) }
                        }
                    }
                }

                if (features.spreadPairing) {
                    SwitchRow(
                        label = "封面单独成页",
                        checked = prefs.comicDualPageCoverAlone,
                        onCheckedChange = onToggleCoverAlone,
                    )
                    SwitchRow(
                        label = "跨页大图独占整宽",
                        checked = prefs.comicSpreadAutoDetect,
                        onCheckedChange = onToggleSpreadAutoDetect,
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 4.dp),
            ) {
                Text("亮度", style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = prefs.readerBrightness,
                    onValueChange = onSetBrightness,
                    valueRange = -1f..1f,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                )
                Text(
                    text = if (prefs.readerBrightness < 0f) "跟随系统"
                    else "${(prefs.readerBrightness * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            SwitchRow(
                label = "屏幕常亮",
                checked = prefs.keepScreenOn,
                onCheckedChange = onToggleKeepScreenOn,
            )

            // PDF：能取字就给「切到文字模式」，确认是扫描件就明说——
            // 而不是摆一个按了没反应的按钮让人反复点
            if (onSwitchToTextMode != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Text("阅读模式", style = MaterialTheme.typography.labelMedium)
                    TextButton(
                        onClick = onSwitchToTextMode,
                        modifier = Modifier.padding(start = 8.dp),
                    ) { Text("切到文字模式") }
                }
            } else if (features.scanned) {
                Text(
                    text = "这是扫描件，暂不支持取字",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (hasOutline) TextButton(onClick = onOpenOutline) { Text("目录") }
                TextButton(onClick = onOpenThumbnails) { Text("页缩略图") }
                TextButton(onClick = onOpenSearch) { Text("搜索") }
                TextButton(onClick = onOpenAnnotations) { Text("标注") }
                TextButton(onClick = { showTheme = !showTheme }) { Text("配色") }
                TextButton(onClick = onOpenSettings) { Text("更多设置") }
            }
            if (onPrevVolume != null || onNextVolume != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    TextButton(onClick = { onPrevVolume?.invoke() }, enabled = onPrevVolume != null) {
                        Text("上一卷")
                    }
                    TextButton(onClick = { onNextVolume?.invoke() }, enabled = onNextVolume != null) {
                        Text("下一卷")
                    }
                    if (seriesPosition != null) {
                        Text(
                            text = seriesPosition,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = onOpenSeries) { Text("同系列") }
                }
            }
            // 自动翻页：单击开关；开着时再点循环档位（间隔秒数 / 滚动速度）
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                TextButton(
                    onClick = {
                        if (prefs.autoPageEnabled) {
                            onCycleAutoPageSetting()
                        } else {
                            onToggleAutoPage(true)
                        }
                    },
                ) {
                    Text("自动翻页：${autoPageSpeedLabel(prefs)}")
                }
                if (prefs.autoPageEnabled) {
                    TextButton(onClick = { onToggleAutoPage(false) }) { Text("停止") }
                }
                TextButton(onClick = onCycleAutoPageMode) {
                    Text(autoPageModeLabel(prefs.autoPageMode))
                }
            }
            if (showTheme) {
                ThemePicker(
                    prefs = prefs,
                    onSelectTheme = onSelectTheme,
                    onPickCustomBackground = onPickCustomBackground,
                    onPickCustomText = onPickCustomText,
                )
            }
        }
    }
}

/**
 * 加密文档的密码输入框。
 * 密码只在「打开文档」这一条链路上传一次：不落库、不进备份、不写日志。
 */
@Composable
fun PasswordDialog(onSubmit: (String) -> Unit, onDismiss: () -> Unit) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("文档需要密码") },
        text = {
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("密码") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(password) }, enabled = password.isNotEmpty()) {
                Text("打开")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

package com.llzx373.foldreader.feature.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.llzx373.foldreader.BuildConfig
import com.llzx373.foldreader.FoldReaderApplication
import com.llzx373.foldreader.core.backup.BackupManager
import com.llzx373.foldreader.core.data.settings.DarkThemeOption
import com.llzx373.foldreader.core.data.settings.DualPageMode
import com.llzx373.foldreader.core.data.settings.PageTurnMode
import com.llzx373.foldreader.core.foldable.FoldableUiState
import com.llzx373.foldreader.core.format.ChapterRules
import com.llzx373.foldreader.core.format.txt.UriChannels
import com.llzx373.foldreader.core.reader.FontManager
import com.llzx373.foldreader.feature.reader.ThemePicker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(foldableUiState: FoldableUiState) {
    val context = LocalContext.current
    val app = context.applicationContext as FoldReaderApplication
    val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(app.container))
    val prefs by viewModel.preferences.collectAsState()
    val importedFonts by viewModel.importedFonts.collectAsState()
    val readingStats by viewModel.readingStats.collectAsState()
    var showChapterRulesDialog by remember { mutableStateOf(false) }
    var showAdRulesDialog by remember { mutableStateOf(false) }
    var showLicensesDialog by remember { mutableStateOf(false) }
    var importResult by remember { mutableStateOf<BackupManager.ImportResult?>(null) }

    val fontPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            val name = UriChannels.displayName(context, uri)
            viewModel.importFont(uri, name) { ok ->
                val message = if (ok) "字体已导入并应用" else "字体导入失败"
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    val backupExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            viewModel.exportBackup(uri) { error ->
                val message = error?.let { "导出失败：$it" } ?: "备份已导出"
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }
    }
    val backupImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            viewModel.importBackup(uri) { result, error ->
                if (error != null || result == null) {
                    Toast.makeText(
                        context,
                        "导入失败：${error ?: "未知错误"}",
                        Toast.LENGTH_LONG,
                    ).show()
                } else {
                    importResult = result
                }
            }
        }
    }

    Scaffold(
        topBar = { CenterAlignedTopAppBar(title = { Text("设置") }) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState()),
        ) {
            SectionHeader("阅读显示")
            SliderSetting(
                label = "字号",
                value = prefs.fontSizeSp,
                valueRange = 12f..32f,
                steps = 9,
                format = { "%.0f sp".format(it) },
                onChange = viewModel::updateFontSize,
            )
            SliderSetting(
                label = "行距",
                value = prefs.lineSpacingMultiplier,
                valueRange = 1.0f..2.2f,
                steps = 11,
                format = { "%.1f".format(it) },
                onChange = viewModel::updateLineSpacing,
            )
            SliderSetting(
                label = "每行字数上限",
                value = prefs.maxLineChars.toFloat(),
                valueRange = 18f..40f,
                steps = 21,
                format = { "%.0f 字".format(it) },
                onChange = { viewModel.updateMaxLineChars(it.toInt()) },
            )
            SliderSetting(
                label = "段距",
                value = prefs.paragraphSpacingEm,
                valueRange = 0f..1.0f,
                steps = 9,
                format = { "%.1f".format(it) },
                onChange = viewModel::updateParagraphSpacing,
            )
            SliderSetting(
                label = "字距",
                value = prefs.letterSpacingEm,
                valueRange = 0f..0.2f,
                steps = 9,
                format = { "%.2f".format(it) },
                onChange = viewModel::updateLetterSpacing,
            )
            SegmentedSetting(
                label = "边距",
                options = listOf("小", "中", "大"),
                selectedIndex = prefs.marginLevel.coerceIn(0, 2),
                onSelect = viewModel::updateMarginLevel,
            )
            Text(
                text = "主题",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            ThemePicker(
                prefs = prefs,
                onSelectTheme = viewModel::updateTheme,
                onPickCustomBackground = { viewModel.updateCustomBackground(it) },
                onPickCustomText = { viewModel.updateCustomText(it) },
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            SectionHeader("字体")
            val systemFonts = listOf("default", "serif", "sans_serif", "monospace")
            (systemFonts + importedFonts.map { "${FontManager.FILE_PREFIX}$it" }).forEach { key ->
                ListItem(
                    headlineContent = { Text(FontManager.displayNameOf(key)) },
                    trailingContent = {
                        Switch(
                            checked = prefs.fontKey == key,
                            onCheckedChange = { if (it) viewModel.updateFontKey(key) },
                        )
                    },
                )
            }
            ListItem(
                headlineContent = {
                    TextButton(onClick = { fontPicker.launch(arrayOf("*/*")) }) {
                        Text("导入字体（TTF/OTF）")
                    }
                },
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            SectionHeader("翻页与交互")
            SegmentedSetting(
                label = "双页模式",
                options = listOf("自动", "强制双页", "强制单栏"),
                selectedIndex = when (prefs.dualPageMode) {
                    DualPageMode.AUTO -> 0
                    DualPageMode.FORCE_DUAL -> 1
                    DualPageMode.FORCE_SINGLE -> 2
                },
                onSelect = { index ->
                    viewModel.updateDualPageMode(
                        when (index) {
                            1 -> DualPageMode.FORCE_DUAL
                            2 -> DualPageMode.FORCE_SINGLE
                            else -> DualPageMode.AUTO
                        },
                    )
                },
            )
            SwitchSetting("宽屏双页（无铰链设备）", prefs.wideScreenDualPage, viewModel::updateWideScreenDualPage)
            SwitchSetting("双页右栏下移一行（避让摄像头）", prefs.dualRightPageDrop, viewModel::updateDualRightPageDrop)
            SegmentedSetting(
                label = "翻页方式",
                options = listOf("覆盖", "无动画", "上下滚动", "仿真"),
                selectedIndex = when (prefs.pageTurnMode) {
                    PageTurnMode.COVER -> 0
                    PageTurnMode.NONE -> 1
                    PageTurnMode.SCROLL -> 2
                    PageTurnMode.SIMULATION -> 3
                },
                onSelect = { index ->
                    viewModel.updatePageTurnMode(
                        when (index) {
                            1 -> PageTurnMode.NONE
                            2 -> PageTurnMode.SCROLL
                            3 -> PageTurnMode.SIMULATION
                            else -> PageTurnMode.COVER
                        },
                    )
                },
            )
            SliderSetting(
                label = "热区比例",
                value = prefs.pageTurnHotspotRatio,
                valueRange = 0.15f..0.45f,
                steps = 5,
                format = { "%.0f%%".format(it * 100) },
                onChange = viewModel::updateHotspotRatio,
            )
            SwitchSetting("音量键翻页", prefs.volumeKeyPagingEnabled, viewModel::updateVolumeKeyPaging)
            SwitchSetting("左侧滑动调亮度", prefs.brightnessGestureEnabled, viewModel::updateBrightnessGesture)
            SwitchSetting("滑动翻页手势", prefs.swipeGestureEnabled, viewModel::updateSwipeGesture)
            SwitchSetting("屏幕常亮", prefs.keepScreenOn, viewModel::updateKeepScreenOn)

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            SectionHeader("页眉页脚")
            SwitchSetting("章节名", prefs.showChapterTitle, viewModel::updateShowChapterTitle)
            SwitchSetting("阅读进度", prefs.showPageProgress, viewModel::updateShowPageProgress)
            SwitchSetting("页码", prefs.showPageNumber, viewModel::updateShowPageNumber)
            SwitchSetting("电量", prefs.showBattery, viewModel::updateShowBattery)
            SwitchSetting("时间", prefs.showTime, viewModel::updateShowTime)

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            SectionHeader("章节识别")
            ListItem(
                headlineContent = { Text("章节识别规则") },
                supportingContent = {
                    Text(
                        if (prefs.customChapterRules.isEmpty()) {
                            "使用内置规则（第X章、Chapter N、数字分卷、楔子/番外等）"
                        } else {
                            "自定义 ${prefs.customChapterRules.size} 条（优先于内置规则）"
                        },
                    )
                },
                modifier = Modifier.clickable { showChapterRulesDialog = true },
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            SectionHeader("智能清理")
            ListItem(
                headlineContent = { Text("去广告行规则") },
                supportingContent = {
                    Text(
                        if (prefs.adCleanRules.isEmpty()) {
                            "未配置规则，导入时勾选「去广告行」不生效"
                        } else {
                            "已配置 ${prefs.adCleanRules.size} 条正则"
                        },
                    )
                },
                modifier = Modifier.clickable { showAdRulesDialog = true },
            )
            ListItem(
                headlineContent = { Text("关于智能清理") },
                supportingContent = {
                    Text("导入书籍时可选去空行 / 去广告行 / 繁简转换，清理结果保存为副本，原文件不受影响；繁简转换为单字级映射，不做词组级转换")
                },
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            SectionHeader("外观与书架")
            SegmentedSetting(
                label = "夜间模式",
                options = listOf("随系统", "浅色", "深色"),
                selectedIndex = when (prefs.darkThemeOption) {
                    DarkThemeOption.SYSTEM -> 0
                    DarkThemeOption.LIGHT -> 1
                    DarkThemeOption.DARK -> 2
                },
                onSelect = { index ->
                    viewModel.updateDarkThemeOption(
                        when (index) {
                            1 -> DarkThemeOption.LIGHT
                            2 -> DarkThemeOption.DARK
                            else -> DarkThemeOption.SYSTEM
                        },
                    )
                },
            )
            SwitchSetting("书架网格视图", prefs.bookshelfGridView, viewModel::updateBookshelfGridView)

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            ReadingStatsSection(stats = readingStats)

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            SectionHeader("备份与恢复")
            ListItem(
                headlineContent = { Text("导出备份") },
                supportingContent = {
                    Text("书架数据与阅读偏好导出为 JSON（不含书籍文件本身；偏移索引与分页缓存不包含，打开书籍时自动重建）")
                },
                modifier = Modifier.clickable {
                    backupExportLauncher.launch(defaultBackupFileName())
                },
            )
            ListItem(
                headlineContent = { Text("导入备份") },
                supportingContent = {
                    Text("恢复按书籍内容哈希匹配，请先将原书 TXT 文件导入书架，再执行恢复；未匹配的书籍会列出")
                },
                modifier = Modifier.clickable {
                    backupImportLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                },
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            SectionHeader("关于")
            ListItem(
                headlineContent = { Text("FoldReader") },
                supportingContent = { Text("版本 ${BuildConfig.VERSION_NAME}") },
            )
            ListItem(
                headlineContent = { Text("开源许可") },
                supportingContent = { Text("第三方开源组件许可清单") },
                modifier = Modifier.clickable { showLicensesDialog = true },
            )
            ListItem(
                headlineContent = { Text("设计理念") },
                supportingContent = { Text("为折叠屏而生的本地阅读器：展开双页如书，折起单手从容") },
            )
        }
    }

    if (showChapterRulesDialog) {
        ChapterRulesDialog(
            customRules = prefs.customChapterRules,
            onAdd = viewModel::addCustomChapterRule,
            onRemove = viewModel::removeCustomChapterRule,
            onDismiss = { showChapterRulesDialog = false },
        )
    }

    if (showAdRulesDialog) {
        AdCleanRulesDialog(
            rules = prefs.adCleanRules,
            onAdd = viewModel::addAdCleanRule,
            onRemove = viewModel::removeAdCleanRule,
            onDismiss = { showAdRulesDialog = false },
        )
    }

    if (showLicensesDialog) {
        OpenSourceLicensesDialog(onDismiss = { showLicensesDialog = false })
    }

    importResult?.let { result ->
        AlertDialog(
            onDismissRequest = { importResult = null },
            title = { Text("恢复完成") },
            text = {
                Column {
                    Text(
                        "已恢复 ${result.restoredBooks} 本书" +
                            "（书签 ${result.restoredBookmarks}、标注 ${result.restoredAnnotations}、" +
                            "阅读统计 ${result.restoredSessions} 条）",
                    )
                    Text(
                        "恢复按书籍内容哈希匹配，请先将原书 TXT 文件导入书架，再执行恢复。",
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    if (result.missingBookTitles.isNotEmpty()) {
                        Text(
                            "以下 ${result.missingBookTitles.size} 本书未在书架找到，暂未恢复：",
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        result.missingBookTitles.forEach { Text("· $it") }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { importResult = null }) { Text("知道了") }
            },
        )
    }
}

private fun defaultBackupFileName(): String {
    val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
    return "foldreader-backup-$stamp.json"
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun SliderSetting(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    format: (Float) -> String,
    onChange: (Float) -> Unit,
) {
    var draft by remember { mutableStateOf<Float?>(null) }
    ListItem(
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, modifier = Modifier.padding(end = 12.dp))
                Slider(
                    value = draft ?: value,
                    onValueChange = { draft = it },
                    onValueChangeFinished = {
                        draft?.let(onChange)
                        draft = null
                    },
                    valueRange = valueRange,
                    steps = steps,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = format(draft ?: value),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SegmentedSetting(
    label: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    ListItem(
        headlineContent = {
            Column {
                Text(label, modifier = Modifier.padding(bottom = 8.dp))
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    options.forEachIndexed { index, option ->
                        SegmentedButton(
                            selected = selectedIndex == index,
                            onClick = { onSelect(index) },
                            shape = SegmentedButtonDefaults.itemShape(index, options.size),
                        ) { Text(option) }
                    }
                }
            }
        },
    )
}

@Composable
private fun SwitchSetting(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
    )
}

private val openSourceLicenses: List<Pair<String, String>> = listOf(
    "Jetpack Compose（UI / Material3 / Material Icons）" to "Apache License 2.0",
    "AndroidX（Activity / Lifecycle / Navigation / DataStore / Window / Adaptive）" to "Apache License 2.0",
    "Room 持久化库" to "Apache License 2.0",
    "kotlinx-coroutines" to "Apache License 2.0",
    "OpenCC 繁简转换字表（BYVoid/OpenCC）" to "Apache License 2.0",
)

@Composable
private fun OpenSourceLicensesDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
        title = { Text("开源许可") },
        text = {
            Column {
                openSourceLicenses.forEach { (name, license) ->
                    Text(name, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = license,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 10.dp),
                    )
                }
                Text(
                    text = "繁简转换使用 OpenCC 单字映射表（assets/ts_map.txt），感谢 BYVoid/OpenCC 项目。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

@Composable
private fun AdCleanRulesDialog(
    rules: List<String>,
    onAdd: (String) -> Boolean,
    onRemove: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
        title = { Text("去广告行规则") },
        text = {
            Column {
                Text(
                    text = "导入时勾选「去广告行」后，匹配以下任一正则的整行将被删除，仅对之后导入的书生效。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                rules.forEachIndexed { index, rule ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = rule,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { onRemove(index) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "删除规则")
                        }
                    }
                }
                OutlinedTextField(
                    value = draft,
                    onValueChange = {
                        draft = it
                        invalid = false
                    },
                    label = { Text("新增正则（匹配行内任意位置）") },
                    singleLine = true,
                    isError = invalid,
                    supportingText = if (invalid) {
                        { Text("正则表达式不合法，未保存") }
                    } else {
                        null
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
                TextButton(
                    enabled = draft.isNotBlank(),
                    onClick = {
                        if (onAdd(draft)) {
                            draft = ""
                            invalid = false
                        } else {
                            invalid = true
                        }
                    },
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text("添加")
                }
            }
        },
    )
}

@Composable
private fun ChapterRulesDialog(
    customRules: List<String>,
    onAdd: (String) -> Boolean,
    onRemove: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
        title = { Text("章节识别规则") },
        text = {
            Column {
                Text(
                    text = "内置规则（${ChapterRules.DEFAULT.size} 条）：",
                    style = MaterialTheme.typography.labelMedium,
                )
                ChapterRules.DEFAULT.forEach { rule ->
                    Text(
                        text = rule.pattern,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = "自定义规则先于内置规则匹配，仅对之后导入或重建目录的书生效。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                customRules.forEachIndexed { index, rule ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = rule,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { onRemove(index) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "删除规则")
                        }
                    }
                }
                OutlinedTextField(
                    value = draft,
                    onValueChange = {
                        draft = it
                        invalid = false
                    },
                    label = { Text("新增正则（匹配整行标题）") },
                    singleLine = true,
                    isError = invalid,
                    supportingText = if (invalid) {
                        { Text("正则表达式不合法，未保存") }
                    } else {
                        null
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
                TextButton(
                    enabled = draft.isNotBlank(),
                    onClick = {
                        if (onAdd(draft)) {
                            draft = ""
                            invalid = false
                        } else {
                            invalid = true
                        }
                    },
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text("添加")
                }
            }
        },
    )
}

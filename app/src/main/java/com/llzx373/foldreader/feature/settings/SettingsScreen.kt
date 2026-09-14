package com.llzx373.foldreader.feature.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
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
import com.llzx373.foldreader.FoldReaderApplication
import com.llzx373.foldreader.core.data.settings.DarkThemeOption
import com.llzx373.foldreader.core.data.settings.DualPageMode
import com.llzx373.foldreader.core.data.settings.PageTurnMode
import com.llzx373.foldreader.core.foldable.FoldableUiState
import com.llzx373.foldreader.core.format.txt.UriChannels
import com.llzx373.foldreader.core.reader.FontManager
import com.llzx373.foldreader.feature.reader.ThemePicker

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(foldableUiState: FoldableUiState) {
    val context = LocalContext.current
    val app = context.applicationContext as FoldReaderApplication
    val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(app.container))
    val prefs by viewModel.preferences.collectAsState()
    val importedFonts by viewModel.importedFonts.collectAsState()
    val readingStats by viewModel.readingStats.collectAsState()

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
            SwitchSetting("屏幕常亮", prefs.keepScreenOn, viewModel::updateKeepScreenOn)

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            SectionHeader("页眉页脚")
            SwitchSetting("章节名", prefs.showChapterTitle, viewModel::updateShowChapterTitle)
            SwitchSetting("阅读进度", prefs.showPageProgress, viewModel::updateShowPageProgress)
            SwitchSetting("电量", prefs.showBattery, viewModel::updateShowBattery)
            SwitchSetting("时间", prefs.showTime, viewModel::updateShowTime)

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
        }
    }
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

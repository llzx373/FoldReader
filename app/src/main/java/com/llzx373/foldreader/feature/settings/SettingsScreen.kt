package com.llzx373.foldreader.feature.settings

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.viewmodel.compose.viewModel
import com.llzx373.foldreader.BuildConfig
import com.llzx373.foldreader.FoldReaderApplication
import com.llzx373.foldreader.core.ai.AiProtocol
import com.llzx373.foldreader.core.ai.AiTargetLang
import com.llzx373.foldreader.core.ai.gate.AiContentGate
import com.llzx373.foldreader.core.ai.prompt.BuiltinPrompts
import com.llzx373.foldreader.core.ai.prompt.SelectionTranslatePrompt
import com.llzx373.foldreader.core.backup.BackupManager
import com.llzx373.foldreader.core.data.settings.ComicDirection
import com.llzx373.foldreader.core.data.settings.ComicFitMode
import com.llzx373.foldreader.core.data.settings.DarkThemeOption
import com.llzx373.foldreader.core.data.settings.DualPageMode
import com.llzx373.foldreader.core.data.settings.PageTurnMode
import com.llzx373.foldreader.core.data.settings.ReadingPreferences
import com.llzx373.foldreader.core.data.settings.TapAction
import com.llzx373.foldreader.core.debug.DiagnosticLog
import com.llzx373.foldreader.core.foldable.FoldableUiState
import com.llzx373.foldreader.core.format.ChapterRules
import com.llzx373.foldreader.core.format.clean.CleanLevel
import com.llzx373.foldreader.core.format.clean.CleanToggles
import com.llzx373.foldreader.core.format.txt.UriChannels
import com.llzx373.foldreader.core.reader.FontManager
import com.llzx373.foldreader.core.tts.TtsSpeech
import com.llzx373.foldreader.feature.lock.AppLock
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
    val modelStatus by viewModel.modelStatus.collectAsState()
    val readingStats by viewModel.readingStats.collectAsState()
    var showChapterRulesDialog by remember { mutableStateOf(false) }
    var showAdRulesDialog by remember { mutableStateOf(false) }
    var showCleanLevelDialog by remember { mutableStateOf(false) }
    var showCleanTogglesDialog by remember { mutableStateOf(false) }
    var showLicensesDialog by remember { mutableStateOf(false) }
    val apiKeyConfigured by viewModel.apiKeyConfigured.collectAsState()
    val aiTestState by viewModel.aiTestState.collectAsState()
    var showAiPresetDialog by remember { mutableStateOf(false) }
    var aiTextFieldDialog by remember { mutableStateOf<AiTextFieldDialog?>(null) }
    var showAiApiKeyDialog by remember { mutableStateOf(false) }
    var showAiHistoryDialog by remember { mutableStateOf(false) }
    var showBuiltinPromptsDialog by remember { mutableStateOf(false) }
    var showGlossaryDialog by remember { mutableStateOf(false) }
    var showTargetLangDialog by remember { mutableStateOf(false) }
    var showDictionaryDialog by remember { mutableStateOf(false) }
    var showVocabularyDialog by remember { mutableStateOf(false) }
    val dictionaries by viewModel.dictionaries.collectAsState()
    var showAiClearKeyConfirm by remember { mutableStateOf(false) }
    var showAiClearDataConfirm by remember { mutableStateOf(false) }
    var showModelManagerDialog by remember { mutableStateOf(false) }
    var importResult by remember { mutableStateOf<BackupManager.ImportResult?>(null) }
    val webDavPasswordConfigured by viewModel.webDavPasswordConfigured.collectAsState()
    val webDavTestState by viewModel.webDavTestState.collectAsState()
    val webDavBackups by viewModel.webDavBackups.collectAsState()
    val webDavUploading by viewModel.webDavUploading.collectAsState()
    val autoBackupRunning by viewModel.autoBackupRunning.collectAsState()
    var showWebDavListDialog by remember { mutableStateOf(false) }
    /** 远端备份的待恢复确认：预览 + 已下载的备份原文（确认后不再二次下载）。 */
    var webDavPendingRestore by remember {
        mutableStateOf<Pair<BackupManager.BackupPreview, String>?>(null)
    }
    var webDavPreviewLoading by remember { mutableStateOf<String?>(null) }
    var showWebDavUrlDialog by remember { mutableStateOf(false) }
    var showWebDavUsernameDialog by remember { mutableStateOf(false) }
    var showWebDavPasswordDialog by remember { mutableStateOf(false) }
    var showWebDavClearPasswordConfirm by remember { mutableStateOf(false) }
    /** M25：首个 WebDAV 网络动作前的一次性明示确认；非 null = 等待确认，确认后执行。 */
    var pendingWebDavAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    /** M25：任何 WebDAV 网络动作的统一闸门——首次先弹一次性明示确认。 */
    fun webDavAction(action: () -> Unit) {
        if (prefs.webdavConfirmed) action() else pendingWebDavAction = action
    }
    var logEnabled by remember { mutableStateOf(DiagnosticLog.isEnabled) }
    val clipboard = LocalClipboardManager.current

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

    // M21：OCR 模型 SAF 导入。按文件名匹配清单条目，结果以 Toast 明确反馈（R7）。
    val modelPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            val name = UriChannels.displayName(context, uri)
            viewModel.importModel(uri, name) { result ->
                val message = when (result) {
                    is com.llzx373.foldreader.core.ai.android.ModelManager.ImportResult.Success ->
                        "模型已导入：${result.spec.fileName}"
                    is com.llzx373.foldreader.core.ai.android.ModelManager.ImportResult.CustomSuccess ->
                        "自定义模型已导入：${result.spec.purpose}"
                    is com.llzx373.foldreader.core.ai.android.ModelManager.ImportResult.HashMismatch ->
                        "校验失败：${result.spec.fileName} 与官方 SHA-256 不符（下错版本或文件损坏）"
                    com.llzx373.foldreader.core.ai.android.ModelManager.ImportResult.UnknownFile ->
                        "不是清单内的模型文件（文件名需与下载地址给出的保持一致）"
                    com.llzx373.foldreader.core.ai.android.ModelManager.ImportResult.IoError ->
                        "读取文件失败，请重试"
                }
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    // M24：自定义模型导入——任意 .onnx 进指定槽位，不校验清单。启动前先记住目标槽位。
    var pendingCustomSpec by remember {
        mutableStateOf<com.llzx373.foldreader.core.ocr.OcrModelSpec?>(null)
    }
    val customModelPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val spec = pendingCustomSpec
        pendingCustomSpec = null
        if (uri != null && spec != null) {
            viewModel.importCustomModel(spec, uri) { result ->
                val message = when (result) {
                    is com.llzx373.foldreader.core.ai.android.ModelManager.ImportResult.CustomSuccess ->
                        "自定义模型已导入并优先生效：${result.spec.purpose}"
                    is com.llzx373.foldreader.core.ai.android.ModelManager.ImportResult.IoError ->
                        "读取文件失败，请重试"
                    else -> "导入失败"
                }
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
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

    // M25：自动备份目录（SAF 树）；授权持久化，否则重启后写不进去
    val autoBackupDirPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            viewModel.updateAutoBackupDirUri(uri.toString())
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
            SwitchSetting("宽屏双页（无铰链设备，仅横屏生效）", prefs.wideScreenDualPage, viewModel::updateWideScreenDualPage)
            SwitchSetting("规避摄像头位置（双页自动检测）", prefs.avoidCameraCutout, viewModel::updateAvoidCameraCutout)
            SegmentedSetting(
                label = "翻页方式",
                options = listOf("覆盖", "仿真", "无动画", "上下滚动"),
                selectedIndex = when (prefs.pageTurnMode) {
                    PageTurnMode.COVER -> 0
                    PageTurnMode.SIMULATION -> 1
                    PageTurnMode.NONE -> 2
                    PageTurnMode.SCROLL -> 3
                },
                onSelect = { index ->
                    viewModel.updatePageTurnMode(
                        when (index) {
                            1 -> PageTurnMode.SIMULATION
                            2 -> PageTurnMode.NONE
                            3 -> PageTurnMode.SCROLL
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
            SegmentedSetting(
                label = "中间点击",
                options = listOf("菜单", "上页", "下页", "书签", "无"),
                selectedIndex = when (prefs.middleTapAction) {
                    TapAction.PREVIOUS_PAGE -> 1
                    TapAction.NEXT_PAGE -> 2
                    TapAction.TOGGLE_BOOKMARK -> 3
                    TapAction.NONE -> 4
                    else -> 0
                },
                onSelect = { index ->
                    viewModel.updateMiddleTapAction(
                        when (index) {
                            1 -> TapAction.PREVIOUS_PAGE
                            2 -> TapAction.NEXT_PAGE
                            3 -> TapAction.TOGGLE_BOOKMARK
                            4 -> TapAction.NONE
                            else -> TapAction.TOGGLE_MENU
                        },
                    )
                },
            )
            SegmentedSetting(
                label = "中间双击",
                options = listOf("无", "缩放", "菜单", "书签"),
                selectedIndex = when (prefs.middleDoubleTapAction) {
                    TapAction.TOGGLE_ZOOM -> 1
                    TapAction.TOGGLE_MENU -> 2
                    TapAction.TOGGLE_BOOKMARK -> 3
                    else -> 0
                },
                onSelect = { index ->
                    viewModel.updateMiddleDoubleTapAction(
                        when (index) {
                            1 -> TapAction.TOGGLE_ZOOM
                            2 -> TapAction.TOGGLE_MENU
                            3 -> TapAction.TOGGLE_BOOKMARK
                            else -> TapAction.NONE
                        },
                    )
                },
            )
            SwitchSetting("音量键翻页", prefs.volumeKeyPagingEnabled, viewModel::updateVolumeKeyPaging)
            SwitchSetting("左侧滑动调亮度", prefs.brightnessGestureEnabled, viewModel::updateBrightnessGesture)
            SwitchSetting("滑动翻页手势", prefs.swipeGestureEnabled, viewModel::updateSwipeGesture)
            SliderSetting(
                label = "横滑判定距离",
                value = prefs.swipeDistanceDp,
                valueRange = 20f..80f,
                steps = 5,
                format = { "%.0f dp".format(it) },
                onChange = viewModel::updateSwipeDistance,
            )
            SliderSetting(
                label = "横滑判定速度",
                value = prefs.swipeFlingVelocityDpPerSec,
                valueRange = 200f..1200f,
                steps = 9,
                format = { "%.0f dp/s".format(it) },
                onChange = viewModel::updateSwipeFlingVelocity,
            )
            SwitchSetting("屏幕常亮", prefs.keepScreenOn, viewModel::updateKeepScreenOn)

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            SectionHeader("漫画")
            SegmentedSetting(
                label = "阅读方向",
                options = listOf("左→右", "右→左（日漫）"),
                selectedIndex = if (prefs.comicDirection == ComicDirection.RTL) 1 else 0,
                onSelect = { index ->
                    viewModel.updateComicDirection(
                        if (index == 1) ComicDirection.RTL else ComicDirection.LTR,
                    )
                },
            )
            SegmentedSetting(
                label = "适应模式",
                options = listOf("整页", "宽度", "高度", "原图"),
                selectedIndex = when (prefs.comicFitMode) {
                    ComicFitMode.FIT_PAGE -> 0
                    ComicFitMode.FIT_WIDTH -> 1
                    ComicFitMode.FIT_HEIGHT -> 2
                    ComicFitMode.ORIGINAL -> 3
                },
                onSelect = { index ->
                    viewModel.updateComicFitMode(
                        when (index) {
                            1 -> ComicFitMode.FIT_WIDTH
                            2 -> ComicFitMode.FIT_HEIGHT
                            3 -> ComicFitMode.ORIGINAL
                            else -> ComicFitMode.FIT_PAGE
                        },
                    )
                },
            )
            SwitchSetting(
                "封面单独成页（双页从第 2 页开始配对）",
                prefs.comicDualPageCoverAlone,
                viewModel::updateComicCoverAlone,
            )
            SwitchSetting(
                "跨页大图独占整宽",
                prefs.comicSpreadAutoDetect,
                viewModel::updateComicSpreadAutoDetect,
            )
            SegmentedSetting(
                label = "纵向滚动页间距",
                options = listOf("无缝", "小", "大"),
                selectedIndex = when {
                    prefs.comicScrollGapDp <= 0 -> 0
                    prefs.comicScrollGapDp <= 8 -> 1
                    else -> 2
                },
                onSelect = { index ->
                    viewModel.updateComicScrollGap(
                        when (index) {
                            1 -> 8
                            2 -> 24
                            else -> 0
                        },
                    )
                },
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            SectionHeader("页眉页脚")
            SwitchSetting("章节名", prefs.showChapterTitle, viewModel::updateShowChapterTitle)
            SwitchSetting("阅读进度", prefs.showPageProgress, viewModel::updateShowPageProgress)
            SwitchSetting("页码", prefs.showPageNumber, viewModel::updateShowPageNumber)
            SwitchSetting("电量", prefs.showBattery, viewModel::updateShowBattery)
            SwitchSetting("时间", prefs.showTime, viewModel::updateShowTime)

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            SectionHeader("听书朗读")
            SliderSetting(
                label = "语速",
                value = prefs.ttsSpeechRate,
                valueRange = TtsSpeech.MIN_RATE..TtsSpeech.MAX_RATE,
                steps = 14,
                format = { TtsSpeech.formatRate(it) },
                onChange = viewModel::updateTtsSpeechRate,
            )
            SliderSetting(
                label = "音调",
                value = prefs.ttsPitch,
                valueRange = TtsSpeech.MIN_PITCH..TtsSpeech.MAX_PITCH,
                steps = 14,
                format = { TtsSpeech.formatPitch(it) },
                onChange = viewModel::updateTtsPitch,
            )

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
                headlineContent = { Text("清理档位") },
                supportingContent = { Text(cleanLevelSummary(prefs)) },
                modifier = Modifier.clickable { showCleanLevelDialog = true },
            )
            ListItem(
                headlineContent = { Text("清理规则明细") },
                supportingContent = {
                    Text(
                        "已启用 ${enabledCleanToggleCount(prefs)} / ${CleanToggles.ENTRIES.size} 项" +
                            "（逐项调整后档位变为「自定义」）",
                    )
                },
                modifier = Modifier.clickable { showCleanTogglesDialog = true },
            )
            ListItem(
                headlineContent = { Text("去广告行规则") },
                supportingContent = {
                    Text(
                        if (prefs.adCleanRules.isEmpty()) {
                            "未配置规则；行内切除见「清理规则明细」"
                        } else {
                            "已配置 ${prefs.adCleanRules.size} 条正则，整行匹配即删除"
                        },
                    )
                },
                modifier = Modifier.clickable { showAdRulesDialog = true },
            )
            ListItem(
                headlineContent = { Text("关于智能清理") },
                supportingContent = {
                    Text(
                        "导入书籍时按档位清洗，结果保存为副本、原文件不受影响；" +
                            "已导入的书可在书籍详情里「智能整理」重新清洗。" +
                            "清理只在本地离线执行，不上传任何内容。",
                    )
                },
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            SectionHeader("AI 服务")
            SwitchSetting("启用 AI 服务", prefs.aiEnabled, viewModel::updateAiEnabled)
            if (prefs.aiEnabled) {
                SegmentedSetting(
                    label = "协议",
                    options = listOf("OpenAI Chat", "OpenAI Responses", "Anthropic"),
                    selectedIndex = when (prefs.aiProtocol) {
                        AiProtocol.OPENAI_CHAT -> 0
                        AiProtocol.OPENAI_RESPONSES -> 1
                        AiProtocol.ANTHROPIC -> 2
                    },
                    onSelect = { index ->
                        viewModel.updateAiProtocol(
                            when (index) {
                                1 -> AiProtocol.OPENAI_RESPONSES
                                2 -> AiProtocol.ANTHROPIC
                                else -> AiProtocol.OPENAI_CHAT
                            },
                        )
                    },
                )
                ListItem(
                    headlineContent = { Text("服务商预设") },
                    supportingContent = { Text("DeepSeek / OpenAI / Anthropic / 通义千问 / 自定义") },
                    modifier = Modifier.clickable { showAiPresetDialog = true },
                )
                ListItem(
                    headlineContent = { Text("服务商地址") },
                    supportingContent = { Text(prefs.aiBaseUrl.ifBlank { "未配置" }) },
                    modifier = Modifier.clickable { aiTextFieldDialog = AiTextFieldDialog.BASE_URL },
                )
                ListItem(
                    headlineContent = { Text("通用模型") },
                    supportingContent = { Text(prefs.aiModelGeneral.ifBlank { "未配置" }) },
                    modifier = Modifier.clickable { aiTextFieldDialog = AiTextFieldDialog.MODEL_GENERAL },
                )
                ListItem(
                    headlineContent = { Text("翻译模型") },
                    supportingContent = {
                        Text(prefs.aiModelTranslation.ifBlank { "未配置（回落通用模型）" })
                    },
                    modifier = Modifier.clickable { aiTextFieldDialog = AiTextFieldDialog.MODEL_TRANSLATION },
                )
                ListItem(
                    headlineContent = { Text("视觉模型") },
                    supportingContent = {
                        Text(prefs.aiModelVision.ifBlank { "未配置（回落通用模型）" })
                    },
                    modifier = Modifier.clickable { aiTextFieldDialog = AiTextFieldDialog.MODEL_VISION },
                )
                ListItem(
                    headlineContent = { Text("API 密钥") },
                    supportingContent = {
                        Text(if (apiKeyConfigured) "已配置（本地加密存储）" else "未配置")
                    },
                    modifier = Modifier.clickable { showAiApiKeyDialog = true },
                )
                ListItem(
                    headlineContent = { Text("测试连接") },
                    supportingContent = { Text(aiTestSummary(aiTestState)) },
                    modifier = Modifier.clickable { viewModel.testConnection() },
                )
                // M30：目标语言扩到 8 种，分段按钮放不下，改为弹窗单选
                ListItem(
                    headlineContent = { Text("默认目标语言") },
                    supportingContent = {
                        Text(SelectionTranslatePrompt.displayName(prefs.aiTargetLang))
                    },
                    modifier = Modifier.clickable { showTargetLangDialog = true },
                )
                ListItem(
                    headlineContent = { Text("外发历史") },
                    supportingContent = { Text("每次向模型发送内容前的本地台账，可随时审查") },
                    modifier = Modifier.clickable { showAiHistoryDialog = true },
                )
                ListItem(
                    headlineContent = { Text("内置提示词") },
                    supportingContent = { Text("各 AI 功能发往模型的提示词全文，只读可核对") },
                    modifier = Modifier.clickable { showBuiltinPromptsDialog = true },
                )
                ListItem(
                    headlineContent = { Text("术语表") },
                    supportingContent = { Text("确认自动生成的专名候选，维护全局与单书的约定译法") },
                    modifier = Modifier.clickable { showGlossaryDialog = true },
                )
                ListItem(
                    headlineContent = { Text("清除凭据") },
                    supportingContent = { Text("删除已保存的 API 密钥") },
                    modifier = Modifier.clickable { showAiClearKeyConfirm = true },
                )
                ListItem(
                    headlineContent = { Text("清除全部 AI 数据") },
                    supportingContent = { Text("删除所有书的译本、翻译台账与术语表；凭据与外发历史保留") },
                    modifier = Modifier.clickable { showAiClearDataConfirm = true },
                )
                ListItem(
                    headlineContent = { Text("关于 AI") },
                    supportingContent = {
                        Text(
                            "正文/采样会发往你在上方自配的服务商，请自行评估其隐私政策；" +
                                "API 密钥仅在本机加密存储，不进备份、不写日志；" +
                                "未配置密钥时不会产生任何网络请求。",
                        )
                    },
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            SectionHeader("词典（离线查词）")
            ListItem(
                headlineContent = { Text("词典管理") },
                supportingContent = {
                    Text(
                        if (dictionaries.isEmpty()) {
                            "导入 StarDict 词典后，阅读器长按选中可查词（离线优先，未命中可回落 AI 解释）"
                        } else {
                            "已导入 ${dictionaries.size} 部：" +
                                dictionaries.joinToString("、") { it.bookName }
                        },
                    )
                },
                modifier = Modifier.clickable { showDictionaryDialog = true },
            )
            ListItem(
                headlineContent = { Text("生词本") },
                supportingContent = { Text("查词卡片「收藏」的词条（释义 / 例句 / 来源书），按书或按时间浏览") },
                modifier = Modifier.clickable { showVocabularyDialog = true },
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            SectionHeader("OCR 模型（离线识别）")
            ListItem(
                headlineContent = { Text("模型管理") },
                supportingContent = {
                    // 未钉官方版的槽位（inpaint）不计入就绪计数——它不是必需模型
                    val pinned = modelStatus.filter { it.spec.sha256 != null }
                    val ready = pinned.count { it.ready }
                    Text(
                        if (com.llzx373.foldreader.BuildConfig.BUNDLED_MODELS) {
                            "扫描 PDF 文本层与漫画翻译共用的离线模型（本版本已自带，可导入自定义模型覆盖）；已就绪 $ready/${pinned.size}"
                        } else {
                            "扫描 PDF 文本层与漫画翻译共用的离线模型（自行下载导入、校验 SHA-256，也可导入自定义模型）；已导入 $ready/${pinned.size}"
                        },
                    )
                },
                modifier = Modifier.clickable { showModelManagerDialog = true },
            )
            SegmentedSetting(
                label = "OCR 识别语言",
                options = listOf("自动", "中文", "English", "日本語"),
                selectedIndex = when (prefs.ocrRecLang) {
                    "rec_ch" -> 1
                    "rec_en" -> 2
                    "rec_ja" -> 3
                    else -> 0
                },
                onSelect = { index ->
                    viewModel.updateOcrRecLang(
                        when (index) {
                            1 -> "rec_ch"
                            2 -> "rec_en"
                            3 -> "rec_ja"
                            else -> ""
                        },
                    )
                },
            )
            ListItem(
                headlineContent = { Text("关于 OCR") },
                supportingContent = {
                    Text(
                        "识别完全在本机离线运行，不产生任何网络请求；" +
                            "扫描版 PDF 在模型就绪后可全文搜索、可拖框选字。",
                    )
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
            if (prefs.bookshelfGridView) {
                SegmentedSetting(
                    label = "书架每行本数",
                    options = listOf("自动", "2", "3", "4", "5"),
                    selectedIndex = if (prefs.bookshelfGridColumns in 2..5) {
                        prefs.bookshelfGridColumns - 1
                    } else {
                        0
                    },
                    onSelect = { index ->
                        viewModel.updateBookshelfGridColumns(if (index == 0) 0 else index + 1)
                    },
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            ReadingStatsSection(stats = readingStats)
            // M34：每日阅读目标（分钟）；打卡数据复用阅读统计日桶，零新采集
            SegmentedSetting(
                label = "每日阅读目标",
                options = listOf("关闭", "10 分钟", "20 分钟", "30 分钟", "60 分钟"),
                selectedIndex = when (prefs.dailyReadingGoalMinutes) {
                    10 -> 1
                    20 -> 2
                    30 -> 3
                    60 -> 4
                    else -> 0
                },
                onSelect = { index ->
                    viewModel.updateDailyReadingGoalMinutes(
                        when (index) {
                            1 -> 10
                            2 -> 20
                            3 -> 30
                            4 -> 60
                            else -> 0
                        },
                    )
                },
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            SectionHeader("隐私")
            // M34 隐私锁：开关两个方向都要过一次系统验证（防止别人拿到亮屏的 App 直接关掉）；
            // 设备没有任何验证手段（无锁屏/生物识别）时不允许开启
            SwitchSetting("应用锁（启动时需生物识别/锁屏密码）", prefs.appLockEnabled) { enabled ->
                val activity = context as? FragmentActivity
                when {
                    activity == null -> Unit
                    enabled && !AppLock.canAuthenticate(activity) ->
                        Toast.makeText(
                            context,
                            "设备未设置锁屏密码或生物识别，无法开启应用锁",
                            Toast.LENGTH_LONG,
                        ).show()
                    else -> AppLock.prompt(
                        activity,
                        onSuccess = {
                            viewModel.updateAppLockEnabled(enabled)
                            if (enabled) AppLock.unlocked = true
                        },
                        onFailed = { message ->
                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                        },
                    )
                }
            }
            ListItem(
                headlineContent = { Text("隐藏书籍") },
                supportingContent = {
                    Text("书籍详情里可「隐藏本书」；书架右上角菜单「显示隐藏的书籍」可临时找回（应用锁开启时需先验证）")
                },
            )

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
            SectionHeader("自动备份")
            SwitchSetting("每日自动备份", prefs.autoBackupEnabled, viewModel::updateAutoBackupEnabled)
            if (prefs.autoBackupEnabled) {
                ListItem(
                    headlineContent = { Text("备份目录") },
                    supportingContent = {
                        Text(
                            prefs.autoBackupDirUri
                                .takeIf { it.isNotBlank() }
                                ?.let { android.net.Uri.decode(android.net.Uri.parse(it).lastPathSegment) }
                                ?: "未选择（选一个目录，备份 JSON 会导出到这里）",
                        )
                    },
                    modifier = Modifier.clickable { autoBackupDirPicker.launch(null) },
                )
                SegmentedSetting(
                    label = "保留份数",
                    options = listOf("3", "5", "10", "20"),
                    selectedIndex = when (prefs.autoBackupKeepCount) {
                        3 -> 0
                        10 -> 2
                        20 -> 3
                        else -> 1
                    },
                    onSelect = { index ->
                        viewModel.updateAutoBackupKeepCount(
                            when (index) {
                                0 -> 3
                                2 -> 10
                                3 -> 20
                                else -> 5
                            },
                        )
                    },
                )
                ListItem(
                    headlineContent = { Text("立即备份一次") },
                    supportingContent = {
                        Text(
                            when {
                                autoBackupRunning -> "备份中…"
                                prefs.autoBackupLastRunAt > 0 ->
                                    "上次成功：" + SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                                        .format(Date(prefs.autoBackupLastRunAt))
                                else -> "还未成功备份过"
                            },
                        )
                    },
                    modifier = Modifier.clickable(enabled = !autoBackupRunning) {
                        viewModel.runAutoBackupNow { name, error ->
                            val message = when {
                                error != null -> "备份失败：$error"
                                else -> "已备份：$name"
                            }
                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                        }
                    },
                )
                ListItem(
                    headlineContent = { Text("关于自动备份") },
                    supportingContent = {
                        Text(
                            "每天首次打开应用时检查一次，距上次成功超过 24 小时即导出到所选目录，" +
                                "只保留最近 N 份（只动本应用命名的备份文件）。" +
                                "全程本地写入，不产生任何网络请求。",
                        )
                    },
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            SectionHeader("WebDAV 备份")
            ListItem(
                headlineContent = { Text("服务器地址") },
                supportingContent = {
                    Text(prefs.webdavBaseUrl.ifBlank { "未配置（备份目录的完整地址，如 https://dav.jianguoyun.com/dav/FoldReader/）" })
                },
                modifier = Modifier.clickable { showWebDavUrlDialog = true },
            )
            ListItem(
                headlineContent = { Text("账号") },
                supportingContent = { Text(prefs.webdavUsername.ifBlank { "未配置" }) },
                modifier = Modifier.clickable { showWebDavUsernameDialog = true },
            )
            ListItem(
                headlineContent = { Text("密码") },
                supportingContent = {
                    Text(if (webDavPasswordConfigured) "已配置（本地加密存储）" else "未配置")
                },
                modifier = Modifier.clickable { showWebDavPasswordDialog = true },
            )
            ListItem(
                headlineContent = { Text("测试连接") },
                supportingContent = { Text(webDavTestSummary(webDavTestState)) },
                modifier = Modifier.clickable {
                    webDavAction { viewModel.testWebDavConnection() }
                },
            )
            ListItem(
                headlineContent = { Text("上传备份") },
                supportingContent = {
                    Text(
                        if (webDavUploading) {
                            "上传中…"
                        } else {
                            "导出 JSON 并上传到服务器（按时间戳命名，同名自动区分）"
                        },
                    )
                },
                modifier = Modifier.clickable(enabled = !webDavUploading) {
                    webDavAction {
                        viewModel.uploadBackupToWebDav { name, error ->
                            val message = when {
                                error != null -> "上传失败：$error"
                                else -> "已上传：$name"
                            }
                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                        }
                    }
                },
            )
            ListItem(
                headlineContent = { Text("远端备份列表") },
                supportingContent = { Text("查看服务器上的备份，恢复前可预览版本与内容规模") },
                modifier = Modifier.clickable {
                    webDavAction {
                        showWebDavListDialog = true
                        viewModel.refreshWebDavBackups()
                    }
                },
            )
            if (webDavPasswordConfigured) {
                ListItem(
                    headlineContent = { Text("清除密码") },
                    supportingContent = { Text("删除已保存的 WebDAV 密码；地址与账号保留") },
                    modifier = Modifier.clickable { showWebDavClearPasswordConfirm = true },
                )
            }
            ListItem(
                headlineContent = { Text("关于 WebDAV 备份") },
                supportingContent = {
                    Text(
                        "备份只发往你在上方自配的服务器，请自行评估其隐私政策；" +
                            "密码仅在本机加密存储，不进备份、不写日志；" +
                            "未配置时不会产生任何网络请求；每次传输记入「外发历史」。",
                    )
                },
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            SectionHeader("诊断（返回跳动 / 折叠适配）")
            SwitchSetting("记录诊断日志", logEnabled) { enabled ->
                logEnabled = enabled
                DiagnosticLog.setEnabled(context, enabled)
            }
            ListItem(
                headlineContent = { Text("在日志中打标记") },
                supportingContent = { Text("复现「跳一下」的前后各点一次，日志里会出现醒目分隔线，便于圈定区间") },
                modifier = Modifier.clickable {
                    DiagnosticLog.mark("手动标记")
                    Toast.makeText(context, "已打标记", Toast.LENGTH_SHORT).show()
                },
            )
            ListItem(
                headlineContent = { Text("分享日志文件") },
                supportingContent = {
                    Text("导出到 cache 后走系统分享（微信/邮件/网盘均可）；失败会自动复制到剪贴板")
                },
                modifier = Modifier.clickable { shareDiagnosticLog(context) },
            )
            ListItem(
                headlineContent = { Text("复制日志到剪贴板") },
                supportingContent = { Text("直接粘贴到聊天窗口发我；内容含设备/屏幕/折叠形态与逐帧布局") },
                modifier = Modifier.clickable {
                    val text = DiagnosticLog.snapshot(context)
                    clipboard.setText(AnnotatedString(text))
                    Toast.makeText(context, "日志已复制（${text.length} 字符）", Toast.LENGTH_SHORT).show()
                },
            )
            ListItem(
                headlineContent = { Text("清空日志") },
                supportingContent = { Text("删掉已落盘的日志与崩溃转储") },
                modifier = Modifier.clickable {
                    DiagnosticLog.clear()
                    Toast.makeText(context, "日志已清空", Toast.LENGTH_SHORT).show()
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
    if (showCleanLevelDialog) {
        CleanLevelDialog(
            current = prefs.cleanLevel,
            onSelect = {
                viewModel.updateCleanLevel(it)
                showCleanLevelDialog = false
            },
            onDismiss = { showCleanLevelDialog = false },
        )
    }
    if (showCleanTogglesDialog) {
        CleanTogglesDialog(
            toggles = prefs.cleanToggles,
            onToggle = viewModel::updateCleanToggle,
            onDismiss = { showCleanTogglesDialog = false },
        )
    }
    if (showAiPresetDialog) {
        AiPresetDialog(
            onSelect = { url ->
                if (url != null) viewModel.updateAiBaseUrl(url)
                showAiPresetDialog = false
            },
            onDismiss = { showAiPresetDialog = false },
        )
    }
    aiTextFieldDialog?.let { dialog ->
        AiTextInputDialog(
            title = aiTextFieldTitle(dialog),
            current = aiTextFieldValue(dialog, prefs),
            onSave = { value ->
                when (dialog) {
                    AiTextFieldDialog.BASE_URL -> viewModel.updateAiBaseUrl(value)
                    AiTextFieldDialog.MODEL_GENERAL -> viewModel.updateAiModelGeneral(value)
                    AiTextFieldDialog.MODEL_TRANSLATION -> viewModel.updateAiModelTranslation(value)
                    AiTextFieldDialog.MODEL_VISION -> viewModel.updateAiModelVision(value)
                }
                aiTextFieldDialog = null
            },
            onDismiss = { aiTextFieldDialog = null },
        )
    }
    if (showAiApiKeyDialog) {
        AiApiKeyDialog(
            onSave = {
                viewModel.saveApiKey(it)
                showAiApiKeyDialog = false
            },
            onDismiss = { showAiApiKeyDialog = false },
        )
    }
    if (showAiHistoryDialog) {
        AiOutboundHistoryDialog(
            records = viewModel.outboundHistory().asReversed(),
            onDismiss = { showAiHistoryDialog = false },
        )
    }
    if (showBuiltinPromptsDialog) {
        BuiltinPromptsDialog(onDismiss = { showBuiltinPromptsDialog = false })
    }
    if (showGlossaryDialog) {
        GlossaryDialog(onDismiss = { showGlossaryDialog = false })
    }
    if (showTargetLangDialog) {
        AlertDialog(
            onDismissRequest = { showTargetLangDialog = false },
            title = { Text("默认目标语言") },
            text = {
                Column {
                    AiTargetLang.entries.forEach { option ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.updateAiTargetLang(option)
                                    showTargetLangDialog = false
                                }
                                .padding(vertical = 6.dp),
                        ) {
                            RadioButton(
                                selected = prefs.aiTargetLang == option,
                                onClick = null,
                            )
                            Text(
                                text = SelectionTranslatePrompt.displayName(option),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showTargetLangDialog = false }) { Text("关闭") }
            },
        )
    }
    if (showDictionaryDialog) {
        DictionaryDialog(
            viewModel = viewModel,
            onDismiss = { showDictionaryDialog = false },
        )
    }
    if (showVocabularyDialog) {
        VocabularyDialog(
            viewModel = viewModel,
            onDismiss = { showVocabularyDialog = false },
        )
    }
    if (showModelManagerDialog) {
        ModelManagerDialog(
            slots = modelStatus,
            onImport = { modelPicker.launch(arrayOf("*/*")) },
            onImportCustom = { spec ->
                pendingCustomSpec = spec
                customModelPicker.launch(arrayOf("*/*"))
            },
            onDelete = { spec ->
                viewModel.deleteModel(spec.id)
                Toast.makeText(context, "已删除：${spec.fileName}", Toast.LENGTH_SHORT).show()
            },
            onDeleteCustom = { spec ->
                viewModel.deleteCustomModel(spec.id)
                Toast.makeText(context, "已删除自定义模型：${spec.purpose}", Toast.LENGTH_SHORT).show()
            },
            onCopyUrl = { url ->
                clipboard.setText(AnnotatedString(url))
                Toast.makeText(context, "下载地址已复制", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { showModelManagerDialog = false },
        )
    }
    if (showAiClearKeyConfirm) {
        AlertDialog(
            onDismissRequest = { showAiClearKeyConfirm = false },
            title = { Text("清除凭据") },
            text = { Text("将删除本机保存的 API 密钥，AI 功能随即不可用。配置的服务商地址与模型保留。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearApiKey()
                    showAiClearKeyConfirm = false
                }) { Text("清除") }
            },
            dismissButton = {
                TextButton(onClick = { showAiClearKeyConfirm = false }) { Text("取消") }
            },
        )
    }
    if (showAiClearDataConfirm) {
        AlertDialog(
            onDismissRequest = { showAiClearDataConfirm = false },
            title = { Text("清除全部 AI 数据") },
            text = {
                Text(
                    "将删除所有书的译本文件、翻译台账与术语表（已译章节需重新翻译）。" +
                        "API 密钥、服务商配置与外发历史保留。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearAiData()
                    showAiClearDataConfirm = false
                }) { Text("清除") }
            },
            dismissButton = {
                TextButton(onClick = { showAiClearDataConfirm = false }) { Text("取消") }
            },
        )
    }

    if (showLicensesDialog) {
        OpenSourceLicensesDialog(onDismiss = { showLicensesDialog = false })
    }

    if (showWebDavUrlDialog) {
        AiTextInputDialog(
            title = "WebDAV 服务器地址",
            current = prefs.webdavBaseUrl,
            onSave = {
                viewModel.updateWebDavBaseUrl(it)
                showWebDavUrlDialog = false
            },
            onDismiss = { showWebDavUrlDialog = false },
        )
    }
    if (showWebDavUsernameDialog) {
        AiTextInputDialog(
            title = "WebDAV 账号",
            current = prefs.webdavUsername,
            onSave = {
                viewModel.updateWebDavUsername(it)
                showWebDavUsernameDialog = false
            },
            onDismiss = { showWebDavUsernameDialog = false },
        )
    }
    if (showWebDavPasswordDialog) {
        WebDavPasswordDialog(
            onSave = {
                viewModel.saveWebDavPassword(it)
                showWebDavPasswordDialog = false
            },
            onDismiss = { showWebDavPasswordDialog = false },
        )
    }
    if (showWebDavClearPasswordConfirm) {
        AlertDialog(
            onDismissRequest = { showWebDavClearPasswordConfirm = false },
            title = { Text("清除密码") },
            text = { Text("将删除本机保存的 WebDAV 密码，备份上传/恢复随即不可用。服务器地址与账号保留。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearWebDavPassword()
                    showWebDavClearPasswordConfirm = false
                }) { Text("清除") }
            },
            dismissButton = {
                TextButton(onClick = { showWebDavClearPasswordConfirm = false }) { Text("取消") }
            },
        )
    }
    pendingWebDavAction?.let { action ->
        AlertDialog(
            onDismissRequest = { pendingWebDavAction = null },
            title = { Text("WebDAV 备份") },
            text = {
                Text(
                    "备份将上传到你配置的服务器：\n${prefs.webdavBaseUrl.ifBlank { "（未配置）"}}\n\n" +
                        "传输内容为备份 JSON（书架数据与阅读偏好，不含书籍文件本体）。" +
                        "服务器由你自托管/自行选择，请自行评估其隐私政策；每次传输会记入「外发历史」。" +
                        "此确认只提示一次。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.confirmWebDav()
                    pendingWebDavAction = null
                    action()
                }) { Text("同意并继续") }
            },
            dismissButton = {
                TextButton(onClick = { pendingWebDavAction = null }) { Text("取消") }
            },
        )
    }

    if (showWebDavListDialog) {
        WebDavRemoteListDialog(
            state = webDavBackups,
            loadingName = webDavPreviewLoading,
            onRefresh = { viewModel.refreshWebDavBackups() },
            onRestore = { name ->
                webDavPreviewLoading = name
                viewModel.previewWebDavBackup(name) { preview, text, error ->
                    webDavPreviewLoading = null
                    if (preview != null && text != null) {
                        webDavPendingRestore = preview to text
                    } else {
                        Toast.makeText(context, "下载失败：${error ?: "未知错误"}", Toast.LENGTH_LONG).show()
                    }
                }
            },
            onDismiss = { showWebDavListDialog = false },
        )
    }
    webDavPendingRestore?.let { (preview, backupText) ->
        val exportedAt = remember(preview.exportedAt) {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(preview.exportedAt))
        }
        AlertDialog(
            onDismissRequest = { webDavPendingRestore = null },
            title = { Text("恢复备份") },
            text = {
                Column {
                    Text("版本 v${preview.version} · ${preview.bookCount} 本书 · 导出于 $exportedAt")
                    Text(
                        "恢复按书籍内容哈希匹配既有书籍：匹配上的恢复进度/书签/标注/偏好，" +
                            "未匹配的会列入「文件缺失」清单。",
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    webDavPendingRestore = null
                    showWebDavListDialog = false
                    viewModel.restoreWebDavBackup(backupText) { result, error ->
                        if (error != null || result == null) {
                            Toast.makeText(
                                context,
                                "恢复失败：${error ?: "未知错误"}",
                                Toast.LENGTH_LONG,
                            ).show()
                        } else {
                            importResult = result
                        }
                    }
                }) { Text("恢复") }
            },
            dismissButton = {
                TextButton(onClick = { webDavPendingRestore = null }) { Text("取消") }
            },
        )
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

/** 导出诊断日志并走系统分享；分享不可用时退回剪贴板 */
private fun shareDiagnosticLog(context: Context) {
    val file = DiagnosticLog.export(context)
    if (file == null) {
        Toast.makeText(context, "导出失败，请改用「复制日志到剪贴板」", Toast.LENGTH_LONG).show()
        return
    }
    val uri = runCatching {
        FileProvider.getUriForFile(
            context,
            context.packageName + DiagnosticLog.FILE_PROVIDER_SUFFIX,
            file,
        )
    }.getOrNull()
    if (uri == null) {
        Toast.makeText(context, "导出失败，请改用「复制日志到剪贴板」", Toast.LENGTH_LONG).show()
        return
    }
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "FoldReader 诊断日志")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(Intent.createChooser(intent, "分享诊断日志")) }
        .onFailure {
            Toast.makeText(context, "没有可分享的应用，请改用「复制日志到剪贴板」", Toast.LENGTH_LONG).show()
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
private fun CleanLevelDialog(
    current: CleanLevel,
    onSelect: (CleanLevel) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        title = { Text("清理档位") },
        text = {
            Column {
                Text(
                    text = "档位决定启用哪些规则；也可以到「清理规则明细」里逐项调整。" +
                        "清洗只影响之后导入或「智能整理」的书。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CleanLevel.entries.forEach { level ->
                    if (level == CleanLevel.CUSTOM && current != CleanLevel.CUSTOM) return@forEach
                    RadioSetting(
                        label = cleanLevelLabel(level),
                        hint = cleanLevelHint(level),
                        selected = level == current,
                        onSelect = { onSelect(level) },
                    )
                }
            }
        },
    )
}

@Composable
private fun CleanTogglesDialog(
    toggles: CleanToggles,
    onToggle: (String, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        title = { Text("清理规则明细") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "逐项调整后档位会变成「自定义」。全部关闭且没有广告规则时不生成副本。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CleanToggles.ENTRIES.forEach { entry ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(entry.label, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = entry.hint,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = entry.get(toggles),
                            onCheckedChange = { onToggle(entry.key, it) },
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun RadioSetting(
    label: String,
    hint: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = { Text(hint) },
        trailingContent = {
            RadioButton(selected = selected, onClick = onSelect)
        },
        modifier = Modifier.clickable(onClick = onSelect),
    )
}

private fun cleanLevelLabel(level: CleanLevel): String = when (level) {
    CleanLevel.CONSERVATIVE -> "保守"
    CleanLevel.STANDARD -> "标准（默认）"
    CleanLevel.AGGRESSIVE -> "激进"
    CleanLevel.CUSTOM -> "自定义"
}

private fun cleanLevelHint(level: CleanLevel): String = when (level) {
    CleanLevel.CONSERVATIVE -> "只做无争议的字符/空白归一与广告行过滤，不改动段落结构"
    CleanLevel.STANDARD -> "在保守之上加段落重组、空行规整、章节标题修复与标点规整"
    CleanLevel.AGGRESSIVE -> "在标准之上加繁简转换，以及重复标点折叠、引号字形统一等"
    CleanLevel.CUSTOM -> "由「清理规则明细」里的开关决定"
}

private fun cleanLevelSummary(prefs: ReadingPreferences): String =
    "${cleanLevelLabel(prefs.cleanLevel)}：${cleanLevelHint(prefs.cleanLevel)}"

private fun enabledCleanToggleCount(prefs: ReadingPreferences): Int =
    CleanToggles.ENTRIES.count { it.get(prefs.cleanToggles) }

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

// ---- AI 服务 ----

/** 「服务商地址 / 三个模型」共用的文本输入对话框入口。 */
private enum class AiTextFieldDialog { BASE_URL, MODEL_GENERAL, MODEL_TRANSLATION, MODEL_VISION }

private fun aiTextFieldTitle(dialog: AiTextFieldDialog): String = when (dialog) {
    AiTextFieldDialog.BASE_URL -> "服务商地址"
    AiTextFieldDialog.MODEL_GENERAL -> "通用模型"
    AiTextFieldDialog.MODEL_TRANSLATION -> "翻译模型"
    AiTextFieldDialog.MODEL_VISION -> "视觉模型"
}

private fun aiTextFieldValue(dialog: AiTextFieldDialog, prefs: ReadingPreferences): String =
    when (dialog) {
        AiTextFieldDialog.BASE_URL -> prefs.aiBaseUrl
        AiTextFieldDialog.MODEL_GENERAL -> prefs.aiModelGeneral
        AiTextFieldDialog.MODEL_TRANSLATION -> prefs.aiModelTranslation
        AiTextFieldDialog.MODEL_VISION -> prefs.aiModelVision
    }

private fun aiTestSummary(state: SettingsViewModel.AiTestState?): String = when (state) {
    null -> "发送一条测试消息验证配置"
    SettingsViewModel.AiTestState.Running -> "测试中…"
    is SettingsViewModel.AiTestState.Success -> "成功：${state.reply}"
    is SettingsViewModel.AiTestState.Failure -> "失败：${state.message}"
}

private fun webDavTestSummary(state: SettingsViewModel.WebDavTestState?): String = when (state) {
    null -> "PROPFIND 目标目录验证配置；目录不存在时会自动创建"
    SettingsViewModel.WebDavTestState.Running -> "测试中…"
    is SettingsViewModel.WebDavTestState.Success ->
        if (state.createdDirectory) "成功（目录不存在，已自动创建）" else "连接成功"
    is SettingsViewModel.WebDavTestState.Failure -> "失败：${state.message}"
}

/** 预设只填地址，协议仍需在上方按服务商文档选择。 */
private val aiPresets = listOf(
    "DeepSeek" to "https://api.deepseek.com",
    "OpenAI" to "https://api.openai.com/v1",
    "Anthropic" to "https://api.anthropic.com",
    "通义千问" to "https://dashscope.aliyuncs.com/compatible-mode/v1",
)

@Composable
private fun AiPresetDialog(
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        title = { Text("服务商预设") },
        text = {
            Column {
                Text(
                    text = "选择预设只填写服务商地址；「自定义」保留当前地址不变。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                aiPresets.forEach { (name, url) ->
                    ListItem(
                        headlineContent = { Text(name) },
                        supportingContent = { Text(url) },
                        modifier = Modifier.clickable { onSelect(url) },
                    )
                }
                ListItem(
                    headlineContent = { Text("自定义") },
                    supportingContent = { Text("在「服务商地址」里手动填写") },
                    modifier = Modifier.clickable { onSelect(null) },
                )
            }
        },
    )
}

@Composable
private fun AiTextInputDialog(
    title: String,
    current: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                label = { Text(title) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(draft) }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** key 掩码输入；只交给 CredentialStore，不落任何状态与日志。 */
@Composable
private fun AiApiKeyDialog(
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var key by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("API 密钥") },
        text = {
            Column {
                Text(
                    text = "密钥仅在本机加密存储，不进备份、不写日志。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text("API 密钥") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(key.trim()) }, enabled = key.isNotBlank()) {
                Text("保存")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** WebDAV 密码掩码输入（M25）；只交给 WebDavCredentialStore，不落任何状态与日志。 */
@Composable
private fun WebDavPasswordDialog(
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("WebDAV 密码") },
        text = {
            Column {
                Text(
                    text = "密码仅在本机加密存储，不进备份、不写日志。坚果云等服务请使用「应用密码」而非登录密码。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("密码 / 应用密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(password) }, enabled = password.isNotEmpty()) {
                Text("保存")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 远端备份列表（M25）：点条目 = 下载 + 恢复前预览。 */
@Composable
private fun WebDavRemoteListDialog(
    state: SettingsViewModel.WebDavListState?,
    loadingName: String?,
    onRefresh: () -> Unit,
    onRestore: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        title = { Text("远端备份") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                when (state) {
                    null, SettingsViewModel.WebDavListState.Loading -> Text(
                        text = "加载中…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    is SettingsViewModel.WebDavListState.Failed -> Column {
                        Text(
                            text = "加载失败：${state.message}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        TextButton(onClick = onRefresh) { Text("重试") }
                    }
                    is SettingsViewModel.WebDavListState.Ready -> {
                        if (state.entries.isEmpty()) {
                            Text(
                                text = "服务器上还没有备份，先在上方「上传备份」",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        state.entries.forEach { entry ->
                            ListItem(
                                headlineContent = { Text(entry.name) },
                                supportingContent = {
                                    Text(
                                        listOfNotNull(
                                            formatBytes(entry.sizeBytes),
                                            entry.lastModified,
                                        ).joinToString(" · ") +
                                            if (loadingName == entry.name) " · 下载中…" else "",
                                    )
                                },
                                modifier = Modifier.clickable(enabled = loadingName == null) {
                                    onRestore(entry.name)
                                },
                            )
                        }
                    }
                }
            }
        },
    )
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

@Composable
private fun AiOutboundHistoryDialog(
    records: List<AiContentGate.OutboundRecord>,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        title = { Text("外发历史") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (records.isEmpty()) {
                    Text(
                        text = "暂无记录",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val timeFormat = remember {
                    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                }
                records.forEach { record ->
                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                        Text(
                            text = "${timeFormat.format(Date(record.timestamp))} · ${record.feature}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = if (record.estimatedTokens > 0) {
                                "${record.scope} · ≈${record.estimatedTokens} token"
                            } else {
                                // WebDAV 备份等非 AI 记录没有 token 概念，scope 里已含文件名与大小
                                record.scope
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
    )
}

/** 内置提示词只读查看：逐项分区展示功能名与提示词全文，可选择复制。 */
@Composable
private fun BuiltinPromptsDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        title = { Text("内置提示词") },
        text = {
            SelectionContainer {
                Column(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    BuiltinPrompts.all.forEach { prompt ->
                        Column(modifier = Modifier.padding(vertical = 8.dp)) {
                            Text(
                                text = prompt.feature,
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                text = prompt.template,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
        },
    )
}

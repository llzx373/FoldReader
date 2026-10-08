package com.llzx373.foldreader.feature.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.llzx373.foldreader.AppContainer
import com.llzx373.foldreader.core.ai.AiException
import com.llzx373.foldreader.core.ai.AiMessage
import com.llzx373.foldreader.core.ai.AiProvider
import com.llzx373.foldreader.core.ai.AiRole
import com.llzx373.foldreader.core.ai.AiTargetLang
import com.llzx373.foldreader.core.ai.AiProtocol
import com.llzx373.foldreader.core.ai.android.CredentialStore
import com.llzx373.foldreader.core.ai.gate.AiContentGate
import com.llzx373.foldreader.core.backup.BackupManager
import com.llzx373.foldreader.core.data.repository.BookPrefsRepository
import com.llzx373.foldreader.core.data.settings.ComicDirection
import com.llzx373.foldreader.core.data.settings.ComicFitMode
import com.llzx373.foldreader.core.data.settings.DarkThemeOption
import com.llzx373.foldreader.core.data.settings.DualPageMode
import com.llzx373.foldreader.core.data.settings.PageTurnMode
import com.llzx373.foldreader.core.data.settings.ReadingPreferences
import com.llzx373.foldreader.core.data.settings.ReadingTheme
import com.llzx373.foldreader.core.data.settings.SettingsRepository
import com.llzx373.foldreader.core.data.settings.TapAction
import com.llzx373.foldreader.core.format.clean.CleanLevel
import com.llzx373.foldreader.core.reader.FontManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val fontManager: FontManager,
    private val bookshelfRepository: com.llzx373.foldreader.core.data.repository.BookshelfRepository,
    private val backupManager: BackupManager,
    private val bookPrefsRepository: BookPrefsRepository,
    private val credentialStore: CredentialStore,
    private val aiContentGate: AiContentGate,
    private val aiProvider: suspend () -> AiProvider?,
    /** M25：WebDAV 密码的加密存储与客户端装配（未配置时 provider 返回 null、零网络组件）。 */
    private val webDavCredentialStore: com.llzx373.foldreader.core.backup.webdav.android.WebDavCredentialStore,
    private val webDavClientProvider: suspend () -> com.llzx373.foldreader.core.backup.webdav.WebDavClient?,
    /** M25：上传/列表/下载恢复编排（无状态，装配单例）。 */
    private val webDavBackupManager: com.llzx373.foldreader.core.backup.webdav.WebDavBackupManager,
    /** M25：本地自动备份（SAF 目录 + 轮转）。 */
    private val autoBackupRunner: com.llzx373.foldreader.core.backup.AutoBackupRunner,
    /** M21：OCR/气泡模型管理（导入/校验/删除/就绪状态）。 */
    private val modelManager: com.llzx373.foldreader.core.ai.android.ModelManager,
    /** M28：词典管理（导入/删除）与查词缓存失效。 */
    private val dictionaryStore: com.llzx373.foldreader.core.dict.DictionaryStore,
    private val importDictionariesAction: (Uri) -> com.llzx373.foldreader.core.dict.android.DictionaryImporter.Summary,
    private val invalidateDictionariesAction: () -> Unit,
    /** M28：生词本表（列表/删除/导出）。 */
    private val wordEntryDao: com.llzx373.foldreader.core.data.db.WordEntryDao,
    /** 「清除全部 AI 数据」的实际执行（M19）：挂在容器上，测试可传空实现。 */
    private val clearAiDataAction: suspend () -> Unit = {},
) : ViewModel() {

    val preferences: StateFlow<ReadingPreferences> = settingsRepository.preferences
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReadingPreferences())

    private val _importedFonts = MutableStateFlow(fontManager.listImported())
    val importedFonts: StateFlow<List<String>> = _importedFonts.asStateFlow()

    data class ReadingStatsUi(
        val weekMillis: Long = 0,
        val monthMillis: Long = 0,
        val last7Days: List<Pair<Long, Long>> = emptyList(),
    )

    private val _readingStats = MutableStateFlow(ReadingStatsUi())
    val readingStats: StateFlow<ReadingStatsUi> = _readingStats.asStateFlow()

    init {
        refreshReadingStats()
        refreshDictionaries()
    }

    fun refreshReadingStats() = launch {
        val zone = java.time.ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val monthStart = com.llzx373.foldreader.core.reader.monthStartMs(now, zone)
        val sessions = bookshelfRepository.getReadingSessionsBetween(monthStart, now)
            .map { it.dayStartMs to it.durationMs }
        _readingStats.value = ReadingStatsUi(
            weekMillis = com.llzx373.foldreader.core.reader.sumSessionsBetween(
                sessions, com.llzx373.foldreader.core.reader.weekStartMs(now, zone), now,
            ),
            monthMillis = com.llzx373.foldreader.core.reader.sumSessionsBetween(
                sessions, monthStart, now,
            ),
            last7Days = com.llzx373.foldreader.core.reader.dailyBuckets(sessions, now, 7, zone),
        )
    }

    fun updateFontSize(sizeSp: Float) = launch { settingsRepository.setFontSize(sizeSp) }
    fun updateLineSpacing(multiplier: Float) = launch { settingsRepository.setLineSpacing(multiplier) }
    fun updateMarginLevel(level: Int) = launch { settingsRepository.setMarginLevel(level) }
    fun updateMaxLineChars(chars: Int) = launch { settingsRepository.setMaxLineChars(chars) }
    fun updateParagraphSpacing(spacingEm: Float) =
        launch { settingsRepository.setParagraphSpacingEm(spacingEm) }
    fun updateLetterSpacing(spacingEm: Float) =
        launch { settingsRepository.setLetterSpacingEm(spacingEm) }
    fun updateTheme(theme: ReadingTheme) = launch { settingsRepository.setTheme(theme) }
    fun updateCustomBackground(argb: Int?) =
        launch { settingsRepository.setCustomColors(argb, preferences.value.customTextArgb) }
    fun updateCustomText(argb: Int?) =
        launch { settingsRepository.setCustomColors(preferences.value.customBackgroundArgb, argb) }
    fun updateDarkThemeOption(option: DarkThemeOption) =
        launch { settingsRepository.setDarkThemeOption(option) }
    fun updateFontKey(fontKey: String) = launch { settingsRepository.setFontKey(fontKey) }
    fun updatePageTurnMode(mode: PageTurnMode) = launch {
        settingsRepository.setPageTurnMode(mode)
        bookPrefsRepository.applyGlobalPageTurnMode(mode)
    }
    fun updateMiddleTapAction(action: TapAction) = launch { settingsRepository.setMiddleTapAction(action) }
    fun updateMiddleDoubleTapAction(action: TapAction) = launch {
        settingsRepository.setMiddleDoubleTapAction(action)
    }
    fun updateDualPageMode(mode: DualPageMode) = launch { settingsRepository.setDualPageMode(mode) }
    fun updateWideScreenDualPage(enabled: Boolean) =
        launch { settingsRepository.setWideScreenDualPage(enabled) }
    fun updateAvoidCameraCutout(enabled: Boolean) =
        launch { settingsRepository.setAvoidCameraCutout(enabled) }
    fun updateHotspotRatio(ratio: Float) = launch { settingsRepository.setPageTurnHotspotRatio(ratio) }
    fun updateVolumeKeyPaging(enabled: Boolean) =
        launch { settingsRepository.setVolumeKeyPagingEnabled(enabled) }
    fun updateBrightnessGesture(enabled: Boolean) =
        launch { settingsRepository.setBrightnessGestureEnabled(enabled) }
    fun updateSwipeGesture(enabled: Boolean) =
        launch { settingsRepository.setSwipeGestureEnabled(enabled) }
    fun updateSwipeDistance(distanceDp: Float) =
        launch { settingsRepository.setSwipeDistanceDp(distanceDp) }
    fun updateSwipeFlingVelocity(velocityDpPerSec: Float) =
        launch { settingsRepository.setSwipeFlingVelocityDpPerSec(velocityDpPerSec) }
    fun updateKeepScreenOn(enabled: Boolean) = launch { settingsRepository.setKeepScreenOn(enabled) }
    fun updateShowChapterTitle(enabled: Boolean) =
        launch { settingsRepository.setShowChapterTitle(enabled) }
    fun updateShowPageProgress(enabled: Boolean) =
        launch { settingsRepository.setShowPageProgress(enabled) }
    fun updateShowPageNumber(enabled: Boolean) =
        launch { settingsRepository.setShowPageNumber(enabled) }
    fun updateShowBattery(enabled: Boolean) = launch { settingsRepository.setShowBattery(enabled) }
    fun updateShowTime(enabled: Boolean) = launch { settingsRepository.setShowTime(enabled) }
    fun updateTtsSpeechRate(rate: Float) = launch { settingsRepository.setTtsSpeechRate(rate) }
    fun updateTtsPitch(pitch: Float) = launch { settingsRepository.setTtsPitch(pitch) }
    fun updateBookshelfGridView(gridView: Boolean) =
        launch { settingsRepository.setBookshelfGridView(gridView) }
    fun updateBookshelfGridColumns(columns: Int) =
        launch { settingsRepository.setBookshelfGridColumns(columns) }
    fun updateComicDirection(direction: ComicDirection) = launch {
        settingsRepository.setComicDirection(direction)
        bookPrefsRepository.applyGlobalComicDirection(direction)
    }
    fun updateComicFitMode(mode: ComicFitMode) = launch {
        settingsRepository.setComicFitMode(mode)
        bookPrefsRepository.applyGlobalComicFitMode(mode)
    }
    fun updateComicCoverAlone(enabled: Boolean) =
        launch { settingsRepository.setComicDualPageCoverAlone(enabled) }
    fun updateComicSpreadAutoDetect(enabled: Boolean) =
        launch { settingsRepository.setComicSpreadAutoDetect(enabled) }
    fun updateComicScrollGap(gapDp: Int) =
        launch { settingsRepository.setComicScrollGapDp(gapDp) }

    /** 非法正则不保存，返回 false 供界面提示。 */
    fun addCustomChapterRule(pattern: String): Boolean {
        val trimmed = pattern.trim()
        if (trimmed.isEmpty() || runCatching { Regex(trimmed) }.isFailure) return false
        launch { settingsRepository.setCustomChapterRules(preferences.value.customChapterRules + trimmed) }
        return true
    }

    fun removeCustomChapterRule(index: Int) {
        val rules = preferences.value.customChapterRules
        if (index !in rules.indices) return
        launch {
            settingsRepository.setCustomChapterRules(
                rules.toMutableList().apply { removeAt(index) },
            )
        }
    }

    /** 非法正则不保存，返回 false 供界面提示。 */
    fun addAdCleanRule(pattern: String): Boolean {
        val trimmed = pattern.trim()
        if (trimmed.isEmpty() || runCatching { Regex(trimmed) }.isFailure) return false
        launch { settingsRepository.setAdCleanRules(preferences.value.adCleanRules + trimmed) }
        return true
    }

    fun removeAdCleanRule(index: Int) {
        val rules = preferences.value.adCleanRules
        if (index !in rules.indices) return
        launch {
            settingsRepository.setAdCleanRules(
                rules.toMutableList().apply { removeAt(index) },
            )
        }
    }

    fun updateCleanLevel(level: CleanLevel) = launch { settingsRepository.setCleanLevel(level) }

    fun updateCleanToggle(key: String, enabled: Boolean) =
        launch { settingsRepository.setCleanToggle(key, enabled) }

    // ---- AI 服务 ----

    fun updateAiEnabled(enabled: Boolean) = launch { settingsRepository.setAiEnabled(enabled) }
    fun updateAiProtocol(protocol: AiProtocol) = launch { settingsRepository.setAiProtocol(protocol) }
    fun updateAiBaseUrl(baseUrl: String) = launch { settingsRepository.setAiBaseUrl(baseUrl.trim()) }
    fun updateAiModelGeneral(model: String) = launch { settingsRepository.setAiModelGeneral(model.trim()) }
    fun updateAiModelTranslation(model: String) =
        launch { settingsRepository.setAiModelTranslation(model.trim()) }
    fun updateAiModelVision(model: String) = launch { settingsRepository.setAiModelVision(model.trim()) }
    fun updateAiTargetLang(targetLang: AiTargetLang) =
        launch { settingsRepository.setAiTargetLang(targetLang) }
    fun updateAiPricePerMillion(price: Double) =
        launch { settingsRepository.setAiPricePerMillion(price) }

    private val _apiKeyConfigured = MutableStateFlow(credentialStore.readKey() != null)
    val apiKeyConfigured: StateFlow<Boolean> = _apiKeyConfigured.asStateFlow()

    fun saveApiKey(key: String) {
        credentialStore.saveKey(key)
        _apiKeyConfigured.value = true
    }

    fun clearApiKey() {
        credentialStore.clear()
        _apiKeyConfigured.value = false
    }

    /** 「清除全部 AI 数据」（M19/M20）：译本副本 + 翻译台账 + 术语表；凭据与外发历史不动。 */
    fun clearAiData() = launch { clearAiDataAction() }

    /** 测试连接的状态；key 永远不进入这里（也不进日志）。 */
    sealed interface AiTestState {
        data object Running : AiTestState
        data class Success(val reply: String) : AiTestState
        data class Failure(val message: String) : AiTestState
    }

    private val _aiTestState = MutableStateFlow<AiTestState?>(null)
    val aiTestState: StateFlow<AiTestState?> = _aiTestState.asStateFlow()

    fun testConnection() {
        launch {
            _aiTestState.value = AiTestState.Running
            val provider = aiProvider()
            if (provider == null) {
                _aiTestState.value = AiTestState.Failure("请完成协议、地址与 API key 配置")
                return@launch
            }
            try {
                val reply = StringBuilder()
                withTimeout(AI_TEST_TIMEOUT_MS) {
                    provider.chat(
                        listOf(AiMessage.of(AiRole.USER, "用三个字回答：1+1=?")),
                        preferences.value.aiModelGeneral,
                    ).collect { reply.append(it) }
                }
                _aiTestState.value = AiTestState.Success(reply.toString().take(50))
            } catch (e: CancellationException) {
                throw e
            } catch (e: AiException) {
                _aiTestState.value = AiTestState.Failure(e.message ?: "调用失败")
            } catch (e: Exception) {
                _aiTestState.value = AiTestState.Failure("网络不可达或响应异常")
            }
        }
    }

    /** 出站历史直接读台账（最新在前由界面负责反转）。 */
    fun outboundHistory(): List<AiContentGate.OutboundRecord> = aiContentGate.history()

    // ---- WebDAV 备份（M25）----

    fun updateWebDavBaseUrl(baseUrl: String) = launch { settingsRepository.setWebDavBaseUrl(baseUrl) }
    fun updateWebDavUsername(username: String) = launch { settingsRepository.setWebDavUsername(username) }

    /** 首次连接的一次性明示确认落账（幂等）。 */
    fun confirmWebDav() = launch { settingsRepository.setWebDavConfirmed(true) }

    private val _webDavPasswordConfigured = MutableStateFlow(webDavCredentialStore.readPassword() != null)
    val webDavPasswordConfigured: StateFlow<Boolean> = _webDavPasswordConfigured.asStateFlow()

    /** 密码只交给 WebDavCredentialStore，不落任何状态与日志。 */
    fun saveWebDavPassword(password: String) {
        webDavCredentialStore.savePassword(password)
        _webDavPasswordConfigured.value = true
    }

    fun clearWebDavPassword() {
        webDavCredentialStore.clear()
        _webDavPasswordConfigured.value = false
    }

    /** WebDAV 测试连接的状态；密码永远不进入这里（也不进日志）。 */
    sealed interface WebDavTestState {
        data object Running : WebDavTestState
        data class Success(val createdDirectory: Boolean) : WebDavTestState
        data class Failure(val message: String) : WebDavTestState
    }

    private val _webDavTestState = MutableStateFlow<WebDavTestState?>(null)
    val webDavTestState: StateFlow<WebDavTestState?> = _webDavTestState.asStateFlow()

    fun testWebDavConnection() {
        launch {
            _webDavTestState.value = WebDavTestState.Running
            val client = webDavClientProvider()
            if (client == null) {
                _webDavTestState.value = WebDavTestState.Failure("请完成服务器地址、账号与密码配置")
                return@launch
            }
            try {
                val result = withContext(kotlinx.coroutines.Dispatchers.IO) { client.testConnection() }
                _webDavTestState.value = WebDavTestState.Success(result.createdDirectory)
            } catch (e: CancellationException) {
                throw e
            } catch (e: com.llzx373.foldreader.core.backup.webdav.WebDavException) {
                _webDavTestState.value = WebDavTestState.Failure(e.message ?: "连接失败")
            } catch (e: Exception) {
                _webDavTestState.value = WebDavTestState.Failure("网络不可达或响应异常")
            }
        }
    }

    /** 远端备份列表状态；null = 尚未加载。 */
    sealed interface WebDavListState {
        data object Loading : WebDavListState
        data class Ready(val entries: List<com.llzx373.foldreader.core.backup.webdav.WebDavEntry>) :
            WebDavListState
        data class Failed(val message: String) : WebDavListState
    }

    private val _webDavBackups = MutableStateFlow<WebDavListState?>(null)
    val webDavBackups: StateFlow<WebDavListState?> = _webDavBackups.asStateFlow()

    /** 上传中标记（界面据此禁用重复点击）。 */
    private val _webDavUploading = MutableStateFlow(false)
    val webDavUploading: StateFlow<Boolean> = _webDavUploading.asStateFlow()

    fun uploadBackupToWebDav(onResult: (String?, String?) -> Unit) {
        launch {
            _webDavUploading.value = true
            try {
                val name = webDavBackupManager.upload()
                onResult(name, null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onResult(null, e.message ?: "上传失败")
            } finally {
                _webDavUploading.value = false
            }
        }
    }

    fun refreshWebDavBackups() {
        launch {
            _webDavBackups.value = WebDavListState.Loading
            _webDavBackups.value = try {
                WebDavListState.Ready(webDavBackupManager.listBackups())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                WebDavListState.Failed(e.message ?: "列表加载失败")
            }
        }
    }

    /** 恢复前预览：下载并解析元信息，成功后回调（预览 + 待确认恢复的原文）。 */
    fun previewWebDavBackup(
        name: String,
        onResult: (BackupManager.BackupPreview?, String?, String?) -> Unit,
    ) {
        launch {
            try {
                val (preview, text) = webDavBackupManager.downloadForPreview(name)
                onResult(preview, text, null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onResult(null, null, e.message ?: "下载失败")
            }
        }
    }

    /** 确认恢复：走与本地导入相同的链路（contentHash 对齐既有书）。 */
    fun restoreWebDavBackup(
        backupText: String,
        onResult: (BackupManager.ImportResult?, String?) -> Unit,
    ) {
        launch {
            try {
                onResult(webDavBackupManager.restore(backupText), null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onResult(null, e.message ?: "恢复失败")
            }
        }
    }

    // ---- 本地自动备份（M25）----

    fun updateAutoBackupEnabled(enabled: Boolean) =
        launch { settingsRepository.setAutoBackupEnabled(enabled) }

    fun updateAutoBackupDirUri(treeUri: String) =
        launch { settingsRepository.setAutoBackupDirUri(treeUri) }

    fun updateAutoBackupKeepCount(keep: Int) =
        launch { settingsRepository.setAutoBackupKeepCount(keep) }

    private val _autoBackupRunning = MutableStateFlow(false)
    val autoBackupRunning: StateFlow<Boolean> = _autoBackupRunning.asStateFlow()

    /** 「立即备份一次」：无条件导出 + 轮转。 */
    fun runAutoBackupNow(onResult: (String?, String?) -> Unit) {
        launch {
            _autoBackupRunning.value = true
            try {
                onResult(autoBackupRunner.runNow(), null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onResult(null, e.message ?: "备份失败")
            } finally {
                _autoBackupRunning.value = false
            }
        }
    }

    fun importFont(uri: Uri, displayName: String?, onResult: (Boolean) -> Unit) {
        launch {
            val key = fontManager.import(uri, displayName)
            if (key != null) {
                _importedFonts.value = fontManager.listImported()
                settingsRepository.setFontKey(key)
            }
            onResult(key != null)
        }
    }

    // ---- OCR 模型（M21/M24）----

    private val _modelStatus = MutableStateFlow(modelManager.slots())
    val modelStatus: StateFlow<List<com.llzx373.foldreader.core.ai.android.ModelManager.ModelSlot>> =
        _modelStatus.asStateFlow()

    fun importModel(
        uri: Uri,
        displayName: String?,
        onResult: (com.llzx373.foldreader.core.ai.android.ModelManager.ImportResult) -> Unit,
    ) {
        launch {
            val result = modelManager.import(uri, displayName)
            _modelStatus.value = modelManager.slots()
            onResult(result)
        }
    }

    /** 自定义模型导入（M24）：私有微调/其他 .onnx 进指定槽位，不校验清单。 */
    fun importCustomModel(
        spec: com.llzx373.foldreader.core.ocr.OcrModelSpec,
        uri: Uri,
        onResult: (com.llzx373.foldreader.core.ai.android.ModelManager.ImportResult) -> Unit,
    ) {
        launch {
            val result = modelManager.importCustom(spec, uri)
            _modelStatus.value = modelManager.slots()
            onResult(result)
        }
    }

    fun deleteModel(modelId: String) {
        modelManager.delete(modelId)
        _modelStatus.value = modelManager.slots()
    }

    fun deleteCustomModel(modelId: String) {
        modelManager.deleteCustom(modelId)
        _modelStatus.value = modelManager.slots()
    }

    fun updateOcrRecLang(modelId: String) = launch { settingsRepository.setOcrRecLang(modelId) }

    // ---- 词典管理（M28）----

    private val _dictionaries = MutableStateFlow<List<com.llzx373.foldreader.core.dict.DictInfo>>(emptyList())
    val dictionaries: StateFlow<List<com.llzx373.foldreader.core.dict.DictInfo>> =
        _dictionaries.asStateFlow()

    private val _dictionariesLoaded = MutableStateFlow(false)
    val dictionariesLoaded: StateFlow<Boolean> = _dictionariesLoaded.asStateFlow()

    private fun refreshDictionaries() = launch {
        _dictionaries.value = withContext(kotlinx.coroutines.Dispatchers.IO) { dictionaryStore.list() }
        _dictionariesLoaded.value = true
    }

    /** 导入词典目录（SAF 树）：逐词干导入，汇总成功/失败；查词缓存随之失效。 */
    fun importDictionaries(treeUri: Uri, onResult: (String) -> Unit) {
        launch {
            val message = withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val summary = importDictionariesAction(treeUri)
                    invalidateDictionariesAction()
                    buildString {
                        if (summary.imported.isNotEmpty()) {
                            append("已导入 ${summary.imported.size} 部词典（")
                            append(summary.imported.joinToString("、") { it.bookName })
                            append("）")
                        }
                        summary.failures.forEach { (stem, reason) ->
                            if (isNotEmpty()) append("\n")
                            append("$stem：$reason")
                        }
                    }
                } catch (e: com.llzx373.foldreader.core.dict.DictionaryStore.ImportException) {
                    e.message ?: "导入失败"
                } catch (e: Exception) {
                    "导入失败：${e.message ?: "文件读取异常"}"
                }
            }
            refreshDictionaries()
            onResult(message)
        }
    }

    fun deleteDictionary(id: String) {
        launch {
            withContext(kotlinx.coroutines.Dispatchers.IO) { dictionaryStore.delete(id) }
            invalidateDictionariesAction()
            refreshDictionaries()
        }
    }

    // ---- 生词本（M28）----

    /** 列表项：词条 + 来源书名（书被连带删除时只剩孤儿的情况不会发生——外键级联）。 */
    data class VocabularyItem(
        val entry: com.llzx373.foldreader.core.data.db.WordEntryEntity,
        val bookTitle: String,
    )

    val vocabulary: StateFlow<List<VocabularyItem>> =
        kotlinx.coroutines.flow.combine(
            wordEntryDao.observeAll(),
            bookshelfRepository.observeBookshelf(),
        ) { entries, books ->
            val titles = books.associateBy({ it.id }, { it.title })
            entries.map { VocabularyItem(it, titles[it.bookId] ?: "（未知书）") }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun deleteWordEntry(id: Long) = launch { wordEntryDao.deleteById(id) }

    fun exportBackup(uri: Uri, onResult: (String?) -> Unit) {
        launch {
            val error = runCatching { backupManager.exportTo(uri) }.exceptionOrNull()?.message
            onResult(error)
        }
    }

    fun importBackup(uri: Uri, onResult: (BackupManager.ImportResult?, String?) -> Unit) {
        launch {
            runCatching { backupManager.importFrom(uri) }
                .onSuccess { onResult(it, null) }
                .onFailure { onResult(null, it.message ?: "导入失败") }
        }
    }

    private fun launch(block: suspend () -> Unit) = viewModelScope.launch { block() }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                SettingsViewModel(
                    settingsRepository = container.settingsRepository,
                    fontManager = container.fontManager,
                    bookshelfRepository = container.bookshelfRepository,
                    backupManager = container.backupManager,
                    bookPrefsRepository = container.bookPrefsRepository,
                    credentialStore = container.credentialStore,
                    aiContentGate = container.aiContentGate,
                    aiProvider = container::aiProvider,
                    webDavCredentialStore = container.webDavCredentialStore,
                    webDavClientProvider = container::webDavClient,
                    webDavBackupManager = container.webDavBackupManager,
                    autoBackupRunner = container.autoBackupRunner,
                    modelManager = container.modelManager,
                    dictionaryStore = container.dictionaryStore,
                    importDictionariesAction = container.dictionaryImporter::importFromTree,
                    invalidateDictionariesAction = container.dictionaryLookupService::invalidate,
                    wordEntryDao = container.database.wordEntryDao(),
                    clearAiDataAction = container::clearAiData,
                )
            }
        }
        private const val AI_TEST_TIMEOUT_MS = 90_000L
    }
}

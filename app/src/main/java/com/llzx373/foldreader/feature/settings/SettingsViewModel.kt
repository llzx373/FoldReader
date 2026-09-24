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
    fun updateBookshelfGridView(gridView: Boolean) =
        launch { settingsRepository.setBookshelfGridView(gridView) }
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
                    clearAiDataAction = container::clearAiData,
                )
            }
        }
        private const val AI_TEST_TIMEOUT_MS = 90_000L
    }
}

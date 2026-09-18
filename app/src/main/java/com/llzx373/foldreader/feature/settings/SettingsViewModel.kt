package com.llzx373.foldreader.feature.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.llzx373.foldreader.AppContainer
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
import com.llzx373.foldreader.core.reader.FontManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val fontManager: FontManager,
    private val bookshelfRepository: com.llzx373.foldreader.core.data.repository.BookshelfRepository,
    private val backupManager: BackupManager,
    private val bookPrefsRepository: BookPrefsRepository,
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
                )
            }
        }
    }
}

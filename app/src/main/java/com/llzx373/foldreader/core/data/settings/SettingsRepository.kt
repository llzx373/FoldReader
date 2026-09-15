package com.llzx373.foldreader.core.data.settings

import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    val preferences: Flow<ReadingPreferences>

    suspend fun setFontSize(sizeSp: Float)
    suspend fun setLineSpacing(multiplier: Float)
    suspend fun setMarginLevel(level: Int)
    suspend fun setMaxLineChars(chars: Int)
    suspend fun setParagraphSpacingEm(spacingEm: Float)
    suspend fun setLetterSpacingEm(spacingEm: Float)
    suspend fun setTheme(theme: ReadingTheme)
    suspend fun setCustomColors(backgroundArgb: Int?, textArgb: Int?)
    suspend fun setDarkThemeOption(option: DarkThemeOption)
    suspend fun setFontKey(fontKey: String)
    suspend fun setDualPageMode(mode: DualPageMode)
    suspend fun setWideScreenDualPage(enabled: Boolean)
    suspend fun setPageTurnMode(mode: PageTurnMode)
    suspend fun setPageTurnModeExplicit(explicit: Boolean)
    suspend fun setPageTurnHotspotRatio(ratio: Float)
    suspend fun setVolumeKeyPagingEnabled(enabled: Boolean)
    suspend fun setBrightnessGestureEnabled(enabled: Boolean)
    suspend fun setSwipeGestureEnabled(enabled: Boolean)
    suspend fun setKeepScreenOn(enabled: Boolean)
    suspend fun setShowChapterTitle(enabled: Boolean)
    suspend fun setShowPageProgress(enabled: Boolean)
    suspend fun setShowPageNumber(enabled: Boolean)
    suspend fun setShowBattery(enabled: Boolean)
    suspend fun setShowTime(enabled: Boolean)
    suspend fun setReaderBrightness(brightness: Float)
    suspend fun setAutoPageEnabled(enabled: Boolean)
    suspend fun setAutoPageMode(mode: AutoPageMode)
    suspend fun setAutoPageIntervalSec(seconds: Int)
    suspend fun setAutoPageSpeedPx(pxPerSecond: Float)
    suspend fun setSimulationDegraded(degraded: Boolean)
    suspend fun setPanelScreenOff(enabled: Boolean)
    suspend fun setBookshelfGridView(gridView: Boolean)
    suspend fun setCustomChapterRules(rules: List<String>)
    suspend fun setAdCleanRules(rules: List<String>)
}

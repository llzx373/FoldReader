package com.llzx373.foldreader.core.data.settings

import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    val preferences: Flow<ReadingPreferences>

    suspend fun setFontSize(sizeSp: Float)
    suspend fun setLineSpacing(multiplier: Float)
    suspend fun setMarginLevel(level: Int)
    suspend fun setTheme(theme: ReadingTheme)
    suspend fun setCustomColors(backgroundArgb: Int?, textArgb: Int?)
    suspend fun setDarkThemeOption(option: DarkThemeOption)
    suspend fun setFontKey(fontKey: String)
    suspend fun setDualPageMode(mode: DualPageMode)
    suspend fun setPageTurnMode(mode: PageTurnMode)
    suspend fun setPageTurnHotspotRatio(ratio: Float)
    suspend fun setVolumeKeyPagingEnabled(enabled: Boolean)
    suspend fun setKeepScreenOn(enabled: Boolean)
    suspend fun setShowChapterTitle(enabled: Boolean)
    suspend fun setShowPageProgress(enabled: Boolean)
    suspend fun setShowBattery(enabled: Boolean)
    suspend fun setShowTime(enabled: Boolean)
    suspend fun setReaderBrightness(brightness: Float)
    suspend fun setBookshelfGridView(gridView: Boolean)
}

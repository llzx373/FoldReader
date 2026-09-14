package com.llzx373.foldreader.core.data.settings

import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    val preferences: Flow<ReadingPreferences>

    suspend fun setFontSize(sizeSp: Float)
    suspend fun setLineSpacing(multiplier: Float)
    suspend fun setTheme(theme: ReadingTheme)
    suspend fun setPageTurnMode(mode: PageTurnMode)
    suspend fun setPageTurnHotspotRatio(ratio: Float)
    suspend fun setVolumeKeyPagingEnabled(enabled: Boolean)
    suspend fun setKeepScreenOn(enabled: Boolean)
}

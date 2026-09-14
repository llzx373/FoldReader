package com.llzx373.foldreader.core.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.readingPreferencesStore: DataStore<Preferences> by preferencesDataStore(
    name = "reading_preferences",
)

class SettingsRepositoryImpl(
    private val context: Context,
) : SettingsRepository {

    private object Keys {
        val FONT_SIZE_SP = floatPreferencesKey("font_size_sp")
        val LINE_SPACING_MULTIPLIER = floatPreferencesKey("line_spacing_multiplier")
        val THEME_ID = stringPreferencesKey("theme_id")
        val PAGE_TURN_MODE = stringPreferencesKey("page_turn_mode")
        val PAGE_TURN_HOTSPOT_RATIO = floatPreferencesKey("page_turn_hotspot_ratio")
        val VOLUME_KEY_PAGING_ENABLED = booleanPreferencesKey("volume_key_paging_enabled")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val BOOKSHELF_GRID_VIEW = booleanPreferencesKey("bookshelf_grid_view")
    }

    override val preferences: Flow<ReadingPreferences> =
        context.readingPreferencesStore.data.map { prefs ->
            val defaults = ReadingPreferences()
            ReadingPreferences(
                fontSizeSp = prefs[Keys.FONT_SIZE_SP] ?: defaults.fontSizeSp,
                lineSpacingMultiplier = prefs[Keys.LINE_SPACING_MULTIPLIER]
                    ?: defaults.lineSpacingMultiplier,
                themeId = enumOrDefault(prefs[Keys.THEME_ID], defaults.themeId),
                pageTurnMode = enumOrDefault(prefs[Keys.PAGE_TURN_MODE], defaults.pageTurnMode),
                pageTurnHotspotRatio = prefs[Keys.PAGE_TURN_HOTSPOT_RATIO]
                    ?: defaults.pageTurnHotspotRatio,
                volumeKeyPagingEnabled = prefs[Keys.VOLUME_KEY_PAGING_ENABLED]
                    ?: defaults.volumeKeyPagingEnabled,
                keepScreenOn = prefs[Keys.KEEP_SCREEN_ON] ?: defaults.keepScreenOn,
                bookshelfGridView = prefs[Keys.BOOKSHELF_GRID_VIEW] ?: defaults.bookshelfGridView,
            )
        }

    override suspend fun setFontSize(sizeSp: Float) {
        context.readingPreferencesStore.edit { it[Keys.FONT_SIZE_SP] = sizeSp }
    }

    override suspend fun setLineSpacing(multiplier: Float) {
        context.readingPreferencesStore.edit { it[Keys.LINE_SPACING_MULTIPLIER] = multiplier }
    }

    override suspend fun setTheme(theme: ReadingTheme) {
        context.readingPreferencesStore.edit { it[Keys.THEME_ID] = theme.name }
    }

    override suspend fun setPageTurnMode(mode: PageTurnMode) {
        context.readingPreferencesStore.edit { it[Keys.PAGE_TURN_MODE] = mode.name }
    }

    override suspend fun setPageTurnHotspotRatio(ratio: Float) {
        context.readingPreferencesStore.edit { it[Keys.PAGE_TURN_HOTSPOT_RATIO] = ratio }
    }

    override suspend fun setVolumeKeyPagingEnabled(enabled: Boolean) {
        context.readingPreferencesStore.edit { it[Keys.VOLUME_KEY_PAGING_ENABLED] = enabled }
    }

    override suspend fun setKeepScreenOn(enabled: Boolean) {
        context.readingPreferencesStore.edit { it[Keys.KEEP_SCREEN_ON] = enabled }
    }

    override suspend fun setBookshelfGridView(gridView: Boolean) {
        context.readingPreferencesStore.edit { it[Keys.BOOKSHELF_GRID_VIEW] = gridView }
    }
}

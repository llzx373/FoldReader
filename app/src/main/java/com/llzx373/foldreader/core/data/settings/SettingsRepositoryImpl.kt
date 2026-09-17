package com.llzx373.foldreader.core.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
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
        val MARGIN_LEVEL = intPreferencesKey("margin_level")
        val MAX_LINE_CHARS = intPreferencesKey("max_line_chars")
        val PARAGRAPH_SPACING_EM = floatPreferencesKey("paragraph_spacing_em")
        val LETTER_SPACING_EM = floatPreferencesKey("letter_spacing_em")
        val THEME_ID = stringPreferencesKey("theme_id")
        val CUSTOM_BACKGROUND_ARGB = intPreferencesKey("custom_background_argb")
        val CUSTOM_TEXT_ARGB = intPreferencesKey("custom_text_argb")
        val DARK_THEME_OPTION = stringPreferencesKey("dark_theme_option")
        val FONT_KEY = stringPreferencesKey("font_key")
        val DUAL_PAGE_MODE = stringPreferencesKey("dual_page_mode")
        val WIDE_SCREEN_DUAL_PAGE = booleanPreferencesKey("wide_screen_dual_page")
        val AVOID_CAMERA_CUTOUT = booleanPreferencesKey("avoid_camera_cutout")
        val PAGE_TURN_MODE = stringPreferencesKey("page_turn_mode")
        val PAGE_TURN_HOTSPOT_RATIO = floatPreferencesKey("page_turn_hotspot_ratio")
        val VOLUME_KEY_PAGING_ENABLED = booleanPreferencesKey("volume_key_paging_enabled")
        val BRIGHTNESS_GESTURE_ENABLED = booleanPreferencesKey("brightness_gesture_enabled")
        val SWIPE_GESTURE_ENABLED = booleanPreferencesKey("swipe_gesture_enabled")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val SHOW_CHAPTER_TITLE = booleanPreferencesKey("show_chapter_title")
        val SHOW_PAGE_PROGRESS = booleanPreferencesKey("show_page_progress")
        val SHOW_PAGE_NUMBER = booleanPreferencesKey("show_page_number")
        val SHOW_BATTERY = booleanPreferencesKey("show_battery")
        val SHOW_TIME = booleanPreferencesKey("show_time")
        val READER_BRIGHTNESS = floatPreferencesKey("reader_brightness")
        val AUTO_PAGE_ENABLED = booleanPreferencesKey("auto_page_enabled")
        val AUTO_PAGE_MODE = stringPreferencesKey("auto_page_mode")
        val AUTO_PAGE_INTERVAL_SEC = intPreferencesKey("auto_page_interval_sec")
        val AUTO_PAGE_SPEED_PX = floatPreferencesKey("auto_page_speed_px")
        val PANEL_SCREEN_OFF = booleanPreferencesKey("panel_screen_off")
        val BOOKSHELF_GRID_VIEW = booleanPreferencesKey("bookshelf_grid_view")
        val BOOKSHELF_SORT = stringPreferencesKey("bookshelf_sort")
        val CUSTOM_CHAPTER_RULES = stringPreferencesKey("custom_chapter_rules")
        val AD_CLEAN_RULES = stringPreferencesKey("ad_clean_rules")
    }

    override val preferences: Flow<ReadingPreferences> =
        context.readingPreferencesStore.data.map { prefs ->
            val defaults = ReadingPreferences()
            ReadingPreferences(
                fontSizeSp = prefs[Keys.FONT_SIZE_SP] ?: defaults.fontSizeSp,
                lineSpacingMultiplier = prefs[Keys.LINE_SPACING_MULTIPLIER]
                    ?: defaults.lineSpacingMultiplier,
                marginLevel = prefs[Keys.MARGIN_LEVEL] ?: defaults.marginLevel,
                maxLineChars = prefs[Keys.MAX_LINE_CHARS] ?: defaults.maxLineChars,
                paragraphSpacingEm = prefs[Keys.PARAGRAPH_SPACING_EM]
                    ?: defaults.paragraphSpacingEm,
                letterSpacingEm = prefs[Keys.LETTER_SPACING_EM] ?: defaults.letterSpacingEm,
                themeId = enumOrDefault(prefs[Keys.THEME_ID], defaults.themeId),
                customBackgroundArgb = prefs[Keys.CUSTOM_BACKGROUND_ARGB],
                customTextArgb = prefs[Keys.CUSTOM_TEXT_ARGB],
                darkThemeOption = enumOrDefault(prefs[Keys.DARK_THEME_OPTION], defaults.darkThemeOption),
                fontKey = prefs[Keys.FONT_KEY] ?: defaults.fontKey,
                dualPageMode = enumOrDefault(prefs[Keys.DUAL_PAGE_MODE], defaults.dualPageMode),
                wideScreenDualPage = prefs[Keys.WIDE_SCREEN_DUAL_PAGE]
                    ?: defaults.wideScreenDualPage,
                avoidCameraCutout = prefs[Keys.AVOID_CAMERA_CUTOUT]
                    ?: defaults.avoidCameraCutout,
                pageTurnMode = enumOrDefault(prefs[Keys.PAGE_TURN_MODE], defaults.pageTurnMode),
                pageTurnHotspotRatio = prefs[Keys.PAGE_TURN_HOTSPOT_RATIO]
                    ?: defaults.pageTurnHotspotRatio,
                volumeKeyPagingEnabled = prefs[Keys.VOLUME_KEY_PAGING_ENABLED]
                    ?: defaults.volumeKeyPagingEnabled,
                brightnessGestureEnabled = prefs[Keys.BRIGHTNESS_GESTURE_ENABLED]
                    ?: defaults.brightnessGestureEnabled,
                swipeGestureEnabled = prefs[Keys.SWIPE_GESTURE_ENABLED]
                    ?: defaults.swipeGestureEnabled,
                keepScreenOn = prefs[Keys.KEEP_SCREEN_ON] ?: defaults.keepScreenOn,
                showChapterTitle = prefs[Keys.SHOW_CHAPTER_TITLE] ?: defaults.showChapterTitle,
                showPageProgress = prefs[Keys.SHOW_PAGE_PROGRESS] ?: defaults.showPageProgress,
                showPageNumber = prefs[Keys.SHOW_PAGE_NUMBER] ?: defaults.showPageNumber,
                showBattery = prefs[Keys.SHOW_BATTERY] ?: defaults.showBattery,
                showTime = prefs[Keys.SHOW_TIME] ?: defaults.showTime,
                readerBrightness = prefs[Keys.READER_BRIGHTNESS] ?: defaults.readerBrightness,
                autoPageEnabled = prefs[Keys.AUTO_PAGE_ENABLED] ?: defaults.autoPageEnabled,
                autoPageMode = enumOrDefault(prefs[Keys.AUTO_PAGE_MODE], defaults.autoPageMode),
                autoPageIntervalSec = prefs[Keys.AUTO_PAGE_INTERVAL_SEC] ?: defaults.autoPageIntervalSec,
                autoPageSpeedPx = prefs[Keys.AUTO_PAGE_SPEED_PX] ?: defaults.autoPageSpeedPx,
                panelScreenOff = prefs[Keys.PANEL_SCREEN_OFF] ?: defaults.panelScreenOff,
                bookshelfGridView = prefs[Keys.BOOKSHELF_GRID_VIEW] ?: defaults.bookshelfGridView,
                bookshelfSort = enumOrDefault(prefs[Keys.BOOKSHELF_SORT], defaults.bookshelfSort),
                customChapterRules = decodeCustomChapterRules(prefs[Keys.CUSTOM_CHAPTER_RULES]),
                adCleanRules = decodeRuleList(prefs[Keys.AD_CLEAN_RULES]),
            )
        }

    override suspend fun setFontSize(sizeSp: Float) {
        context.readingPreferencesStore.edit { it[Keys.FONT_SIZE_SP] = sizeSp }
    }

    override suspend fun setLineSpacing(multiplier: Float) {
        context.readingPreferencesStore.edit { it[Keys.LINE_SPACING_MULTIPLIER] = multiplier }
    }

    override suspend fun setMarginLevel(level: Int) {
        context.readingPreferencesStore.edit { it[Keys.MARGIN_LEVEL] = level.coerceIn(0, 2) }
    }

    override suspend fun setMaxLineChars(chars: Int) {
        context.readingPreferencesStore.edit { it[Keys.MAX_LINE_CHARS] = chars.coerceIn(18, 40) }
    }

    override suspend fun setParagraphSpacingEm(spacingEm: Float) {
        context.readingPreferencesStore.edit {
            it[Keys.PARAGRAPH_SPACING_EM] = spacingEm.coerceIn(0f, 2f)
        }
    }

    override suspend fun setLetterSpacingEm(spacingEm: Float) {
        context.readingPreferencesStore.edit {
            it[Keys.LETTER_SPACING_EM] = spacingEm.coerceIn(0f, 0.5f)
        }
    }

    override suspend fun setCustomColors(backgroundArgb: Int?, textArgb: Int?) {
        context.readingPreferencesStore.edit { prefs ->
            if (backgroundArgb == null) prefs.remove(Keys.CUSTOM_BACKGROUND_ARGB)
            else prefs[Keys.CUSTOM_BACKGROUND_ARGB] = backgroundArgb
            if (textArgb == null) prefs.remove(Keys.CUSTOM_TEXT_ARGB)
            else prefs[Keys.CUSTOM_TEXT_ARGB] = textArgb
        }
    }

    override suspend fun setDarkThemeOption(option: DarkThemeOption) {
        context.readingPreferencesStore.edit { it[Keys.DARK_THEME_OPTION] = option.name }
    }

    override suspend fun setFontKey(fontKey: String) {
        context.readingPreferencesStore.edit { it[Keys.FONT_KEY] = fontKey }
    }

    override suspend fun setDualPageMode(mode: DualPageMode) {
        context.readingPreferencesStore.edit { it[Keys.DUAL_PAGE_MODE] = mode.name }
    }

    override suspend fun setWideScreenDualPage(enabled: Boolean) {
        context.readingPreferencesStore.edit { it[Keys.WIDE_SCREEN_DUAL_PAGE] = enabled }
    }

    override suspend fun setAvoidCameraCutout(enabled: Boolean) {
        context.readingPreferencesStore.edit { it[Keys.AVOID_CAMERA_CUTOUT] = enabled }
    }

    override suspend fun setTheme(theme: ReadingTheme) {
        context.readingPreferencesStore.edit { it[Keys.THEME_ID] = theme.name }
    }

    override suspend fun setPageTurnMode(mode: PageTurnMode) {
        context.readingPreferencesStore.edit {
            it[Keys.PAGE_TURN_MODE] = mode.name
        }
    }

    override suspend fun setPageTurnHotspotRatio(ratio: Float) {
        context.readingPreferencesStore.edit { it[Keys.PAGE_TURN_HOTSPOT_RATIO] = ratio }
    }

    override suspend fun setVolumeKeyPagingEnabled(enabled: Boolean) {
        context.readingPreferencesStore.edit { it[Keys.VOLUME_KEY_PAGING_ENABLED] = enabled }
    }

    override suspend fun setBrightnessGestureEnabled(enabled: Boolean) {
        context.readingPreferencesStore.edit { it[Keys.BRIGHTNESS_GESTURE_ENABLED] = enabled }
    }

    override suspend fun setSwipeGestureEnabled(enabled: Boolean) {
        context.readingPreferencesStore.edit { it[Keys.SWIPE_GESTURE_ENABLED] = enabled }
    }

    override suspend fun setKeepScreenOn(enabled: Boolean) {
        context.readingPreferencesStore.edit { it[Keys.KEEP_SCREEN_ON] = enabled }
    }

    override suspend fun setShowChapterTitle(enabled: Boolean) {
        context.readingPreferencesStore.edit { it[Keys.SHOW_CHAPTER_TITLE] = enabled }
    }

    override suspend fun setShowPageProgress(enabled: Boolean) {
        context.readingPreferencesStore.edit { it[Keys.SHOW_PAGE_PROGRESS] = enabled }
    }

    override suspend fun setShowPageNumber(enabled: Boolean) {
        context.readingPreferencesStore.edit { it[Keys.SHOW_PAGE_NUMBER] = enabled }
    }

    override suspend fun setShowBattery(enabled: Boolean) {
        context.readingPreferencesStore.edit { it[Keys.SHOW_BATTERY] = enabled }
    }

    override suspend fun setShowTime(enabled: Boolean) {
        context.readingPreferencesStore.edit { it[Keys.SHOW_TIME] = enabled }
    }

    override suspend fun setReaderBrightness(brightness: Float) {
        context.readingPreferencesStore.edit {
            it[Keys.READER_BRIGHTNESS] = brightness.coerceIn(-1f, 1f)
        }
    }

    override suspend fun setAutoPageEnabled(enabled: Boolean) {
        context.readingPreferencesStore.edit { it[Keys.AUTO_PAGE_ENABLED] = enabled }
    }

    override suspend fun setAutoPageMode(mode: AutoPageMode) {
        context.readingPreferencesStore.edit { it[Keys.AUTO_PAGE_MODE] = mode.name }
    }

    override suspend fun setAutoPageIntervalSec(seconds: Int) {
        context.readingPreferencesStore.edit {
            it[Keys.AUTO_PAGE_INTERVAL_SEC] = seconds.coerceIn(3, 30)
        }
    }

    override suspend fun setAutoPageSpeedPx(pxPerSecond: Float) {
        context.readingPreferencesStore.edit {
            it[Keys.AUTO_PAGE_SPEED_PX] = pxPerSecond.coerceIn(10f, 300f)
        }
    }

    override suspend fun setPanelScreenOff(enabled: Boolean) {
        context.readingPreferencesStore.edit { it[Keys.PANEL_SCREEN_OFF] = enabled }
    }

    override suspend fun setBookshelfGridView(gridView: Boolean) {
        context.readingPreferencesStore.edit { it[Keys.BOOKSHELF_GRID_VIEW] = gridView }
    }

    override suspend fun setBookshelfSort(sort: BookshelfSort) {
        context.readingPreferencesStore.edit { it[Keys.BOOKSHELF_SORT] = sort.name }
    }

    override suspend fun setCustomChapterRules(rules: List<String>) {
        val cleaned = rules.map { it.trim() }.filter { it.isNotEmpty() }
        context.readingPreferencesStore.edit {
            it[Keys.CUSTOM_CHAPTER_RULES] = encodeCustomChapterRules(cleaned)
        }
    }

    override suspend fun setAdCleanRules(rules: List<String>) {
        val cleaned = rules.map { it.trim() }.filter { it.isNotEmpty() }
        context.readingPreferencesStore.edit {
            it[Keys.AD_CLEAN_RULES] = encodeRuleList(cleaned)
        }
    }
}

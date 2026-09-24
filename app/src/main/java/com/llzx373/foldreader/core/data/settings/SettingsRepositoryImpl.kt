package com.llzx373.foldreader.core.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.llzx373.foldreader.core.ai.AiProtocol
import com.llzx373.foldreader.core.ai.AiTargetLang
import com.llzx373.foldreader.core.format.clean.CleanLevel
import com.llzx373.foldreader.core.format.clean.CleanToggles
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
        val MIDDLE_TAP_ACTION = stringPreferencesKey("middle_tap_action")
        val MIDDLE_DOUBLE_TAP_ACTION = stringPreferencesKey("middle_double_tap_action")
        val VOLUME_KEY_PAGING_ENABLED = booleanPreferencesKey("volume_key_paging_enabled")
        val BRIGHTNESS_GESTURE_ENABLED = booleanPreferencesKey("brightness_gesture_enabled")
        val SWIPE_GESTURE_ENABLED = booleanPreferencesKey("swipe_gesture_enabled")
        val SWIPE_DISTANCE_DP = floatPreferencesKey("swipe_distance_dp")
        val SWIPE_FLING_VELOCITY_DP_PER_SEC = floatPreferencesKey("swipe_fling_velocity_dp_per_sec")
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
        val CLEAN_LEVEL = stringPreferencesKey("clean_level")
        val CLEAN_TOGGLES = stringPreferencesKey("clean_toggles")
        val COMIC_DIRECTION = stringPreferencesKey("comic_direction")
        val COMIC_DUAL_PAGE_COVER_ALONE = booleanPreferencesKey("comic_dual_page_cover_alone")
        val COMIC_SPREAD_AUTO_DETECT = booleanPreferencesKey("comic_spread_auto_detect")
        val COMIC_FIT_MODE = stringPreferencesKey("comic_fit_mode")
        val COMIC_SCROLL_GAP_DP = intPreferencesKey("comic_scroll_gap_dp")
        val AI_ENABLED = booleanPreferencesKey("ai_enabled")
        val AI_PROTOCOL = stringPreferencesKey("ai_protocol")
        val AI_BASE_URL = stringPreferencesKey("ai_base_url")
        val AI_MODEL_GENERAL = stringPreferencesKey("ai_model_general")
        val AI_MODEL_TRANSLATION = stringPreferencesKey("ai_model_translation")
        val AI_MODEL_VISION = stringPreferencesKey("ai_model_vision")
        val AI_TARGET_LANG = stringPreferencesKey("ai_target_lang")
        val AI_CHAPTER_RULE_CONFIRMED = booleanPreferencesKey("ai_chapter_rule_confirmed")
        val AI_TRANSLATION_CONFIRMED = booleanPreferencesKey("ai_translation_confirmed")
        val TRANSLATION_VIEW_HINT_SHOWN = booleanPreferencesKey("translation_view_hint_shown")
        val AI_PRICE_PER_MILLION = doublePreferencesKey("ai_price_per_million")
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
                middleTapAction = enumOrDefault(prefs[Keys.MIDDLE_TAP_ACTION], defaults.middleTapAction),
                middleDoubleTapAction = enumOrDefault(
                    prefs[Keys.MIDDLE_DOUBLE_TAP_ACTION],
                    defaults.middleDoubleTapAction,
                ),
                volumeKeyPagingEnabled = prefs[Keys.VOLUME_KEY_PAGING_ENABLED]
                    ?: defaults.volumeKeyPagingEnabled,
                brightnessGestureEnabled = prefs[Keys.BRIGHTNESS_GESTURE_ENABLED]
                    ?: defaults.brightnessGestureEnabled,
                swipeGestureEnabled = prefs[Keys.SWIPE_GESTURE_ENABLED]
                    ?: defaults.swipeGestureEnabled,
                swipeDistanceDp = prefs[Keys.SWIPE_DISTANCE_DP] ?: defaults.swipeDistanceDp,
                swipeFlingVelocityDpPerSec = prefs[Keys.SWIPE_FLING_VELOCITY_DP_PER_SEC]
                    ?: defaults.swipeFlingVelocityDpPerSec,
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
                cleanLevel = enumOrDefault(prefs[Keys.CLEAN_LEVEL], defaults.cleanLevel),
                cleanToggles = CleanToggles.decode(prefs[Keys.CLEAN_TOGGLES])
                    ?: defaults.cleanToggles,
                comicDirection = enumOrDefault(prefs[Keys.COMIC_DIRECTION], defaults.comicDirection),
                comicDualPageCoverAlone = prefs[Keys.COMIC_DUAL_PAGE_COVER_ALONE]
                    ?: defaults.comicDualPageCoverAlone,
                comicSpreadAutoDetect = prefs[Keys.COMIC_SPREAD_AUTO_DETECT]
                    ?: defaults.comicSpreadAutoDetect,
                comicFitMode = enumOrDefault(prefs[Keys.COMIC_FIT_MODE], defaults.comicFitMode),
                comicScrollGapDp = prefs[Keys.COMIC_SCROLL_GAP_DP] ?: defaults.comicScrollGapDp,
                aiEnabled = prefs[Keys.AI_ENABLED] ?: defaults.aiEnabled,
                aiProtocol = enumOrDefault(prefs[Keys.AI_PROTOCOL], defaults.aiProtocol),
                aiBaseUrl = prefs[Keys.AI_BASE_URL] ?: defaults.aiBaseUrl,
                aiModelGeneral = prefs[Keys.AI_MODEL_GENERAL] ?: defaults.aiModelGeneral,
                aiModelTranslation = prefs[Keys.AI_MODEL_TRANSLATION]
                    ?: defaults.aiModelTranslation,
                aiModelVision = prefs[Keys.AI_MODEL_VISION] ?: defaults.aiModelVision,
                aiTargetLang = enumOrDefault(prefs[Keys.AI_TARGET_LANG], defaults.aiTargetLang),
                aiChapterRuleConfirmed = prefs[Keys.AI_CHAPTER_RULE_CONFIRMED]
                    ?: defaults.aiChapterRuleConfirmed,
                aiTranslationConfirmed = prefs[Keys.AI_TRANSLATION_CONFIRMED]
                    ?: defaults.aiTranslationConfirmed,
                translationViewHintShown = prefs[Keys.TRANSLATION_VIEW_HINT_SHOWN]
                    ?: defaults.translationViewHintShown,
                aiPricePerMillion = prefs[Keys.AI_PRICE_PER_MILLION] ?: defaults.aiPricePerMillion,
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

    override suspend fun setMiddleTapAction(action: TapAction) {
        context.readingPreferencesStore.edit { it[Keys.MIDDLE_TAP_ACTION] = action.name }
    }

    override suspend fun setMiddleDoubleTapAction(action: TapAction) {
        context.readingPreferencesStore.edit { it[Keys.MIDDLE_DOUBLE_TAP_ACTION] = action.name }
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

    override suspend fun setSwipeDistanceDp(distanceDp: Float) {
        context.readingPreferencesStore.edit {
            it[Keys.SWIPE_DISTANCE_DP] = distanceDp.coerceIn(20f, 80f)
        }
    }

    override suspend fun setSwipeFlingVelocityDpPerSec(velocityDpPerSec: Float) {
        context.readingPreferencesStore.edit {
            it[Keys.SWIPE_FLING_VELOCITY_DP_PER_SEC] = velocityDpPerSec.coerceIn(200f, 1200f)
        }
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

    override suspend fun setCleanLevel(level: CleanLevel) {
        context.readingPreferencesStore.edit { prefs ->
            prefs[Keys.CLEAN_LEVEL] = level.name
            if (level != CleanLevel.CUSTOM) {
                prefs[Keys.CLEAN_TOGGLES] = CleanToggles.encode(CleanToggles.preset(level))
            }
        }
    }

    override suspend fun setCleanToggle(key: String, enabled: Boolean) {
        val entry = CleanToggles.ENTRIES.firstOrNull { it.key == key } ?: return
        context.readingPreferencesStore.edit { prefs ->
            val current = CleanToggles.decode(prefs[Keys.CLEAN_TOGGLES])
                ?: CleanToggles.preset(ReadingPreferences().cleanLevel)
            prefs[Keys.CLEAN_TOGGLES] = CleanToggles.encode(entry.set(current, enabled))
            prefs[Keys.CLEAN_LEVEL] = CleanLevel.CUSTOM.name
        }
    }

    override suspend fun setCleanProfile(level: CleanLevel, toggles: CleanToggles) {
        context.readingPreferencesStore.edit { prefs ->
            prefs[Keys.CLEAN_LEVEL] = level.name
            prefs[Keys.CLEAN_TOGGLES] = CleanToggles.encode(toggles)
        }
    }

    override suspend fun setComicDirection(direction: ComicDirection) {
        context.readingPreferencesStore.edit { it[Keys.COMIC_DIRECTION] = direction.name }
    }

    override suspend fun setComicDualPageCoverAlone(enabled: Boolean) {
        context.readingPreferencesStore.edit { it[Keys.COMIC_DUAL_PAGE_COVER_ALONE] = enabled }
    }

    override suspend fun setComicSpreadAutoDetect(enabled: Boolean) {
        context.readingPreferencesStore.edit { it[Keys.COMIC_SPREAD_AUTO_DETECT] = enabled }
    }

    override suspend fun setComicFitMode(mode: ComicFitMode) {
        context.readingPreferencesStore.edit { it[Keys.COMIC_FIT_MODE] = mode.name }
    }

    override suspend fun setComicScrollGapDp(gapDp: Int) {
        context.readingPreferencesStore.edit {
            it[Keys.COMIC_SCROLL_GAP_DP] = gapDp.coerceIn(0, 64)
        }
    }

    override suspend fun setAiEnabled(enabled: Boolean) {
        context.readingPreferencesStore.edit { it[Keys.AI_ENABLED] = enabled }
    }

    override suspend fun setAiProtocol(protocol: AiProtocol) {
        context.readingPreferencesStore.edit { it[Keys.AI_PROTOCOL] = protocol.name }
    }

    override suspend fun setAiBaseUrl(baseUrl: String) {
        context.readingPreferencesStore.edit { it[Keys.AI_BASE_URL] = baseUrl.trim() }
    }

    override suspend fun setAiModelGeneral(model: String) {
        context.readingPreferencesStore.edit { it[Keys.AI_MODEL_GENERAL] = model.trim() }
    }

    override suspend fun setAiModelTranslation(model: String) {
        context.readingPreferencesStore.edit { it[Keys.AI_MODEL_TRANSLATION] = model.trim() }
    }

    override suspend fun setAiModelVision(model: String) {
        context.readingPreferencesStore.edit { it[Keys.AI_MODEL_VISION] = model.trim() }
    }

    override suspend fun setAiTargetLang(targetLang: AiTargetLang) {
        context.readingPreferencesStore.edit { it[Keys.AI_TARGET_LANG] = targetLang.name }
    }

    override suspend fun setAiChapterRuleConfirmed(confirmed: Boolean) {
        context.readingPreferencesStore.edit { it[Keys.AI_CHAPTER_RULE_CONFIRMED] = confirmed }
    }

    override suspend fun setAiTranslationConfirmed(confirmed: Boolean) {
        context.readingPreferencesStore.edit { it[Keys.AI_TRANSLATION_CONFIRMED] = confirmed }
    }

    override suspend fun setTranslationViewHintShown(shown: Boolean) {
        context.readingPreferencesStore.edit { it[Keys.TRANSLATION_VIEW_HINT_SHOWN] = shown }
    }

    override suspend fun setAiPricePerMillion(price: Double) {
        context.readingPreferencesStore.edit { it[Keys.AI_PRICE_PER_MILLION] = price }
    }
}

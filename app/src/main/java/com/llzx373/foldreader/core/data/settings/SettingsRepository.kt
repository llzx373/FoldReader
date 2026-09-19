package com.llzx373.foldreader.core.data.settings

import com.llzx373.foldreader.core.format.clean.CleanLevel
import com.llzx373.foldreader.core.format.clean.CleanToggles
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
    suspend fun setAvoidCameraCutout(enabled: Boolean)
    suspend fun setPageTurnMode(mode: PageTurnMode)
    suspend fun setPageTurnHotspotRatio(ratio: Float)
    suspend fun setMiddleTapAction(action: TapAction)
    suspend fun setMiddleDoubleTapAction(action: TapAction)
    suspend fun setVolumeKeyPagingEnabled(enabled: Boolean)
    suspend fun setBrightnessGestureEnabled(enabled: Boolean)
    suspend fun setSwipeGestureEnabled(enabled: Boolean)
    suspend fun setSwipeDistanceDp(distanceDp: Float)
    suspend fun setSwipeFlingVelocityDpPerSec(velocityDpPerSec: Float)
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
    suspend fun setPanelScreenOff(enabled: Boolean)
    suspend fun setBookshelfGridView(gridView: Boolean)
    suspend fun setBookshelfSort(sort: BookshelfSort)
    suspend fun setCustomChapterRules(rules: List<String>)
    suspend fun setAdCleanRules(rules: List<String>)

    /**
     * 切换清理档位：细项同时重置为该档预设（切到 [CleanLevel.CUSTOM] 时保留现有细项）。
     */
    suspend fun setCleanLevel(level: CleanLevel)

    /** 逐项开关某个清理细项；档位随之落到 [CleanLevel.CUSTOM]。 */
    suspend fun setCleanToggle(key: String, enabled: Boolean)

    /**
     * 一次性写入档位与细项。备份恢复走这条——如果恢复时逐项写，
     * 每次写入都会把档位打成「自定义」，恢复完就与备份里的档位对不上了。
     */
    suspend fun setCleanProfile(level: CleanLevel, toggles: CleanToggles)
    suspend fun setComicDirection(direction: ComicDirection)
    suspend fun setComicDualPageCoverAlone(enabled: Boolean)
    suspend fun setComicSpreadAutoDetect(enabled: Boolean)
    suspend fun setComicFitMode(mode: ComicFitMode)
    suspend fun setComicScrollGapDp(gapDp: Int)
}

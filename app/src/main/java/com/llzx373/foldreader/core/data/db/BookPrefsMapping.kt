package com.llzx373.foldreader.core.data.db

import com.llzx373.foldreader.core.data.settings.AutoPageMode
import com.llzx373.foldreader.core.data.settings.DarkThemeOption
import com.llzx373.foldreader.core.data.settings.DualPageMode
import com.llzx373.foldreader.core.data.settings.PageTurnMode
import com.llzx373.foldreader.core.data.settings.ReadingPreferences
import com.llzx373.foldreader.core.data.settings.ReadingTheme
import com.llzx373.foldreader.core.data.settings.enumOrDefault

fun ReadingPreferences.toBookPrefsEntity(bookId: Long) = BookPrefsEntity(
    bookId = bookId,
    fontSizeSp = fontSizeSp,
    lineSpacingMultiplier = lineSpacingMultiplier,
    marginLevel = marginLevel,
    maxLineChars = maxLineChars,
    paragraphSpacingEm = paragraphSpacingEm,
    letterSpacingEm = letterSpacingEm,
    themeId = themeId.name,
    customBackgroundArgb = customBackgroundArgb,
    customTextArgb = customTextArgb,
    darkThemeOption = darkThemeOption.name,
    fontKey = fontKey,
    dualPageMode = dualPageMode.name,
    pageTurnMode = pageTurnMode.name,
    pageTurnHotspotRatio = pageTurnHotspotRatio,
    volumeKeyPagingEnabled = volumeKeyPagingEnabled,
    keepScreenOn = keepScreenOn,
    showChapterTitle = showChapterTitle,
    showPageProgress = showPageProgress,
    showPageNumber = showPageNumber,
    showBattery = showBattery,
    showTime = showTime,
    readerBrightness = readerBrightness,
    autoPageEnabled = autoPageEnabled,
    autoPageMode = autoPageMode.name,
    autoPageIntervalSec = autoPageIntervalSec,
    autoPageSpeedPx = autoPageSpeedPx,
    panelScreenOff = panelScreenOff,
    autoIndentEnabled = autoIndentEnabled,
)

/** 每书字段覆盖全局值，非书籍维度字段（手势开关、宽屏双页、书架与规则等）沿用 [global]。 */
fun BookPrefsEntity.toReadingPreferences(global: ReadingPreferences): ReadingPreferences =
    global.copy(
        fontSizeSp = fontSizeSp,
        lineSpacingMultiplier = lineSpacingMultiplier,
        marginLevel = marginLevel,
        maxLineChars = maxLineChars,
        paragraphSpacingEm = paragraphSpacingEm,
        letterSpacingEm = letterSpacingEm,
        themeId = enumOrDefault(themeId, ReadingTheme.GREEN),
        customBackgroundArgb = customBackgroundArgb,
        customTextArgb = customTextArgb,
        darkThemeOption = enumOrDefault(darkThemeOption, DarkThemeOption.SYSTEM),
        fontKey = fontKey,
        dualPageMode = enumOrDefault(dualPageMode, DualPageMode.AUTO),
        pageTurnMode = enumOrDefault(pageTurnMode, PageTurnMode.COVER),
        pageTurnHotspotRatio = pageTurnHotspotRatio,
        volumeKeyPagingEnabled = volumeKeyPagingEnabled,
        keepScreenOn = keepScreenOn,
        showChapterTitle = showChapterTitle,
        showPageProgress = showPageProgress,
        showPageNumber = showPageNumber,
        showBattery = showBattery,
        showTime = showTime,
        readerBrightness = readerBrightness,
        autoPageEnabled = autoPageEnabled,
        autoPageMode = enumOrDefault(autoPageMode, AutoPageMode.INTERVAL),
        autoPageIntervalSec = autoPageIntervalSec,
        autoPageSpeedPx = autoPageSpeedPx,
        panelScreenOff = panelScreenOff,
        autoIndentEnabled = autoIndentEnabled,
    )

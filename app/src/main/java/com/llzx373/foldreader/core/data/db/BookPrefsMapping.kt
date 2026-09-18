package com.llzx373.foldreader.core.data.db

import com.llzx373.foldreader.core.data.settings.AutoPageMode
import com.llzx373.foldreader.core.data.settings.ComicDirection
import com.llzx373.foldreader.core.data.settings.ComicFitMode
import com.llzx373.foldreader.core.data.settings.PageTurnMode
import com.llzx373.foldreader.core.data.settings.PdfReadingMode
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
    fontKey = fontKey,
    pageTurnMode = pageTurnMode.name,
    readerBrightness = readerBrightness,
    autoPageEnabled = autoPageEnabled,
    autoPageMode = autoPageMode.name,
    autoPageIntervalSec = autoPageIntervalSec,
    autoPageSpeedPx = autoPageSpeedPx,
    panelScreenOff = panelScreenOff,
    autoIndentEnabled = autoIndentEnabled,
    comicDirection = comicDirection.name,
    comicFitMode = comicFitMode.name,
    pdfReadingMode = pdfReadingMode?.name,
)

/**
 * 每书字段覆盖全局值；其余（手势/显示开关、宽屏双页、书架与规则等）沿用 [global]。
 *
 * 这里只该出现「会随书独立演化」的字段——交互开关与显示项一律走全局直通，
 * 否则设置页改完对已打开过的书不生效（每条书列都会盖住全局）。
 */
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
        fontKey = fontKey,
        pageTurnMode = enumOrDefault(pageTurnMode, PageTurnMode.COVER),
        readerBrightness = readerBrightness,
        autoPageEnabled = autoPageEnabled,
        autoPageMode = enumOrDefault(autoPageMode, AutoPageMode.INTERVAL),
        autoPageIntervalSec = autoPageIntervalSec,
        autoPageSpeedPx = autoPageSpeedPx,
        panelScreenOff = panelScreenOff,
        autoIndentEnabled = autoIndentEnabled,
        comicDirection = enumOrDefault(comicDirection, ComicDirection.LTR),
        comicFitMode = enumOrDefault(comicFitMode, ComicFitMode.FIT_PAGE),
        pdfReadingMode = pdfReadingMode?.let { enumOrDefault(it, PdfReadingMode.PAGED) },
    )

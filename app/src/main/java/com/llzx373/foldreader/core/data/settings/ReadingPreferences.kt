package com.llzx373.foldreader.core.data.settings

enum class ReadingTheme { GREEN, PARCHMENT, GRAY_WHITE, NIGHT, AMOLED, CUSTOM }

enum class PageTurnMode { SIMULATION, COVER, NONE, SCROLL }

data class ReadingPreferences(
    val fontSizeSp: Float = 18f,
    val lineSpacingMultiplier: Float = 1.5f,
    val themeId: ReadingTheme = ReadingTheme.GREEN,
    val pageTurnMode: PageTurnMode = PageTurnMode.COVER,
    val pageTurnHotspotRatio: Float = 0.3f,
    val volumeKeyPagingEnabled: Boolean = false,
    val keepScreenOn: Boolean = false,
    val showChapterTitle: Boolean = true,
    val showPageProgress: Boolean = true,
    val showBattery: Boolean = true,
    val showTime: Boolean = true,
    val readerBrightness: Float = -1f,
    val bookshelfGridView: Boolean = true,
)

internal inline fun <reified T : Enum<T>> enumOrDefault(name: String?, default: T): T =
    name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: default

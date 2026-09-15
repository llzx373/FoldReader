package com.llzx373.foldreader.core.data.settings

enum class ReadingTheme { GREEN, PARCHMENT, GRAY_WHITE, NIGHT, AMOLED, CUSTOM }

enum class PageTurnMode { SIMULATION, COVER, NONE, SCROLL }

enum class DarkThemeOption { SYSTEM, LIGHT, DARK }

enum class DualPageMode { AUTO, FORCE_DUAL, FORCE_SINGLE }

enum class AutoPageMode { INTERVAL, SCROLL }

data class ReadingPreferences(
    val fontSizeSp: Float = 18f,
    val lineSpacingMultiplier: Float = 1.5f,
    val marginLevel: Int = 1,
    val maxLineChars: Int = 40,
    val paragraphSpacingEm: Float = 0.4f,
    val letterSpacingEm: Float = 0f,
    val themeId: ReadingTheme = ReadingTheme.GREEN,
    val customBackgroundArgb: Int? = null,
    val customTextArgb: Int? = null,
    val darkThemeOption: DarkThemeOption = DarkThemeOption.SYSTEM,
    val fontKey: String = "default",
    val dualPageMode: DualPageMode = DualPageMode.AUTO,
    val wideScreenDualPage: Boolean = false,
    /** 非书籍维度（全局透传）：双页右栏整体下移一行，避让内屏摄像头。 */
    val dualRightPageDrop: Boolean = false,
    val pageTurnMode: PageTurnMode = PageTurnMode.COVER,
    /** 用户是否显式设置过翻页方式（false 时按姿态取默认：双页仿真、单页覆盖）。 */
    val pageTurnModeExplicit: Boolean = false,
    val pageTurnHotspotRatio: Float = 0.3f,
    val volumeKeyPagingEnabled: Boolean = false,
    val brightnessGestureEnabled: Boolean = true,
    val swipeGestureEnabled: Boolean = true,
    val keepScreenOn: Boolean = false,
    val showChapterTitle: Boolean = true,
    val showPageProgress: Boolean = true,
    val showPageNumber: Boolean = true,
    val showBattery: Boolean = true,
    val showTime: Boolean = true,
    val readerBrightness: Float = -1f,
    val autoPageEnabled: Boolean = false,
    val autoPageMode: AutoPageMode = AutoPageMode.INTERVAL,
    val autoPageIntervalSec: Int = 10,
    val autoPageSpeedPx: Float = 60f,
    val simulationDegraded: Boolean = false,
    val panelScreenOff: Boolean = false,
    val autoIndentEnabled: Boolean = true,
    val bookshelfGridView: Boolean = true,
    val customChapterRules: List<String> = emptyList(),
    val adCleanRules: List<String> = emptyList(),
)

private const val RULE_LIST_SEPARATOR = "\u001F"

internal fun encodeRuleList(rules: List<String>): String =
    rules.joinToString(RULE_LIST_SEPARATOR)

internal fun decodeRuleList(raw: String?): List<String> =
    raw?.takeIf { it.isNotEmpty() }?.split(RULE_LIST_SEPARATOR) ?: emptyList()

internal fun encodeCustomChapterRules(rules: List<String>): String = encodeRuleList(rules)

internal fun decodeCustomChapterRules(raw: String?): List<String> = decodeRuleList(raw)

internal inline fun <reified T : Enum<T>> enumOrDefault(name: String?, default: T): T =
    name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: default

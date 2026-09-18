package com.llzx373.foldreader.core.data.settings

enum class ReadingTheme { GREEN, PARCHMENT, GRAY_WHITE, NIGHT, AMOLED, CUSTOM }

enum class PageTurnMode { COVER, NONE, SCROLL }

enum class DarkThemeOption { SYSTEM, LIGHT, DARK }

enum class DualPageMode { AUTO, FORCE_DUAL, FORCE_SINGLE }

/** 漫画阅读方向：日漫从右往左（同一对页里低序号页放右边、点左侧翻到下一页）。 */
enum class ComicDirection { LTR, RTL }

/**
 * 漫画适应模式：决定一页以多大铺在屏幕上。
 * 文字小的漫画（尤其是扫描版）用「适应宽度」比「适应整页」可读得多。
 */
enum class ComicFitMode { FIT_PAGE, FIT_WIDTH, FIT_HEIGHT, ORIGINAL }

enum class AutoPageMode { INTERVAL, SCROLL }

/**
 * PDF 的阅读模式。
 *
 * null（不设置）表示"还没由用户定过"：这时按文档本身定——抽得出正文就文本模式，
 * 扫描件只能页式。用户一旦手动切过就以用户的选择为准（见 [ReadingPreferences.pdfReadingMode]）。
 */
enum class PdfReadingMode { PAGED, TEXT }

/** 书架排序方式：均为稳定排序，阅读行为本身不会改变列表顺序。 */
enum class BookshelfSort { IMPORT_TIME, TITLE, PROGRESS }

/**
 * 中间点击区可分配的动作。
 *
 * 左右点击区固定是翻页（保证跟手），中间区则允许把单击与双击各绑一个动作。
 * [TOGGLE_ZOOM] 只有能缩放的阅读器（页式）支持——文本阅读器没有页缩放，
 * 判定见 `com.llzx373.foldreader.feature.reader.supportsTapAction`。
 */
enum class TapAction { TOGGLE_MENU, PREVIOUS_PAGE, NEXT_PAGE, TOGGLE_BOOKMARK, TOGGLE_ZOOM, NONE }

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
    /** 非书籍维度（全局透传）：双页模式按 displayCutout 自动规避摄像头开孔位置。 */
    val avoidCameraCutout: Boolean = false,
    val pageTurnMode: PageTurnMode = PageTurnMode.COVER,
    val pageTurnHotspotRatio: Float = 0.3f,
    /**
     * 中间点击区的单击 / 双击动作。
     *
     * 单击默认仍是开关菜单（与旧行为一致）。双击默认切换缩放——只有页式阅读器支持它，
     * 文本阅读器因为执行不了会整层不挂（见 `supportsTapAction`），
     * 这样既能拿到「双击缩放」，又不会给文本阅读器的单击加上双击超时延迟。
     */
    val middleTapAction: TapAction = TapAction.TOGGLE_MENU,
    val middleDoubleTapAction: TapAction = TapAction.TOGGLE_ZOOM,
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
    val panelScreenOff: Boolean = false,
    val autoIndentEnabled: Boolean = true,
    val bookshelfGridView: Boolean = true,
    val bookshelfSort: BookshelfSort = BookshelfSort.IMPORT_TIME,
    val customChapterRules: List<String> = emptyList(),
    val adCleanRules: List<String> = emptyList(),
    /** 以下为漫画（[com.llzx373.foldreader.core.data.db.BookFormat.COMIC]）专用。 */
    val comicDirection: ComicDirection = ComicDirection.LTR,
    /** 首页（封面）单独成页，双页配对从第 2 页开始。 */
    val comicDualPageCoverAlone: Boolean = true,
    /** 横向宽图（宽高比超过阈值）自动独占整宽，用于还原跨页大图。 */
    val comicSpreadAutoDetect: Boolean = true,
    val comicFitMode: ComicFitMode = ComicFitMode.FIT_PAGE,
    /** 纵向连续滚动时的页间距（dp）；0 = 无缝拼接，条漫就是靠这个连成一整条。 */
    val comicScrollGapDp: Int = 0,
    /**
     * PDF 的阅读模式偏好；null = 由文档决定（见 [PdfReadingMode]）。
     *
     * 两种模式的阅读位置是**各自独立**的（页序号 ↔ 字符偏移不可互转），
     * 所以来回切换不会把位置算错——切回去还是各自上次停的地方。
     */
    val pdfReadingMode: PdfReadingMode? = null,
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

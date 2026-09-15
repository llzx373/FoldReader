package com.llzx373.foldreader.feature.reader

import android.graphics.Bitmap
import androidx.compose.ui.graphics.toArgb
import com.llzx373.foldreader.core.reader.HeaderFooterTexts
import com.llzx373.foldreader.core.reader.LayoutConfig
import com.llzx373.foldreader.core.reader.SpreadGeom

/**
 * 对页位图缓存键：左右页锚点 + 对页几何 + 排版配置 + 主题色 + 位图尺寸。
 * 任一维度变化（翻页、改字号/主题、姿态切换）都会得到新键，旧条目由 LRU 逐出。
 */
data class SpreadBitmapKey(
    val leftStart: Long,
    val rightStart: Long?,
    val widthPx: Int,
    val heightPx: Int,
    val geom: SpreadGeom,
    val config: LayoutConfig,
    val backgroundArgb: Int,
    val textArgb: Int,
    val accentArgb: Int,
    val density: Float,
    val scaledDensity: Float,
)

/** 纯 Kotlin LRU（access-order），抽出来便于单测逐出行为。 */
internal class LruMap<K, V>(private val capacity: Int) {
    private val map = LinkedHashMap<K, V>(capacity, 0.75f, true)

    @Synchronized
    fun get(key: K): V? = map[key]

    @Synchronized
    fun put(key: K, value: V) {
        map[key] = value
        while (map.size > capacity) {
            val it = map.entries.iterator()
            it.next()
            it.remove()
        }
    }

    @Synchronized
    fun clear() = map.clear()

    @Synchronized
    fun size(): Int = map.size
}

/** 翻页动画用对页位图缓存（容量 4：当前 + 前后各一 + 动画对）。 */
class SpreadBitmapCache(capacity: Int = 4) {
    private val map = LruMap<SpreadBitmapKey, Bitmap>(capacity)

    fun get(key: SpreadBitmapKey): Bitmap? = map.get(key)

    fun put(key: SpreadBitmapKey, bitmap: Bitmap) = map.put(key, bitmap)

    fun clear() = map.clear()
}

/** UI 侧供给的渲染上下文：几何/主题/密度 + 抓取时刻的页眉页脚文本与高亮。 */
class CurlRenderContext(
    val geom: SpreadGeom,
    val colors: ReaderColors,
    val density: Float,
    val scaledDensity: Float,
    val widthPx: Int,
    val heightPx: Int,
    val texts: () -> HeaderFooterTexts,
    val highlights: (PageSpread) -> Pair<List<TextRangeSpan>, List<TextRangeSpan>>,
) {
    fun keyFor(spread: PageSpread, config: LayoutConfig): SpreadBitmapKey = SpreadBitmapKey(
        leftStart = spread.left.charStart,
        rightStart = spread.right?.charStart,
        widthPx = widthPx,
        heightPx = heightPx,
        geom = geom,
        config = config,
        backgroundArgb = colors.background.toArgb(),
        textArgb = colors.text.toArgb(),
        accentArgb = colors.accent.toArgb(),
        density = density,
        scaledDensity = scaledDensity,
    )
}

/** 页眉页脚文本装配：与静止态 Compose 叠加层同一套可见性规则。 */
fun headerFooterTexts(
    dual: Boolean,
    chapterTitle: String?,
    bookTitle: String,
    pageNumberLabel: String?,
    progressText: String?,
    batteryText: String?,
    timeText: String?,
): HeaderFooterTexts {
    val footerStart = listOfNotNull(pageNumberLabel, progressText)
        .joinToString("  ")
        .ifEmpty { null }
    val footerEnd = listOfNotNull(batteryText, timeText)
        .joinToString("  ")
        .ifEmpty { null }
    return HeaderFooterTexts(
        topStart = chapterTitle,
        topEnd = if (dual) bookTitle else null,
        bottomStart = footerStart,
        bottomEnd = footerEnd,
    )
}

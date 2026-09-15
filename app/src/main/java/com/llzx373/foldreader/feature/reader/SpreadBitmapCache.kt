package com.llzx373.foldreader.feature.reader

import android.graphics.Bitmap
import androidx.compose.ui.graphics.toArgb
import com.llzx373.foldreader.core.reader.LayoutConfig
import com.llzx373.foldreader.core.reader.SpreadGeom

/**
 * 对页位图缓存键：左右页锚点 + 对页几何 + 排版配置 + 主题色 + 位图尺寸 + 内容版本。
 * 任一维度变化（翻页、改字号/主题、姿态切换）都会得到新键，旧条目由 LRU 逐出。
 * contentVersion 在划线标注/搜索高亮等页内内容变化时递增，避免翻页动画用过期位图。
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
    val contentVersion: Int = 0,
)

/** 纯 Kotlin LRU（access-order），抽出来便于单测逐出行为。 */
internal class LruMap<K, V>(private val capacity: Int) {
    private val map = LinkedHashMap<K, V>(capacity, 0.75f, true)

    @Synchronized
    fun get(key: K): V? = map[key]

    /** 写入并按键数逐出；返回被替换/逐出的键（调用方做附属记账）。 */
    @Synchronized
    fun put(key: K, value: V): List<K> {
        val evicted = mutableListOf<K>()
        if (map.containsKey(key)) evicted += key
        map[key] = value
        while (map.size > capacity) {
            val it = map.entries.iterator()
            evicted += it.next().key
            it.remove()
        }
        return evicted
    }

    @Synchronized
    fun remove(key: K): V? = map.remove(key)

    @Synchronized
    fun eldestKey(): K? = map.keys.firstOrNull()

    @Synchronized
    fun clear() = map.clear()

    @Synchronized
    fun size(): Int = map.size
}

/**
 * 翻页动画用对页位图缓存：按字节预算（默认 48MB）+ 条数上限（默认 6）双重逐出。
 * 大折叠展开态全宽位图单张可达 10MB+，按条数限速会在大幅面设备上超内存。
 * 泛型化取值类型便于 JVM 单测（Bitmap 不可在非 Robolectric 环境构造）。
 */
class SpreadBitmapCache<V>(
    private val byteBudget: Long = DEFAULT_BYTE_BUDGET,
    maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val sizeOf: (SpreadBitmapKey, V) -> Long = { key, _ -> entryBytes(key) },
) {
    private val map = LruMap<SpreadBitmapKey, V>(maxEntries)
    private val bytesByKey = HashMap<SpreadBitmapKey, Long>()
    private var totalBytes = 0L

    val totalBytesSnapshot: Long
        @Synchronized get() = totalBytes

    fun get(key: SpreadBitmapKey): V? = map.get(key)

    @Synchronized
    fun put(key: SpreadBitmapKey, value: V) {
        val evicted = map.put(key, value)
        totalBytes -= evicted.sumOf { bytesByKey.remove(it) ?: 0L }
        val bytes = sizeOf(key, value)
        bytesByKey[key] = bytes
        totalBytes += bytes
        while (totalBytes > byteBudget && map.size() > 1) {
            val eldest = map.eldestKey() ?: break
            if (eldest == key) break
            map.remove(eldest)
            totalBytes -= bytesByKey.remove(eldest) ?: 0L
        }
    }

    @Synchronized
    fun clear() {
        map.clear()
        bytesByKey.clear()
        totalBytes = 0L
    }

    @Synchronized
    fun size(): Int = map.size()

    companion object {
        const val DEFAULT_BYTE_BUDGET: Long = 48L * 1024 * 1024
        const val DEFAULT_MAX_ENTRIES: Int = 6

        /** 位图按 RGB_565 渲染（见 renderSpreadToBitmap），每像素 2 字节。 */
        fun entryBytes(key: SpreadBitmapKey): Long = key.widthPx.toLong() * key.heightPx * 2L
    }
}

/** UI 侧供给的渲染上下文：几何/主题/密度 + 抓取时刻的高亮。 */
class CurlRenderContext(
    val geom: SpreadGeom,
    val colors: ReaderColors,
    val density: Float,
    val scaledDensity: Float,
    val widthPx: Int,
    val heightPx: Int,
    val contentVersion: Int = 0,
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
        contentVersion = contentVersion,
    )
}

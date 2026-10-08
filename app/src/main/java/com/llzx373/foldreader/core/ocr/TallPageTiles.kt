package com.llzx373.foldreader.core.ocr

/**
 * 原生超长图（未预切页的单张长条漫，M31）的分片检测几何。
 *
 * 整页塞进 RT-DETR 的 640×640 方形输入，纵向压扁几十倍后气泡只剩几个像素，
 * 必漏检；所以超长页竖向切成约 2:1 的片分别检测，再把片内归一化坐标换算回
 * 整页归一化坐标。相邻片有重叠（气泡可能正好跨片缝）：去重按**气泡中心归属**——
 * 中心落在哪片的独占区就归哪片，重叠区里的重复检出自然丢弃。
 */
object TallPageTiles {

    /** 超长图判据：高 ≥ 宽 × [TALL_ASPECT]（横向跨页大图是宽图，不走这条路）。 */
    const val TALL_ASPECT = 3f

    /** 片高 ≈ 宽 × [TILE_ASPECT]：640 方形输入的压扁比例不超过既有整页路径太多。 */
    const val TILE_ASPECT = 2f

    /** 相邻片的重叠 = 片高 / [OVERLAP_DIVISOR]（盖住房间级气泡跨缝）。 */
    const val OVERLAP_DIVISOR = 4

    /** 单页最多切片数：再多的极端长图也只检前这么多片（超时与内存兜底）。 */
    const val MAX_TILES = 24

    fun isTall(widthPx: Int, heightPx: Int): Boolean =
        widthPx > 0 && heightPx.toFloat() >= widthPx * TALL_ASPECT

    /**
     * 竖向切片（页像素，半开区间 [top, bottom)）：片高 [tileHeightPx]、相邻重叠 [overlapPx]，
     * 全覆盖页高且不超过 [MAX_TILES] 片（超出截断——顶部内容优先）。
     * 页不高出一片时返回单整片（调用方通常已用 [isTall] 判过，这里是兜底）。
     */
    fun tileRanges(pageHeightPx: Int, tileHeightPx: Int, overlapPx: Int): List<IntRange> {
        require(pageHeightPx > 0 && tileHeightPx > 0) { "尺寸必须为正：$pageHeightPx / $tileHeightPx" }
        val tile = tileHeightPx.coerceAtMost(pageHeightPx)
        val overlap = overlapPx.coerceIn(0, tile / 2)
        if (pageHeightPx <= tile) return listOf(0 until pageHeightPx)
        val step = (tile - overlap).coerceAtLeast(1)
        val ranges = ArrayList<IntRange>()
        var top = 0
        while (ranges.size < MAX_TILES) {
            val bottom = (top + tile).coerceAtMost(pageHeightPx)
            ranges += top until bottom
            if (bottom >= pageHeightPx) break
            top += step
        }
        return ranges
    }

    /** 片内归一化坐标 → 整页归一化坐标（x 不变，y 按片位置与片高折算）。 */
    fun toPageRect(rect: OcrRect, tileTopPx: Int, tileHeightPx: Int, pageHeightPx: Int): OcrRect {
        require(tileHeightPx > 0 && pageHeightPx > 0)
        return OcrRect(
            rect.left,
            ((tileTopPx + rect.top * tileHeightPx) / pageHeightPx).coerceIn(0f, 1f),
            rect.right,
            ((tileTopPx + rect.bottom * tileHeightPx) / pageHeightPx).coerceIn(0f, 1f),
        )
    }

    /** 气泡（含文字行坐标）整体从片坐标系换算到页坐标系。 */
    fun toPageBubble(bubble: OcrBubble, tileTopPx: Int, tileHeightPx: Int, pageHeightPx: Int): OcrBubble =
        bubble.copy(
            rect = toPageRect(bubble.rect, tileTopPx, tileHeightPx, pageHeightPx),
            lines = bubble.lines.map { line ->
                line.copy(box = toPageRect(line.box, tileTopPx, tileHeightPx, pageHeightPx))
            },
        )

    /**
     * 合并各片检出（[tiles] = 片区间 → 已换算到页坐标系的气泡）：按中心 y 的独占区归属去重——
     * 第 i 片的独占区是 [top_i, top_{i+1})，末片到页底。跨缝气泡在重叠区被检出两次，
     * 中心只会落在一片的独占区里，另一份丢弃。返回未排序的并集（阅读序由调用方排）。
     */
    fun merge(tiles: List<Pair<IntRange, List<OcrBubble>>>, pageHeightPx: Int): List<OcrBubble> {
        val out = ArrayList<OcrBubble>()
        tiles.forEachIndexed { i, (range, bubbles) ->
            val zoneEnd = tiles.getOrNull(i + 1)?.first?.first ?: pageHeightPx
            for (bubble in bubbles) {
                val centerY = bubble.rect.centerY * pageHeightPx
                if (centerY >= range.first && centerY < zoneEnd) out += bubble
            }
        }
        return out
    }
}

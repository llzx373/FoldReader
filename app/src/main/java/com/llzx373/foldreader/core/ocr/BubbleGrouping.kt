package com.llzx373.foldreader.core.ocr

/**
 * 气泡归并（M22）：把 YOLO-seg 检出的气泡区域与 det/rec 检出的文字行配对。
 *
 * 纯 JVM：输入输出都是归一化坐标，合成坐标即可单测。
 */
object BubbleGrouping {

    /**
     * 文字行归并入气泡。
     *
     * 规则：行中心落在气泡内，或行面积 [lineOverlapThreshold] 以上与气泡重叠，即归属该气泡；
     * 同时命中多个气泡时取重叠最大者。归属后气泡框扩展为「气泡框 ∪ 行框」的并集
     * （检测框偶尔切掉边缘文字）。孤儿行（不在任何气泡内，如旁白框、拟声词）
     * 按 [OrphanPolicy] 处理。
     *
     * 返回的气泡按阅读序排序并重排 [OcrBubble.index]：日漫 RTL 为 右→左、上→下；
     * LTR 为 左→右、上→下（按行聚带后带内排序，避免高低错位的气泡串序）。
     */
    fun group(
        bubbleRects: List<Pair<OcrRect, Float>>,
        lines: List<OcrTextLine>,
        rtl: Boolean = true,
        lineOverlapThreshold: Float = 0.5f,
        orphanPolicy: OrphanPolicy = OrphanPolicy.AS_OWN_BUBBLE,
    ): List<OcrBubble> {
        val assigned = IntArray(lines.size) { -1 }
        for ((i, line) in lines.withIndex()) {
            var best = -1
            var bestOverlap = 0f
            for ((b, rect) in bubbleRects.withIndex()) {
                val contains = rect.first.containsPoint(line.box.centerX, line.box.centerY)
                val overlap = line.box.overlapRatio(rect.first)
                if ((contains || overlap >= lineOverlapThreshold) && overlap > bestOverlap) {
                    best = b
                    bestOverlap = if (contains && overlap == 0f) 1f else overlap
                }
            }
            assigned[i] = best
        }

        val bubbles = ArrayList<OcrBubble>()
        for ((b, rect) in bubbleRects.withIndex()) {
            val own = lines.filterIndexed { i, _ -> assigned[i] == b }
            if (own.isEmpty()) continue
            val region = own.fold(rect.first) { acc, line -> acc.union(line.box) }
            val confidence = minOf(rect.second, own.minOf { it.confidence })
            bubbles += OcrBubble(index = -1, rect = region, lines = sortLines(own, rtl), confidence = confidence)
        }
        if (orphanPolicy == OrphanPolicy.AS_OWN_BUBBLE) {
            for ((i, line) in lines.withIndex()) {
                if (assigned[i] != -1) continue
                bubbles += OcrBubble(index = -1, rect = line.box, lines = listOf(line), confidence = line.confidence)
            }
        }
        return sortBubbles(bubbles, rtl).mapIndexed { index, bubble -> bubble.copy(index = index) }
    }

    enum class OrphanPolicy {
        /** 孤儿行各自成为单行气泡（旁白/拟声词也能翻译）。 */
        AS_OWN_BUBBLE,

        /** 丢弃孤儿行。 */
        DROP,
    }

    /** 气泡阅读序：按垂直位置聚带（带高取气泡中位高度的比例），带内按方向排序。 */
    fun sortBubbles(bubbles: List<OcrBubble>, rtl: Boolean): List<OcrBubble> {
        if (bubbles.size <= 1) return bubbles
        val bandHeight = (bubbles.map { it.rect.height }.sorted()[bubbles.size / 2] * 1.5f)
            .coerceAtLeast(0.01f)
        val byY = bubbles.sortedBy { it.rect.centerY }
        val bands = ArrayList<MutableList<OcrBubble>>()
        var bandTop = Float.NaN
        for (bubble in byY) {
            if (bands.isEmpty() || bubble.rect.centerY - bandTop > bandHeight) {
                bands += mutableListOf(bubble)
                bandTop = bubble.rect.centerY
            } else {
                bands.last() += bubble
            }
        }
        return bands.flatMap { band ->
            band.sortedBy { if (rtl) -it.rect.centerX else it.rect.centerX }
        }
    }

    /** 气泡内文字行排序：多行时上→下，同行按方向（竖排漫画近似为单列，按 x 再排一次兜底）。 */
    fun sortLines(lines: List<OcrTextLine>, rtl: Boolean): List<OcrTextLine> =
        lines.sortedWith(
            compareBy({ it.box.top }, { if (rtl) -it.box.centerX else it.box.centerX }),
        )
}

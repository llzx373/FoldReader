package com.llzx373.foldreader.core.ocr

/**
 * OCR 文本层上的选字与搜索（M21 扫描 PDF；纯 JVM 可测）。
 *
 * 输入是每页的 [OcrPage]（缓存或现算），输出与 `PagedImageSource` 接缝同口径：
 * 选字给归一化并集框 + 拼接文字，搜索给页号 + 命中数 + 上下文 snippet。
 */
object OcrTextLayer {

    /**
     * 拖框选字：命中与选区矩形相交的文字行，返回（并集框, 按阅读序拼接的文字）。
     * 无命中返回 null（调用方退回自由框选）。
     */
    fun select(lines: List<OcrTextLine>, selection: OcrRect): Pair<OcrRect, String>? {
        val hit = lines.filter { it.box.overlapRatio(selection) > 0f || selection.overlapRatio(it.box) > 0f }
        if (hit.isEmpty()) return null
        val sorted = hit.sortedWith(compareBy({ it.box.top }, { it.box.left }))
        val union = sorted.map { it.box }.reduce { acc, box -> acc.union(box) }
        return union to sorted.joinToString("") { it.text }
    }

    /** 一页 OCR 文本的拼接（搜索用，行间不加分隔——中日文文本天然无空格）。 */
    fun pageText(page: OcrPage): String =
        page.lines.sortedWith(compareBy({ it.box.top }, { it.box.left })).joinToString("") { it.text }

    /**
     * 跨页搜索。[pages] 为已就绪的页（页号 → OCR 结果），按页升序返回命中。
     * 前 [snippetPages] 个命中页截取上下文（命中点前 20 后 30 字，与 PdfPagedSource 同口径）。
     */
    fun search(
        pages: Map<Int, OcrPage>,
        query: String,
        maxHits: Int = 200,
        snippetPages: Int = 40,
    ): List<Triple<Int, Int, String>> {
        if (query.isBlank()) return emptyList()
        val hits = ArrayList<Triple<Int, Int, String>>()
        for ((pageIndex, page) in pages.toSortedMap()) {
            val text = pageText(page)
            var count = 0
            var from = 0
            var snippet = ""
            while (true) {
                val at = text.indexOf(query, from, ignoreCase = true)
                if (at < 0) break
                count++
                if (snippet.isEmpty() && hits.size < snippetPages) {
                    val start = (at - 20).coerceAtLeast(0)
                    val end = (at + query.length + 30).coerceAtMost(text.length)
                    snippet = text.substring(start, end)
                }
                from = at + query.length
            }
            if (count > 0) {
                hits += Triple(pageIndex, count, snippet)
                if (hits.size >= maxHits) break
            }
        }
        return hits
    }
}

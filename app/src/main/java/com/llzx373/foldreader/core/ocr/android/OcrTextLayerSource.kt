package com.llzx373.foldreader.core.ocr.android

import com.llzx373.foldreader.core.ocr.OcrModelSpec
import com.llzx373.foldreader.core.ocr.OcrPage
import com.llzx373.foldreader.core.ocr.OcrRect
import com.llzx373.foldreader.core.ocr.OcrTextLayer
import com.llzx373.foldreader.core.ocr.PdfOcrStore
import com.llzx373.foldreader.core.paged.PagedImageSource
import com.llzx373.foldreader.core.paged.PagedPageImage
import com.llzx373.foldreader.core.paged.PagedSearchHit
import com.llzx373.foldreader.core.paged.PagedTextSelection
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * OCR 文本层装饰器（M21）：给扫描 PDF 补上「可搜索、可选字」的文本层。
 *
 * 包在 [PagedImageSource] 外面：有内嵌文本层的文档一律先走原生路径（更准更省），
 * 原生拿不到再退到 OCR。OCR 结果按页缓存到 [PdfOcrStore]（`filesDir/pdf_ocr/`），
 * 只在第一次用到该页时才识别；坐标与 v2.2 页内锚点同一归一化体系。
 *
 * [recSpecProvider] 每次调用现取（设置里改了识别语言即时生效）；返回 null 表示
 * 模型未就绪，此时行为与原来源完全一致（选字退自由框选、搜索空结果）。
 */
class OcrTextLayerSource(
    private val delegate: PagedImageSource,
    private val bookId: Long,
    private val store: PdfOcrStore,
    private val recSpecProvider: suspend () -> OcrModelSpec?,
    private val recognize: suspend (bitmap: android.graphics.Bitmap, recSpec: OcrModelSpec) -> OcrPage,
) : PagedImageSource by delegate {

    /** 页级识别串行（OcrEngine 内部还有一把全局锁，这里防同页重复识别）。 */
    private val ocrMutex = Mutex()
    private val memoryCache = java.util.LinkedHashMap<Int, OcrPage>()

    override suspend fun selectText(
        index: Int,
        startX: Float,
        startY: Float,
        stopX: Float,
        stopY: Float,
    ): PagedTextSelection? {
        delegate.selectText(index, startX, startY, stopX, stopY)?.let { return it }
        val page = ocrPage(index) ?: return null
        val selection = OcrRect(
            left = minOf(startX, stopX).coerceIn(0f, 1f),
            top = minOf(startY, stopY).coerceIn(0f, 1f),
            right = maxOf(startX, stopX).coerceIn(0f, 1f),
            bottom = maxOf(startY, stopY).coerceIn(0f, 1f),
        )
        val (union, text) = OcrTextLayer.select(page.lines, selection) ?: return null
        if (text.isBlank()) return null
        return PagedTextSelection(union.left, union.top, union.right, union.bottom, text)
    }

    override suspend fun search(
        query: String,
        maxHits: Int,
        snippetPages: Int,
    ): List<PagedSearchHit> {
        val native = delegate.search(query, maxHits, snippetPages)
        if (native.isNotEmpty()) return native
        val recSpec = recSpecProvider() ?: return emptyList()
        val hits = ArrayList<PagedSearchHit>()
        for (index in 0 until pageCount) {
            val page = ocrPage(index, recSpec) ?: continue
            val found = OcrTextLayer.search(mapOf(index to page), query, maxHits = 1)
            if (found.isNotEmpty()) {
                val (_, count, snippet) = found[0]
                hits += PagedSearchHit(page = index, count = count, snippet = snippet)
                if (hits.size >= maxHits) break
            }
        }
        return hits
    }

    /** 取一页的 OCR 结果：内存 → 磁盘 → 现算（渲染页位图识别后落盘）。 */
    private suspend fun ocrPage(index: Int, recSpec: OcrModelSpec? = null): OcrPage? {
        memoryCache[index]?.let { return it }
        store.load(bookId, index)?.let {
            memoryCache[index] = it
            return it
        }
        val spec = recSpec ?: recSpecProvider() ?: return null
        return ocrMutex.withLock {
            // 等锁期间可能已被另一个调用算好
            memoryCache[index]?.let { return@withLock it }
            store.load(bookId, index)?.let {
                memoryCache[index] = it
                return@withLock it
            }
            val bitmap = (delegate.loadPage(index, OCR_RENDER_WIDTH, OCR_RENDER_HEIGHT)
                as? PagedPageImage.Still)?.bitmap ?: return@withLock null
            val page = runCatching { recognize(bitmap, spec) }.getOrNull() ?: return@withLock null
            store.save(bookId, index, page)
            memoryCache[index] = page
            trimMemoryCache()
            page
        }
    }

    /** 内存缓存限页数（文本层很小，但长文档全量搜索时会逐页累积）。 */
    private fun trimMemoryCache() {
        while (memoryCache.size > MEMORY_CACHE_PAGES) {
            val oldest = memoryCache.keys.firstOrNull { it != -1 } ?: break
            memoryCache.remove(oldest)
        }
    }

    companion object {
        /** OCR 取图的目标尺寸：det 内部还会再缩到 960 长边，渲染给足余量即可。 */
        private const val OCR_RENDER_WIDTH = 1280
        private const val OCR_RENDER_HEIGHT = 1920
        private const val MEMORY_CACHE_PAGES = 24
    }
}

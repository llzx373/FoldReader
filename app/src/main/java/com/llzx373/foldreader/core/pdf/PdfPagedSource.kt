package com.llzx373.foldreader.core.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.net.Uri
import android.util.Size
import androidx.pdf.PdfDocument
import androidx.pdf.PdfPasswordException
import androidx.pdf.SandboxedPdfLoader
import androidx.pdf.content.PdfPageTextContent
import com.llzx373.foldreader.core.paged.PagedImageSource
import com.llzx373.foldreader.core.paged.PagedPageImage
import com.llzx373.foldreader.core.paged.PagedSearchHit
import com.llzx373.foldreader.core.paged.PagedSourcePasswordRequired
import com.llzx373.foldreader.core.paged.PagedTextSelection
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.roundToInt

/**
 * PDF 的页位图来源。
 *
 * 渲染交给 `androidx.pdf` 的沙箱文档服务：真正的解析与光栅化发生在**独立进程**里
 * （`android:isolatedProcess="true"` 的 bound service，内部就是平台的 `PdfRenderer`）。
 * PDF 是攻击面最大的消费级格式之一，而我们打开的是用户从外部目录给的文件，所以这层隔离
 * 不是可选项。
 *
 * 代价与约束：
 * - 客户端拿到的是远程文档对象，**一次只能开一个 `BitmapSource`**，所以渲染必须串行；
 * - 每页 `BitmapSource` 用完必须关（官方修过 `close()` 的 RemoteException）；
 * - 位图是 `ARGB_8888`（平台 `PdfRenderer` 的限制），比漫画那条路的 `RGB_565` 占一倍内存。
 */
class PdfPagedSource private constructor(
    private val document: PdfDocument,
) : PagedImageSource {

    override val pageCount: Int get() = document.pageCount

    /** 沙箱侧一次只允许开一页，这里串行化；预取是并发的，不锁会随机拿不到图。 */
    private val renderLock = Mutex()

    /**
     * 每页的点尺寸（宽 to 高）。宽高比探测、渲染尺寸、页内选字都要它，缓存一份省掉重复 IPC。
     */
    private var pageSizesPt: Array<Pair<Float, Float>>? = null

    /**
     * 尺寸缓存的锁。这个类的方法都会被并发调用（预取、缩略图、选字），
     * 不加锁时首个缓存为空，多路会各自发一次全量 `getPageInfos` 并互相覆盖。
     */
    private val pageSizesLock = Mutex()

    private suspend fun pageSizes(): Array<Pair<Float, Float>>? = pageSizesLock.withLock {
        pageSizesPt?.let { return@withLock it }
        if (pageCount == 0) return@withLock null
        val infos = runCatching { document.getPageInfos(0 until pageCount) }.getOrNull()
            ?: return@withLock null
        val sizes = Array(pageCount) { 0f to 0f }
        for (info in infos) {
            if (info.pageNum in sizes.indices) {
                sizes[info.pageNum] = info.width.toFloat() to info.height.toFloat()
            }
        }
        pageSizesPt = sizes
        sizes
    }

    /**
     * 批量取页面尺寸算宽高比。PDF 的页面尺寸来自 `PageInfo`（单位是点），
     * 一次 `getPageInfos(全范围)` 就够——逐页发 IPC 在几百页的文档上会很慢。
     */
    override suspend fun probeAspects(): FloatArray {
        val aspects = FloatArray(pageCount)
        val sizes = pageSizes() ?: return aspects
        for (i in aspects.indices) {
            val (w, h) = sizes[i]
            if (w > 0f && h > 0f) aspects[i] = w / h
        }
        return aspects
    }

    /**
     * 页内选字：把归一化两点换算成点坐标交给文档，再把结果的并集框换回归一化。
     *
     * 选字是用户动作，一次调用最多两次 IPC（页尺寸通常已缓存），拖框期间不会每帧回源。
     * 拿不到文字层（扫描件）或文档报错时返回 null，调用方保留原始矩形框选。
     */
    override suspend fun selectText(
        index: Int,
        startX: Float,
        startY: Float,
        stopX: Float,
        stopY: Float,
    ): PagedTextSelection? {
        if (index !in 0 until pageCount) return null
        val (widthPt, heightPt) = pageSizes()?.getOrNull(index) ?: return null
        if (widthPt <= 0f || heightPt <= 0f) return null

        val selection = runCatching {
            document.getSelectionBounds(
                index,
                PointF(startX * widthPt, startY * heightPt),
                PointF(stopX * widthPt, stopY * heightPt),
            )
        }.getOrNull() ?: return null

        val texts = selection.selectedContents.filterIsInstance<PdfPageTextContent>()
        val text = texts.joinToString("") { it.text }
        val rects = texts.flatMap { it.bounds }
        if (rects.isEmpty() || text.isBlank()) return null

        val left = rects.minOf { it.left } / widthPt
        val top = rects.minOf { it.top } / heightPt
        val right = rects.maxOf { it.right } / widthPt
        val bottom = rects.maxOf { it.bottom } / heightPt
        return PagedTextSelection(
            left = left.coerceIn(0f, 1f),
            top = top.coerceIn(0f, 1f),
            right = right.coerceIn(0f, 1f),
            bottom = bottom.coerceIn(0f, 1f),
            text = text,
        )
    }

    override suspend fun loadPage(
        index: Int,
        targetWidth: Int,
        targetHeight: Int,
    ): PagedPageImage? = render(index, targetWidth, targetHeight)?.let { PagedPageImage.Still(it) }

    override suspend fun loadThumbnail(index: Int, width: Int, height: Int): Bitmap? =
        render(index, width, height)

    private suspend fun render(index: Int, width: Int, height: Int): Bitmap? {
        if (index !in 0 until pageCount) return null
        val size = renderSize(index, width, height)
        return renderLock.withLock {
            runCatching {
                document.getPageBitmapSource(index).use { source ->
                    source.getBitmap(size, null)
                }
            }.getOrNull()
        }
    }

    /**
     * 渲染尺寸必须按页面自身宽高比缩放进请求的槽位。
     *
     * 沙箱侧是按 `scaledPageSizePx` **独立缩放 x/y** 的（`RenderingUtils.getTransformationMatrix`），
     * 传进去什么尺寸页面就被拉伸成什么尺寸。而阅读器给的是「单页可用槽位」（窗口尺寸，
     * 与页面比例无关），直接传会两错并犯：页面被拉变形，且位图尺寸恒等于槽位，
     * 于是四种适应模式在绘制端全塌成同一档——点哪个都不变大变小。
     */
    private suspend fun renderSize(index: Int, width: Int, height: Int): Size {
        val fallback = Size(width.coerceAtLeast(1), height.coerceAtLeast(1))
        val (pageW, pageH) = pageSizes()?.getOrNull(index) ?: return fallback
        if (pageW <= 0f || pageH <= 0f) return fallback
        val scale = minOf(width / pageW, height / pageH)
        if (scale <= 0f) return fallback
        return Size(
            (pageW * scale).roundToInt().coerceAtLeast(1),
            (pageH * scale).roundToInt().coerceAtLeast(1),
        )
    }

    override fun close() {
        runCatching { document.close() }
    }

    /**
     * 逐页搜索文本层。
     *
     * 命中只给出字符下标（[androidx.pdf.content.PageMatchBounds] 不带文字），
     * 所以上下文要按需再取一次该页文本；只给前 [snippetPages] 页取，避免一次搜索打出一串 IPC。
     */
    override suspend fun search(
        query: String,
        maxHits: Int,
        snippetPages: Int,
    ): List<PagedSearchHit> {
        if (query.isBlank() || pageCount == 0) return emptyList()
        val matches = runCatching { document.searchDocument(query, 0 until pageCount) }.getOrNull()
            ?: return emptyList()

        val hits = ArrayList<PagedSearchHit>()
        for (i in 0 until matches.size()) {
            if (hits.size >= maxHits) break
            val page = matches.keyAt(i)
            val pageMatches = matches.valueAt(i).orEmpty()
            if (pageMatches.isEmpty()) continue
            val snippet = if (hits.size < snippetPages) {
                snippetAround(page, pageMatches.first().textStartIndex, query.length)
            } else {
                ""
            }
            hits += PagedSearchHit(page = page, count = pageMatches.size, snippet = snippet)
        }
        return hits
    }

    /** 命中处附近的上下文：前 20 字后 30 字，压掉换行与多余空白。 */
    private suspend fun snippetAround(page: Int, startIndex: Int, matchLength: Int): String {
        val content = runCatching { document.getPageContent(page) }.getOrNull() ?: return ""
        val text = content.textContents.joinToString("") { it.text }
        if (text.isEmpty()) return ""
        val from = (startIndex - SNIPPET_LEAD).coerceIn(0, text.length)
        val to = (startIndex + matchLength + SNIPPET_TAIL).coerceIn(from, text.length)
        return text.substring(from, to).replace(WHITESPACE_RUN, " ").trim()
    }

    companion object {
        private const val SNIPPET_LEAD = 20
        private const val SNIPPET_TAIL = 30
        private val WHITESPACE_RUN = Regex("\\s+")

        /**
         * 打开文档。[password] 为空表示未加密或首次尝试；
         * 需要密码/密码错误时抛 [PagedSourcePasswordRequired]，由阅读器弹密码框重试。
         */
        suspend fun open(context: Context, uri: Uri, password: String?): PdfPagedSource {
            val loader = SandboxedPdfLoader(context)
            val document = try {
                loader.openDocument(uri, password)
            } catch (e: PdfPasswordException) {
                throw PagedSourcePasswordRequired(e.message ?: "密码不正确")
            }
            return PdfPagedSource(document)
        }
    }
}

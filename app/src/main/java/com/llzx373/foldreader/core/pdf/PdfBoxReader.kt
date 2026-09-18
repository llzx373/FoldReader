package com.llzx373.foldreader.core.pdf

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.llzx373.foldreader.core.comic.ComicCoverWriter
import com.llzx373.foldreader.core.format.CoverImage
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.rendering.PDFRenderer
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import kotlin.math.min
import kotlin.math.sqrt

/** PDF 内嵌书签树里的一条：这是文档自带的「目录」，不是用户自己的「书签」。 */
data class PdfOutlineEntry(
    val title: String,
    /** 跳转目标页序号（0 基）；解析不出来时 null（仍要展示，只是点不动）。 */
    val pageIndex: Int?,
    val depth: Int,
)

data class PdfDocumentInfo(
    val pageCount: Int,
    val title: String?,
    val author: String?,
    val subject: String?,
    /** PDF 的 Keywords 字段（逗号/分号分隔的标签）；对应 books.subjects。 */
    val keywords: String?,
    val outline: List<PdfOutlineEntry>,
    /** 文档权限是否允许提取内容；false 时界面不该给出"提取文本"入口。 */
    val canExtractContent: Boolean,
    /** 首页渲染出的封面（已编码为 JPEG 字节）；渲染失败为 null。 */
    val cover: CoverImage?,
    /**
     * 文本模式当电子书读的抽取结果（只是元信息；全文已写进调用方给的临时文件）。
     * 扫描件、未要求抽取、或权限不允许时为 null。
     */
    val text: PdfExtractedText? = null,
)

/**
 * 文本抽取结果。
 *
 * [pageStartOffsets] 存在的意义只有一个：把**页式目录**的页锚点换算成文本模式下的字符锚点
 * （同一份压平产物里，"第 5 页第一个字"的偏移是确定的事实，不是两套坐标互相假装等价）。
 */
data class PdfExtractedText(
    val charCount: Long,
    val pageStartOffsets: LongArray,
)

/** 扫描件判定：平均每页字符数低于这个值就不当电子书（渲染照常可用）。 */
private const val MIN_CHARS_PER_PAGE = 50

/** 分页哨兵：一次遍历同时拿到全文与每页边界，比逐页调用 PDFTextStripper 快得多。 */
private const val PAGE_MARK = "\u0000\u0001PAGE\u0001\u0000"

/**
 * 用 PdfBox 只读地解析 PDF：元数据、内嵌目录树、首页封面、内容提取权限。
 *
 * 与渲染路径（`PdfPagedSource` 走 androidx.pdf 的沙箱进程）刻意分开：
 * 这些都是**一次性**的解析工作，不需要渲染服务那套隔离；而且 PdfBox 能顺手把
 * 目录树这种平台 API 拿不到的东西取出来。
 *
 * 全部是 CPU 密集的本地解析，**只能在后台线程调用**。任何一步失败都返回 null，
 * 由调用方决定后续（预热保持「待解析」角标）。
 */
object PdfBoxReader {

    /** 封面按宽度收敛到 ~480px（与漫画封面一致），并限制总像素避免超大页面撑爆内存。 */
    private const val COVER_TARGET_WIDTH = 480
    private const val COVER_MAX_PIXELS = 1_500_000
    private const val COVER_MIN_SCALE = 0.05f

    /** 目录树的规模上限：不信任文件内容，防止畸形 PDF 把递归与列表撑爆。 */
    private const val MAX_OUTLINE_ENTRIES = 2000
    private const val MAX_OUTLINE_DEPTH = 12

    /**
     * [textTarget] 非空且文档允许提取内容时，额外把正文按页抽出来写进这个文件
     * （文本型 PDF 当电子书读用）。抽出结果不够"像文本"（扫描件）时返回的 text 为 null，
     * 但文件可能已经被写过一部分——调用方需要自己决定留不留。
     */
    fun read(context: Context, uriKey: String, textTarget: File? = null): PdfDocumentInfo? =
        runCatching { withDocument(context, uriKey) { extract(it, textTarget) } }.getOrNull()

    private fun <T> withDocument(context: Context, uriKey: String, block: (PDDocument) -> T): T {
        localPdfFile(uriKey)?.let { file ->
            // 本地文件走内存映射，不整本读进堆
            return PDDocument.load(file).use(block)
        }
        // SAF 等只能拿到流：让 PdfBox 落到缓存目录的临时文件里，避免大文件撑爆内存。
        // 注意这是"给解析器看的副本"，解析完即删，不改变"只登记源位置"的约定。
        val stream = context.contentResolver.openInputStream(Uri.parse(uriKey))
            ?: error("无法读取文件")
        return stream.use { input ->
            val usage = MemoryUsageSetting.setupTempFileOnly().setTempDir(context.cacheDir)
            PDDocument.load(input, null, usage).use(block)
        }
    }

    /** `file://` 的 PDF 才直接给文件；`content://` 返回 null 由调用方走流式加载。 */
    private fun localPdfFile(uriKey: String): File? {
        val uri = Uri.parse(uriKey)
        if (uri.scheme != "file") return null
        val path = uri.path ?: return null
        val file = File(path)
        return file.takeIf { it.isFile }
    }

    private fun extract(document: PDDocument, textTarget: File?): PdfDocumentInfo {
        val info = document.documentInformation
        val canExtract = runCatching {
            document.currentAccessPermission.canExtractContent()
        }.getOrDefault(true)
        return PdfDocumentInfo(
            pageCount = document.numberOfPages,
            title = info.title.cleanOrNull(),
            author = info.author.cleanOrNull(),
            subject = info.subject.cleanOrNull(),
            keywords = info.keywords.cleanOrNull(),
            outline = extractOutline(document),
            canExtractContent = canExtract,
            cover = renderCover(document),
            text = if (canExtract) textTarget?.let { extractText(document, it) } else null,
        )
    }

    /**
     * 把每页正文按顺序写进 [target]，并记录每页的起始字符偏移。
     *
     * 扫描件（几乎抽不到字）返回 null：调用方据此不生成压平产物，界面也据此说明
     * 「这是扫描件」而不是给一个点了没反应的"提取文本"。
     */
    private fun extractText(document: PDDocument, target: File): PdfExtractedText? {
        val pageCount = document.numberOfPages
        if (pageCount == 0) return null
        val stripper = PDFTextStripper().apply {
            // 不按坐标排序：默认的阅读顺序更接近人读的顺序，坐标排序会把多栏排版切碎
            sortByPosition = false
            pageStart = ""
            pageEnd = PAGE_MARK
        }
        val raw = runCatching { stripper.getText(document) }.getOrNull() ?: return null
        val pages = raw.split(PAGE_MARK)
        val starts = LongArray(pageCount)
        var offset = 0L
        target.bufferedWriter(Charsets.UTF_8).use { writer ->
            for (index in 0 until pageCount) {
                if (index > 0) {
                    writer.write("\n\n")
                    offset += 2
                }
                starts[index] = offset
                val page = pages.getOrNull(index)?.trim().orEmpty()
                writer.write(page)
                offset += page.length
            }
        }
        if (offset < pageCount.toLong() * MIN_CHARS_PER_PAGE) return null
        return PdfExtractedText(charCount = offset, pageStartOffsets = starts)
    }

    /**
     * 目录树是链表结构（`children()` 是一个可迭代视图），这里按层级展平成带 depth 的列表，
     * 和 EPUB 的目录共用同一套展示模型。
     */
    private fun extractOutline(document: PDDocument): List<PdfOutlineEntry> {
        val root = document.documentCatalog.documentOutline ?: return emptyList()
        val out = ArrayList<PdfOutlineEntry>()
        walkOutline(document, root.children(), depth = 0, out = out)
        return out
    }

    private fun walkOutline(
        document: PDDocument,
        items: Iterable<PDOutlineItem>,
        depth: Int,
        out: MutableList<PdfOutlineEntry>,
    ) {
        if (depth > MAX_OUTLINE_DEPTH) return
        for (item in items) {
            if (out.size >= MAX_OUTLINE_ENTRIES) return
            item.title.cleanOrNull()?.let { title ->
                out += PdfOutlineEntry(title, pageIndexOf(document, item), depth)
            }
            if (item.hasChildren()) {
                walkOutline(document, item.children(), depth + 1, out)
            }
        }
    }

    /**
     * 目录项指向的页序号。`getDestination()` 本身会抛 IOException（目标坏掉时），
     * 所以整条链路上都在 runCatching 里；解析不出来就返回 null（该项仍展示，只是点不动）。
     */
    private fun pageIndexOf(document: PDDocument, item: PDOutlineItem): Int? {
        val destination = runCatching { item.destination }.getOrNull() ?: return null
        val pageDestination = destination as? PDPageDestination ?: return null
        // retrievePageNumber 会把"按页对象引用"的目标解析成序号；失败退回到已存的序号
        val resolved = runCatching { pageDestination.retrievePageNumber() }.getOrNull()
        if (resolved != null && resolved >= 0) return resolved
        val stored = runCatching { pageDestination.pageNumber }.getOrNull()
        return stored?.takeIf { it >= 0 }
    }

    private fun renderCover(document: PDDocument): CoverImage? {
        if (document.numberOfPages == 0) return null
        val page = runCatching { document.getPage(0) }.getOrNull() ?: return null
        val box = page.mediaBox
        val widthPt = box.width
        val heightPt = box.height
        if (widthPt <= 0f || heightPt <= 0f) return null
        // 既想宽度够用，又不能让海报级页面渲染出上百 MB 的位图
        val scale = min(COVER_TARGET_WIDTH / widthPt, sqrt(COVER_MAX_PIXELS / (widthPt * heightPt)))
            .coerceAtLeast(COVER_MIN_SCALE)
        val bitmap = runCatching { PDFRenderer(document).renderImage(0, scale) }.getOrNull()
            ?: return null
        return ComicCoverWriter.encode(bitmap)
    }

    private fun String?.cleanOrNull(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
}

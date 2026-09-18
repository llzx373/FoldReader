package com.llzx373.foldreader.core.paged

import android.graphics.Bitmap
import java.io.Closeable

/**
 * 页式阅读的内容来源——漫画容器与 PDF 文档的唯一接缝。
 *
 * 阅读器只认「第 N 页、要多大、给我一张能画的图」，至于那一页是压缩包里的一个图片条目、
 * 还是 PDF 渲染出来的位图，由实现决定。这样沉浸外壳、手势、双页、滚动、缩放、缩略图网格
 * 这些页式阅读共有的东西只写一份。
 *
 * 实现要求：所有方法都可能被并发调用（阅读器会并行预取相邻页），
 * 内部需要自己串行化（PDF 的 `BitmapSource` 一次只能开一页）。
 */
interface PagedImageSource : Closeable {

    val pageCount: Int

    /**
     * 一次性探测各页宽高比（w/h），下标即页序号，0 表示未知。
     *
     * 存在的意义是让实现能**批量**拿尺寸：PDF 一次 `getPageInfos(range)` 就够，
     * 逐页发 IPC 在几百页的文档上会很慢。默认不提供（阅读器退回占位比例）。
     */
    suspend fun probeAspects(): FloatArray = FloatArray(0)

    /** 按目标像素尺寸出图；失败返回 null（界面据此显示「无法显示此页」）。 */
    suspend fun loadPage(index: Int, targetWidth: Int, targetHeight: Int): PagedPageImage?

    /** 缩略图：一律要静图（动画页取首帧即可），尺寸远小于正文页。 */
    suspend fun loadThumbnail(index: Int, width: Int, height: Int): Bitmap?

    /**
     * 页内选字：给定页内两点的**归一化**坐标，返回文档算出的选区（并集矩形 + 选中文字）。
     *
     * 交给文档自己算而不是取文本回来自行匹配，是因为阅读顺序、连字、跨栏这些只有文档知道；
     * 参数用归一化是为了不让「点」这种单位漏到接缝外面来。
     *
     * 没有文字层（漫画、扫描版 PDF）返回 null，页内选区随即退化为自由矩形框选——
     * 扫描件本来就没字可选，给一个点了没反应的选字入口不如直接框。
     * 默认实现返回 null，所以图片类来源零改动。
     */
    suspend fun selectText(
        index: Int,
        startX: Float,
        startY: Float,
        stopX: Float,
        stopY: Float,
    ): PagedTextSelection? = null

    /**
     * 逐页搜索文本层。返回按页升序的结果；没有文字层的来源（漫画）返回空列表。
     *
     * [snippetPages] 限制为多少页取上下文——命中位置只有字符下标，要拿上下文得再取一次页文本
     * （每页一次 IPC），所以只给前若干页取，其余只给页号与命中数。
     */
    suspend fun search(
        query: String,
        maxHits: Int = 200,
        snippetPages: Int = 40,
    ): List<PagedSearchHit> = emptyList()

    override fun close()
}

/**
 * 页内选字的计算结果。
 *
 * 矩形是**归一化并集框**（0..1，页左上为原点），与书签/高亮的锚点同一套坐标。
 * 多行选区取的是并集——高亮因此是一整块矩形而不是逐行的几段，与「区域是一个框」的模型一致。
 */
data class PagedTextSelection(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val text: String,
)

/** 一条页式搜索结果：一页一条。[count] 是该页命中处数，[snippet] 是命中附近的文字（可能为空）。 */
data class PagedSearchHit(
    val page: Int,
    val count: Int,
    val snippet: String = "",
)

/** 页式阅读里各格式的能力差异：菜单据此隐藏对该格式无意义的开关。 */
data class PagedReaderFeatures(
    /** 左右阅读方向（日漫 RTL）。PDF 没有这个概念。 */
    val rtl: Boolean = true,
    /** 双页配对（封面单独成页 / 宽图独占整宽）。漫画常用，PDF 少见。 */
    val spreadPairing: Boolean = true,
    /** 纵向连续滚动可无缝拼接（条漫）。PDF 页面自带留白，无缝没有意义。 */
    val seamlessScroll: Boolean = true,
    /**
     * 文档有可提取的正文（文本型 PDF）——界面据此给出「切到文字模式」。
     * 注意这与"能不能读"无关：扫描件照样翻页，只是取不出字。
     */
    val textLayer: Boolean = false,
    /** 已确认是扫描件（正文抽不出来）：界面要说明，而不是留一个点了没反应的开关。 */
    val scanned: Boolean = false,
)

/**
 * 打开内容来源时发现文档需要密码（或密码不对）。
 *
 * 单独一个领域异常是为了让阅读器能据此弹密码框——底层 PdfRenderer 抛的是
 * `SecurityException`，直接冒到界面上只会变成一句看不懂的错误。
 */
class PagedSourcePasswordRequired(message: String = "该文档需要密码") : Exception(message)


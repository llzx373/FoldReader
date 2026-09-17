package com.llzx373.foldreader.core.format

import android.net.Uri
import java.nio.charset.Charset

data class BookMeta(
    val title: String,
    val author: String?,
    val encoding: String,
    val byteSize: Long,
    /** 以下为 EPUB 等富元数据格式的扩展字段（TXT/FB2 留空）。 */
    val description: String? = null,
    val publisher: String? = null,
    val language: String? = null,
    val pubDate: String? = null,
    val subjects: List<String> = emptyList(),
    val identifier: String? = null,
    val seriesName: String? = null,
    val seriesIndex: String? = null,
)

/** 提取出的封面图片字节与扩展名（jpg/png/gif/webp，已过魔数或扩展名校验）。 */
data class CoverImage(val bytes: ByteArray, val extension: String) {
    override fun equals(other: Any?): Boolean =
        other is CoverImage && bytes.contentEquals(other.bytes) && extension == other.extension
    override fun hashCode(): Int = bytes.contentHashCode() * 31 + extension.hashCode()
}

/** 纸书页码：label 为原文页码标记（可能是 "12"/"xii" 等任意串），charOffset 为该页在压平流中的起点。 */
data class PageLabel(val label: String, val charOffset: Long)

/** 当前偏移所处的纸书页：最后一个 charOffset <= [offset] 的页码（labels 须按偏移升序）。 */
fun pageLabelAt(labels: List<PageLabel>, offset: Long): PageLabel? =
    labels.lastOrNull { it.charOffset <= offset }

data class Chapter(
    val title: String,
    val charStart: Long,
    val charEnd: Long,
    /**
     * 目录层级，0 为顶层。只用于展示缩进 —— 不影响阅读、跳转或进度（锚点始终是字符偏移）。
     * TXT 启发式章节与无目录书的兜底都是 0。
     */
    val depth: Int = 0,
)

interface BookContent {
    val charCount: Long

    /**
     * [charCount] 是否已最终确定。实时索引（异步建偏移索引）期间为 false，
     * charCount 随索引推进增长；此时"读到 charCount"不代表文末。
     */
    val isCharCountFinal: Boolean get() = true

    /** 等待 [charCount] 超过 [offset]；索引封口（仍不够 = 真文末）或失败时返回/抛出。 */
    suspend fun awaitCharsAbove(offset: Long) {}

    suspend fun read(range: LongRange): String
}

interface BookParser {
    suspend fun parseMeta(uri: Uri): BookMeta
    suspend fun parseChapters(uri: Uri, charsetOverride: Charset? = null): List<Chapter>
    suspend fun openContent(uri: Uri, charsetOverride: Charset? = null): BookContent

    /** 提取内嵌封面（仅 EPUB 等格式实现）；无封面或条目非图片时返回 null。 */
    suspend fun extractCover(uri: Uri): CoverImage? = null

    /** 正文起点（EPUB landmarks/guide "text" 等）；无则 null，首次打开从 0 开始。 */
    suspend fun preferredStartOffset(uri: Uri): Long? = null

    /** 纸书页码序列（EPUB page-list），按 charOffset 升序；无 page-list 返回 null。 */
    suspend fun pageLabels(uri: Uri): List<PageLabel>? = null

    /** 样式/结构 span（EPUB 压平规范 v3 起记录）；TXT/FB2 恒 null。 */
    suspend fun textSpans(uri: Uri): List<TextSpan>? = null

    /** 内嵌图片的本地文件（EPUB 压平时抽取到 converted/<hash>.images/）；无此书/此图返回 null。 */
    suspend fun imageFile(uri: Uri, imagePath: String): java.io.File? = null

    /**
     * 预热：把「首次打开才需要做」的重活提前做掉（EPUB/FB2 的整本压平）。
     * 默认无操作——TXT 没有这一步，它的偏移索引在打开时边建边读。
     *
     * 调用方必须容忍失败：预热只是加速，失败等同于没预热，首次打开会照常重来。
     */
    suspend fun prewarm(uri: Uri) {}
}

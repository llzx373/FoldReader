package com.llzx373.foldreader.core.comic

import java.io.File

/** 漫画容器类型。 */
enum class ComicContainer { ZIP, RAR, TAR, SEVEN_ZIP, FOLDER }

/**
 * 一页图片的原始字节来源。
 * 一律用 String 表示文档 Uri（不用 `android.net.Uri`），让容器层与页序逻辑留在纯 JVM 可测范围。
 */
sealed interface ComicPageSource {
    /** 随机访问容器（zip）里的条目路径。 */
    data class Entry(val key: String) : ComicPageSource

    /** 已落盘的本地文件（解压缓存 / 复制到本地 / 目录漫画的 file:// 场景）。 */
    data class Local(val file: File) : ComicPageSource

    /** 外部 SAF 文档（未复制到本地的目录漫画）。 */
    data class Document(val uri: String) : ComicPageSource
}

/** 漫画中的一页。[name] 仅用于展示与调试，[index] 是 0 基页序号。 */
data class ComicPage(val index: Int, val name: String, val source: ComicPageSource)

/**
 * 漫画容器：把「目录 / zip / rar / tar / 7z」统一成「按页序号取图」。
 *
 * 读取是阻塞 IO，调用方负责在 IO 调度器上执行（与项目里其它解析器一致）。
 */
interface ComicArchive : AutoCloseable {

    val container: ComicContainer

    /** 全部页，已过滤噪音与非图片条目、按自然序排好。 */
    val pages: List<ComicPage>

    /** 读取一页原始字节；超过 [MAX_PAGE_BYTES] 抛 [java.io.IOException]。实现带字节 LRU。 */
    fun readPage(index: Int): ByteArray

    /** 只读图片头拿像素尺寸（不做完整解码）；认不出返回 null。 */
    fun pageSize(index: Int): IntArray?

    override fun close()

    companion object {
        /** 单页原始字节上限：超过基本不是正常漫画页，拒绝而不是把内存吃穿。 */
        const val MAX_PAGE_BYTES = 64 * 1024 * 1024

        /** 探测尺寸时最多读取的字节数（各类图片头都在几十到几百字节内）。 */
        const val HEADER_PROBE_BYTES = 64 * 1024
    }
}

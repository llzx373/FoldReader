package com.llzx373.foldreader.core.ocr

import java.io.File

/**
 * 扫描 PDF 的 OCR 文本层缓存（M21）。
 *
 * 目录结构：`filesDir/pdf_ocr/<bookId>/<pageIndex>.ocr.json`（结构见 [OcrPageCodec]）。
 * 与译本（TranslationStore）同一约定：纯 JVM、公开方法 `@Synchronized` 文件级串行、
 * 损坏/版本不符即视为未缓存。换模型或管线升级后由 [OcrPageCodec.OCR_VERSION] 整体失效，
 * 不需要手动清理。
 */
class PdfOcrStore(private val pdfOcrDir: File) {

    private fun pageFile(bookId: Long, pageIndex: Int): File =
        File(File(pdfOcrDir, bookId.toString()), "$pageIndex.ocr.json")

    @Synchronized
    fun load(bookId: Long, pageIndex: Int): OcrPage? {
        val file = pageFile(bookId, pageIndex)
        if (!file.isFile) return null
        return runCatching { OcrPageCodec.decodePage(file.readText()) }.getOrNull()
    }

    @Synchronized
    fun save(bookId: Long, pageIndex: Int, page: OcrPage) {
        val file = pageFile(bookId, pageIndex)
        file.parentFile?.mkdirs()
        file.writeText(OcrPageCodec.encodePage(page))
    }

    /** 该书是否已有任何一页的 OCR 缓存（界面判「已建文本层」用）。 */
    fun hasAny(bookId: Long): Boolean =
        File(pdfOcrDir, bookId.toString()).listFiles()?.any { it.name.endsWith(".ocr.json") } ?: false

    /** 删书连带 / 「清除全部 AI 数据」。 */
    @Synchronized
    fun deleteBook(bookId: Long) {
        File(pdfOcrDir, bookId.toString()).deleteRecursively()
    }

    @Synchronized
    fun deleteAll() {
        pdfOcrDir.listFiles()?.forEach { it.deleteRecursively() }
    }
}

package com.llzx373.foldreader.core.translate

import com.llzx373.foldreader.core.ocr.OcrBubble
import com.llzx373.foldreader.core.ocr.OcrPageCodec
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 一页气泡译文的落盘内容（`<pageIndex>.<lang>.json`）：下标即气泡序号（OcrBubble.index）。 */
@Serializable
data class TranslatedBubblePage(
    val texts: List<String>,
)

/**
 * 漫画翻译的页级产物存储（M22，docs/AI功能需求与实施.md 6.2）。
 *
 * 目录结构：`filesDir/comic_translate/<bookId>/`
 * - `<pageIndex>.ocr.json`：OCR/气泡检测结果（气泡框 + 文字行 + 置信度，
 *   编解码见 `core/ocr/OcrPageCodec`）——**换模型或改提示词重译时不重跑 OCR**；
 * - `<pageIndex>.<lang>.json`：该页该目标语言的逐气泡译文（[TranslatedBubblePage]）。
 *
 * 与 TranslationStore 同一约定：纯 JVM、公开方法 `@Synchronized`、损坏即视为未缓存。
 */
class ComicTranslationStore(private val comicTranslateDir: File) {

    private val json = Json { ignoreUnknownKeys = true }

    private fun bookDir(bookId: Long): File = File(comicTranslateDir, bookId.toString())
    private fun ocrFile(bookId: Long, pageIndex: Int): File =
        File(bookDir(bookId), "$pageIndex.ocr.json")
    private fun translationFile(bookId: Long, lang: String, pageIndex: Int): File =
        File(bookDir(bookId), "$pageIndex.$lang.json")

    @Synchronized
    fun saveOcr(bookId: Long, pageIndex: Int, bubbles: List<OcrBubble>) {
        val file = ocrFile(bookId, pageIndex)
        file.parentFile?.mkdirs()
        file.writeText(OcrPageCodec.encodeBubbles(bubbles))
    }

    /** 读取一页的气泡缓存；未缓存 / 损坏 / 版本不符返回 null（调用方重新识别）。 */
    @Synchronized
    fun loadOcr(bookId: Long, pageIndex: Int): List<OcrBubble>? {
        val file = ocrFile(bookId, pageIndex)
        if (!file.isFile) return null
        return runCatching { OcrPageCodec.decodeBubbles(file.readText()) }.getOrNull()
    }

    @Synchronized
    fun saveTranslation(bookId: Long, lang: String, pageIndex: Int, texts: List<String>) {
        val file = translationFile(bookId, lang, pageIndex)
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(TranslatedBubblePage(texts)))
    }

    /** 读取一页译文；未译 / 损坏返回 null。 */
    @Synchronized
    fun loadTranslation(bookId: Long, lang: String, pageIndex: Int): TranslatedBubblePage? {
        val file = translationFile(bookId, lang, pageIndex)
        if (!file.isFile) return null
        return runCatching { json.decodeFromString<TranslatedBubblePage>(file.readText()) }.getOrNull()
    }

    /** 该书该语言已译页数（进度展示）。 */
    fun translatedPages(bookId: Long, lang: String): Int =
        bookDir(bookId).listFiles()?.count { it.name.endsWith(".$lang.json") } ?: 0

    /** 该书该语言是否已有已译页。 */
    fun hasAny(bookId: Long, lang: String): Boolean = translatedPages(bookId, lang) > 0

    /** 单页译文作废（重译前）；OCR 缓存保留。 */
    @Synchronized
    fun deleteTranslation(bookId: Long, lang: String, pageIndex: Int) {
        translationFile(bookId, lang, pageIndex).delete()
    }

    /** 删书连带 / 「清除全部 AI 数据」。 */
    @Synchronized
    fun deleteBook(bookId: Long) {
        bookDir(bookId).deleteRecursively()
    }

    @Synchronized
    fun deleteAll() {
        comicTranslateDir.listFiles()?.forEach { it.deleteRecursively() }
    }
}

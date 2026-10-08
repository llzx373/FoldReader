package com.llzx373.foldreader.core.format

import com.llzx373.foldreader.core.comic.ComicContainers
import com.llzx373.foldreader.core.data.db.BookFormat

/**
 * 电子书格式识别：magic bytes 优先，扩展名/MIME 兜底。
 * 支持 TXT / EPUB / FB2（裸 XML 与 .fb2.zip）/ HTML / DOCX / PDF / Markdown / 漫画容器；
 * 识别不出返回 null（上层按 TXT 处理，保持既有行为）。
 */
object FormatDetector {

    const val EPUB_MIME_TYPE = "application/epub+zip"
    const val PDF_MIME_TYPE = "application/pdf"
    const val DOCX_MIME_TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

    /** HTML 的两种常见 MIME（xhtml+xml 走同一条 HTML 管线）。 */
    val HTML_MIME_TYPES = setOf("text/html", "application/xhtml+xml")

    /**
     * Markdown 的两种常见 MIME。没有魔数可判（纯文本开头可以是任何字符），
     * 只能靠扩展名/MIME 兜底——所以判定放在所有魔数检查之后、TXT 之前。
     */
    val MARKDOWN_MIME_TYPES = setOf("text/markdown", "text/x-markdown")

    /**
     * FB2 没有注册的正式 MIME，两种写法都遇到过。
     *
     * 很多来源（下载目录、第三方文件管理器）压根不给 .fb2 后缀或把它报成这两种之一，
     * 所以判定与「是否可导入」的白名单都要认它，否则清单里广告了 FB2 却在别处看不到。
     */
    val FB2_MIME_TYPES = setOf("application/x-fictionbook+xml", "application/x-fictionbook")

    fun detect(displayName: String?, mimeType: String?, head: ByteArray): BookFormat? {
        if (isEpub(head)) return BookFormat.EPUB
        if (isFb2Zip(head)) return BookFormat.FB2
        if (isFb2(head)) return BookFormat.FB2
        // HTML 判定放在 FB2 之后（FB2 根标签 FictionBook 会先命中，不误伤）、漫画之前
        if (isHtml(head)) return BookFormat.HTML
        // DOCX 判定放在 EPUB/FB2 之后（它们也是 zip）、漫画之前
        // （把 docx 当漫画解只会报「压缩包内没有可显示的图片」）
        if (isDocx(head, displayName, mimeType)) return BookFormat.DOCX
        // PDF 的魔数是唯一的，也不与任何容器冲突，放在漫画判定之前
        if (isPdf(head)) return BookFormat.PDF
        val name = displayName?.lowercase()
        val ext = name?.substringAfterLast('.', "")
        return when {
            ext == "epub" || mimeType == EPUB_MIME_TYPE -> BookFormat.EPUB
            ext == "fb2" || name?.endsWith(".fb2.zip") == true || mimeType in FB2_MIME_TYPES ->
                BookFormat.FB2
            ext == "html" || ext == "htm" || mimeType in HTML_MIME_TYPES -> BookFormat.HTML
            ext == "docx" || mimeType == DOCX_MIME_TYPE -> BookFormat.DOCX
            ext == "pdf" || mimeType == PDF_MIME_TYPE -> BookFormat.PDF
            // Markdown 没有魔数：只认扩展名/MIME，且须在 TXT 兜底之前
            // （text/plain MIME 的 .md 文件不该落到 TXT 管线）
            ext == "md" || ext == "markdown" || mimeType in MARKDOWN_MIME_TYPES -> BookFormat.MARKDOWN
            // 漫画判定放在 EPUB/FB2 之后（它们也是 zip）、TXT 之前
            // （把 zip/rar 当纯文本解只会得到乱码）
            ComicContainers.detect(displayName, mimeType, head) != null -> BookFormat.COMIC
            ext == "txt" || mimeType == "text/plain" -> BookFormat.TXT
            else -> null
        }
    }

    fun isZip(head: ByteArray): Boolean = ComicContainers.isZip(head)

    /** EPUB = zip，且第一个条目是未压缩的 `mimetype`，内容为 `application/epub+zip`。 */
    fun isEpub(head: ByteArray): Boolean {
        // local file header: 签名(4) 版本(2) 标志(2) 压缩方式(2) 时间(2) 日期(2)
        // CRC(4) 压缩大小(4) 原始大小(4) 文件名长度(2) 扩展域长度(2) 文件名 …
        val name = firstZipEntryName(head) ?: return false
        if (name != "mimetype") return false
        // 规范要求 mimetype 条目 stored（不压缩），内容紧跟在文件名与扩展域之后
        val mime = EPUB_MIME_TYPE.toByteArray(Charsets.US_ASCII)
        val nameLength = u16(head, 26)
        val extraLength = u16(head, 28)
        val dataStart = 30 + nameLength + extraLength
        if (dataStart + mime.size > head.size) return false
        return head.copyOfRange(dataStart, dataStart + mime.size).contentEquals(mime)
    }

    /** `.fb2.zip` 变体：zip 第一个条目名以 `.fb2` 结尾。 */
    fun isFb2Zip(head: ByteArray): Boolean =
        firstZipEntryName(head)?.lowercase()?.endsWith(".fb2") == true

    /** 裸 FB2：BOM/空白/XML 声明/注释/DOCTYPE 之后根标签为 `FictionBook`。 */
    fun isFb2(head: ByteArray): Boolean {
        if (isZip(head)) return false
        // Latin-1 视图下探标签 ASCII 骨架（UTF-8 多字节不影响标签判定；UTF-16 源靠扩展名兜底）
        val s = String(head, Charsets.ISO_8859_1)
        val limit = minOf(s.length, 4096)
        var i = 0
        while (i < limit) {
            when {
                s[i].isWhitespace() -> i++
                // UTF-8 BOM 的 Latin-1 视图
                s.startsWith("ï»¿", i) -> i += 3
                s.startsWith("<?xml", i) -> {
                    val end = s.indexOf("?>", i + 5)
                    if (end < 0) return false
                    i = end + 2
                }
                s.startsWith("<!--", i) -> {
                    val end = s.indexOf("-->", i + 4)
                    if (end < 0) return false
                    i = end + 3
                }
                s.startsWith("<!DOCTYPE", i, ignoreCase = true) -> {
                    val end = s.indexOf('>', i + 9)
                    if (end < 0) return false
                    i = end + 1
                }
                else -> return s.startsWith("<FictionBook", i)
            }
        }
        return false
    }

    /**
     * DOCX（OOXML zip）：首条目名以 `word/` 开头即可认定（EPUB/FB2 已先行排除）；
     * 首条目是 `[Content_Types].xml`（Word 产物的典型顺序）时需扩展名/MIME 佐证，
     * 以免把其他 OOXML 包（xlsx/pptx 首条目也常是它）错认成文档书。
     */
    fun isDocx(head: ByteArray, displayName: String?, mimeType: String?): Boolean {
        val firstEntry = firstZipEntryName(head) ?: return false
        if (firstEntry.startsWith("word/")) return true
        if (firstEntry != "[Content_Types].xml") return false
        val ext = displayName?.lowercase()?.substringAfterLast('.', "")
        return ext == "docx" || mimeType == DOCX_MIME_TYPE
    }

    /**
     * 裸 HTML：BOM/空白/XML 声明/注释之后为 `<!doctype html`（大小写不敏感，HTML4 的
     * `<!DOCTYPE html PUBLIC …>` 也被这个前缀覆盖）或根标签 `<html`。
     * 遇到非 html 的 DOCTYPE 直接否决（那是别的 XML 方言）；UTF-16 源与 FB2 一样靠扩展名兜底。
     * 前缀命中后要求下一个字符不是标签名延续（空白/`>`/`/`/结尾），否则 `<htmlx>` 之类会被误收。
     */
    fun isHtml(head: ByteArray): Boolean {
        if (isZip(head)) return false
        // Latin-1 视图下探标签 ASCII 骨架（同 isFb2 的跳过逻辑）
        val s = String(head, Charsets.ISO_8859_1)
        val limit = minOf(s.length, 4096)
        var i = 0
        while (i < limit) {
            when {
                s[i].isWhitespace() -> i++
                // UTF-8 BOM 的 Latin-1 视图
                s.startsWith("ï»¿", i) -> i += 3
                s.startsWith("<?xml", i) -> {
                    val end = s.indexOf("?>", i + 5)
                    if (end < 0) return false
                    i = end + 2
                }
                s.startsWith("<!--", i) -> {
                    val end = s.indexOf("-->", i + 4)
                    if (end < 0) return false
                    i = end + 3
                }
                s.startsWith("<!DOCTYPE", i, ignoreCase = true) ->
                    return s.matchesTagPrefix(i, "<!DOCTYPE html")
                else -> return s.matchesTagPrefix(i, "<html")
            }
        }
        return false
    }

    /** 大小写不敏感的前缀匹配，且前缀之后的字符须为标签名终止符（空白/`>`/`/`/字符串结尾）。 */
    private fun String.matchesTagPrefix(index: Int, prefix: String): Boolean {
        if (!startsWith(prefix, index, ignoreCase = true)) return false
        val next = getOrNull(index + prefix.length) ?: return true
        return next.isWhitespace() || next == '>' || next == '/'
    }

    fun isPdf(head: ByteArray): Boolean {
        val magic = "%PDF-".toByteArray(Charsets.US_ASCII)
        if (head.size < magic.size) return false
        return head.copyOfRange(0, magic.size).contentEquals(magic)
    }

    private fun firstZipEntryName(head: ByteArray): String? {
        if (head.size < 30 || !isZip(head)) return null
        val nameLength = u16(head, 26)
        val nameStart = 30
        if (nameStart + nameLength > head.size) return null
        return String(head, nameStart, nameLength, Charsets.UTF_8)
    }

    private fun u16(head: ByteArray, offset: Int): Int =
        (head[offset].toInt() and 0xFF) or ((head[offset + 1].toInt() and 0xFF) shl 8)
}

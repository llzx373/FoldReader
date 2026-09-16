package com.llzx373.foldreader.core.format

import com.llzx373.foldreader.core.data.db.BookFormat

/**
 * 电子书格式识别：magic bytes 优先，扩展名/MIME 兜底。
 * 识别不出返回 null（上层按 TXT 处理，保持既有行为）；
 * PDF 用 [isPdf] 单独判定，由上层报「暂不支持」。
 */
object FormatDetector {

    const val EPUB_MIME_TYPE = "application/epub+zip"

    fun detect(displayName: String?, mimeType: String?, head: ByteArray): BookFormat? {
        if (isEpub(head)) return BookFormat.EPUB
        if (isFb2Zip(head)) return BookFormat.FB2
        if (isFb2(head)) return BookFormat.FB2
        val name = displayName?.lowercase()
        val ext = name?.substringAfterLast('.', "")
        return when {
            ext == "epub" || mimeType == EPUB_MIME_TYPE -> BookFormat.EPUB
            ext == "fb2" || name?.endsWith(".fb2.zip") == true -> BookFormat.FB2
            ext == "txt" || mimeType == "text/plain" -> BookFormat.TXT
            else -> null
        }
    }

    fun isZip(head: ByteArray): Boolean =
        head.size >= 4 && head[0] == 0x50.toByte() && head[1] == 0x4B.toByte() &&
            head[2] == 0x03.toByte() && head[3] == 0x04.toByte()

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

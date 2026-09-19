package com.llzx373.foldreader.core.format

import com.llzx373.foldreader.core.comic.ComicContainers

/**
 * 书名/MIME 是否像可导入的内容（TXT/EPUB/FB2 + 漫画容器）。
 * 文件浏览器与目录批量导入共用，避免两处过滤规则漂移。
 */
fun isSupportedBookName(name: String, mimeType: String?): Boolean {
    val lower = name.lowercase()
    when (lower.substringAfterLast('.', "")) {
        "txt", "epub", "fb2" -> return true
        "pdf" -> return true
        // 漫画：专用扩展名 + 通用压缩包（按真实字节判容器，识别不出再退回文本）
        "cbz", "cbr", "cbt", "cb7" -> return true
        "zip", "rar", "7z", "tar" -> return true
    }
    return lower.endsWith(".fb2.zip") ||
        mimeType == "text/plain" ||
        mimeType == FormatDetector.EPUB_MIME_TYPE ||
        mimeType == FormatDetector.PDF_MIME_TYPE ||
        mimeType in FormatDetector.FB2_MIME_TYPES ||
        ComicContainers.fromMime(mimeType) != null
}

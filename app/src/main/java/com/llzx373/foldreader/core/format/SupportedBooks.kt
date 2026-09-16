package com.llzx373.foldreader.core.format

/**
 * 书名/MIME 是否像可导入的电子书（TXT/EPUB/FB2，含 .fb2.zip）。
 * 文件浏览器与目录批量导入共用，避免两处过滤规则漂移。
 */
fun isSupportedBookName(name: String, mimeType: String?): Boolean =
    when (name.substringAfterLast('.', "").lowercase()) {
        "txt", "epub", "fb2" -> true
        else -> name.lowercase().endsWith(".fb2.zip") ||
            mimeType == "text/plain" || mimeType == "application/epub+zip"
    }

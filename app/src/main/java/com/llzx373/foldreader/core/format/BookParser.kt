package com.llzx373.foldreader.core.format

import android.net.Uri
import java.nio.charset.Charset

data class BookMeta(
    val title: String,
    val author: String?,
    val encoding: String,
    val byteSize: Long,
)

data class Chapter(
    val title: String,
    val charStart: Long,
    val charEnd: Long,
)

interface BookContent {
    val charCount: Long
    suspend fun read(range: LongRange): String
}

interface BookParser {
    suspend fun parseMeta(uri: Uri): BookMeta
    suspend fun parseChapters(uri: Uri, charsetOverride: Charset? = null): List<Chapter>
    suspend fun openContent(uri: Uri, charsetOverride: Charset? = null): BookContent
}

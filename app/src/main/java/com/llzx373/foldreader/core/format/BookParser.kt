package com.llzx373.foldreader.core.format

import android.net.Uri

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
    suspend fun parseChapters(uri: Uri): List<Chapter>
    fun openContent(uri: Uri): BookContent
}

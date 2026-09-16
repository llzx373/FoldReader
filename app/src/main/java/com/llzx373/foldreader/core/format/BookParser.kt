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
}

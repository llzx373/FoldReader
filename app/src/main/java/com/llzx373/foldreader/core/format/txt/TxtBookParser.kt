package com.llzx373.foldreader.core.format.txt

import android.content.Context
import android.net.Uri
import com.llzx373.foldreader.core.format.BookContent
import com.llzx373.foldreader.core.format.BookMeta
import com.llzx373.foldreader.core.format.BookParser
import com.llzx373.foldreader.core.format.Chapter
import com.llzx373.foldreader.core.format.EncodingDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TxtBookParser(
    private val context: Context,
) : BookParser {

    override suspend fun parseMeta(uri: Uri): BookMeta = withContext(Dispatchers.IO) {
        UriChannels.open(context, uri).use { channel ->
            val sample = UriChannels.readHead(channel, EncodingDetector.SAMPLE_SIZE)
            val detection = EncodingDetector.detect(sample)
            BookMeta(
                title = guessTitle(uri),
                author = null,
                encoding = detection.charset.name(),
                byteSize = channel.size(),
            )
        }
    }

    override suspend fun parseChapters(uri: Uri): List<Chapter> = index(uri).chapters

    suspend fun index(uri: Uri): TxtIndex = withContext(Dispatchers.IO) {
        UriChannels.open(context, uri).use { channel ->
            val sample = UriChannels.readHead(channel, EncodingDetector.SAMPLE_SIZE)
            val detection = EncodingDetector.detect(sample)
            val bom = EncodingDetector.bomLengthOf(sample)
            TxtIndexer.index(channel, detection.charset, bomLength = bom)
        }
    }

    override fun openContent(uri: Uri): BookContent {
        val channel = UriChannels.open(context, uri)
        try {
            val sample = UriChannels.readHead(channel, EncodingDetector.SAMPLE_SIZE)
            val detection = EncodingDetector.detect(sample)
            val bom = EncodingDetector.bomLengthOf(sample)
            val index = TxtIndexer.index(channel, detection.charset, bomLength = bom)
            return TxtBookContent(channel, detection.charset, index.offsetIndex, index.charCount)
        } catch (t: Throwable) {
            channel.close()
            throw t
        }
    }

    private fun guessTitle(uri: Uri): String =
        UriChannels.displayName(context, uri)
            ?.substringBeforeLast('.')
            ?.takeIf { it.isNotBlank() }
            ?: "未知书名"
}

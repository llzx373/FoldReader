package com.llzx373.foldreader.core.format.txt

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.llzx373.foldreader.core.format.BookContent
import com.llzx373.foldreader.core.format.BookMeta
import com.llzx373.foldreader.core.format.BookParser
import com.llzx373.foldreader.core.format.Chapter
import com.llzx373.foldreader.core.format.EncodingDetector
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TxtBookParser(
    private val context: Context,
) : BookParser {

    override suspend fun parseMeta(uri: Uri): BookMeta = withContext(Dispatchers.IO) {
        openChannel(uri).use { channel ->
            val sample = readHead(channel, EncodingDetector.SAMPLE_SIZE)
            val detection = EncodingDetector.detect(sample)
            BookMeta(
                title = guessTitle(uri),
                author = null,
                encoding = detection.charset.name(),
                byteSize = channel.size(),
            )
        }
    }

    override suspend fun parseChapters(uri: Uri): List<Chapter> = withContext(Dispatchers.IO) {
        val (content, index) = indexUri(uri)
        content.close()
        index.chapters
    }

    override fun openContent(uri: Uri): BookContent = indexUri(uri).first

    private fun indexUri(uri: Uri): Pair<TxtBookContent, TxtIndex> {
        val channel = openChannel(uri)
        try {
            val sample = readHead(channel, EncodingDetector.SAMPLE_SIZE)
            val detection = EncodingDetector.detect(sample)
            val bom = EncodingDetector.bomLengthOf(sample)
            val index = TxtIndexer.index(channel, detection.charset, bomLength = bom)
            return TxtBookContent(channel, detection.charset, index.offsetIndex, index.charCount) to index
        } catch (t: Throwable) {
            channel.close()
            throw t
        }
    }

    private fun openChannel(uri: Uri): SeekableByteChannel {
        if (uri.scheme == null || uri.scheme == ContentResolver.SCHEME_FILE) {
            val path = uri.path ?: throw IOException("无效的文件 Uri: $uri")
            return RandomAccessFile(path, "r").channel
        }
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IOException("无法打开文件: $uri")
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).channel
    }

    private fun readHead(channel: SeekableByteChannel, size: Int): ByteArray {
        channel.position(0)
        val buffer = ByteBuffer.allocate(size)
        while (buffer.hasRemaining() && channel.read(buffer) >= 0) Unit
        return buffer.array().copyOf(buffer.position())
    }

    private fun guessTitle(uri: Uri): String {
        val segment = uri.lastPathSegment ?: return "未知书名"
        return segment.substringAfterLast('/').substringBeforeLast('.').ifBlank { "未知书名" }
    }
}

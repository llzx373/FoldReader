package com.llzx373.foldreader.core.format.txt

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel

internal object UriChannels {

    fun open(context: Context, uri: Uri): SeekableByteChannel {
        if (uri.scheme == null || uri.scheme == ContentResolver.SCHEME_FILE) {
            val path = uri.path ?: throw IOException("无效的文件 Uri: $uri")
            return RandomAccessFile(path, "r").channel
        }
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IOException("无法打开文件: $uri")
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).channel
    }

    fun readHead(channel: SeekableByteChannel, size: Int): ByteArray {
        channel.position(0)
        val buffer = ByteBuffer.allocate(size)
        while (buffer.hasRemaining() && channel.read(buffer) >= 0) Unit
        return buffer.array().copyOf(buffer.position())
    }

    fun readAt(channel: SeekableByteChannel, offset: Long, length: Int): ByteArray {
        channel.position(offset)
        val buffer = ByteBuffer.allocate(length)
        while (buffer.hasRemaining() && channel.read(buffer) >= 0) Unit
        return buffer.array().copyOf(buffer.position())
    }

    fun displayName(context: Context, uri: Uri): String? {
        if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            runCatching {
                context.contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    if (cursor.moveToFirst()) return cursor.getString(0)
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }
}

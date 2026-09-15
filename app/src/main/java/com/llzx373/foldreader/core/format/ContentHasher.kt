package com.llzx373.foldreader.core.format

import java.nio.ByteBuffer
import java.security.MessageDigest

object ContentHasher {

    const val SAMPLE_BYTES = 8 * 1024

    fun hash(totalBytes: Long, readAt: (offset: Long, length: Int) -> ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(totalBytes).array())
        if (totalBytes > 0) {
            val offsets = longArrayOf(
                0L,
                (totalBytes / 2 - SAMPLE_BYTES / 2).coerceAtLeast(0L),
                (totalBytes - SAMPLE_BYTES).coerceAtLeast(0L),
            )
            for (offset in offsets) {
                val length = minOf(SAMPLE_BYTES.toLong(), totalBytes - offset).toInt()
                if (length > 0) digest.update(readAt(offset, length))
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

package com.llzx373.foldreader.core.format.docx

import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * DOCX 打开前的安全校验（移植自 Fossify Documents 的 validateDocxXml）：
 * - zip 条目数 ≤ [MAX_ENTRIES]、解压总量 ≤ [MAX_UNCOMPRESSED_BYTES]（防 zip 炸弹）；
 * - `.xml`/`.rels` 条目中出现 `<!DOCTYPE`（含 UTF-16 LE/BE 形态，大小写不敏感）即拒绝
 *   （防 XEE——转换链路上任何 XML 解析器都不该看到实体声明）。
 *
 * 流式单趟扫描，DOCTYPE 匹配是逐字节的 KMP 式状态机，标记串跨读块也能命中。
 */
internal object DocxSecurity {

    /** DOCX 超出安全限制（条目过多或解压体积过大）。 */
    class TooLargeException : IOException("DOCX 超出安全限制（条目数或解压体积）")

    /** DOCX 的 XML 部件含 DOCTYPE（实体注入风险）。 */
    class UnsafeXmlException : IOException("DOCX 含有不受支持的 XML 声明（DOCTYPE）")

    fun validate(input: InputStream) = validate(input, MAX_ENTRIES, MAX_UNCOMPRESSED_BYTES)

    fun validate(input: InputStream, maxEntries: Int, maxUncompressedBytes: Long) {
        ZipInputStream(input.buffered()).use { zip ->
            var entryCount = 0
            var uncompressedBytes = 0L
            while (true) {
                val entry = zip.nextEntry ?: break
                entryCount += 1
                if (entryCount > maxEntries) throw TooLargeException()

                val entryName = entry.name.lowercase()
                val scanXml = entryName.endsWith(".xml") || entryName.endsWith(".rels")
                uncompressedBytes += zip.scanEntry(
                    scanXml = scanXml,
                    remainingBytes = maxUncompressedBytes - uncompressedBytes,
                )
                zip.closeEntry()
            }
        }
    }

    /** 读尽当前条目并返回其解压字节数；超限时抛 [TooLargeException]，命中 DOCTYPE 抛 [UnsafeXmlException]。 */
    private fun InputStream.scanEntry(scanXml: Boolean, remainingBytes: Long): Long {
        val matched = IntArray(DOCTYPE_MARKERS.size)
        val buffer = ByteArray(SCAN_BUFFER_SIZE)
        var bytesRead = 0L
        while (true) {
            val count = read(buffer)
            if (count == -1) return bytesRead

            bytesRead += count
            if (bytesRead > remainingBytes) throw TooLargeException()
            if (!scanXml) continue

            repeat(count) { index ->
                val value = buffer[index].toInt().and(0xFF).uppercaseAscii()
                DOCTYPE_MARKERS.forEachIndexed { markerIndex, marker ->
                    matched[markerIndex] = when (value) {
                        marker[matched[markerIndex]].toInt().and(0xFF) -> matched[markerIndex] + 1
                        marker.first().toInt().and(0xFF) -> 1
                        else -> 0
                    }
                    if (matched[markerIndex] == marker.size) throw UnsafeXmlException()
                }
            }
        }
    }

    private fun Int.uppercaseAscii(): Int = if (this in 'a'.code..'z'.code) this - 32 else this

    private fun String.utf16Marker(littleEndian: Boolean): ByteArray =
        ByteArray(length * 2) { index ->
            val character = this[index / 2].code.toByte()
            if ((index % 2 == 0) == littleEndian) character else 0
        }

    private const val DOCTYPE_MARKER = "<!DOCTYPE"
    private const val SCAN_BUFFER_SIZE = 8 * 1024

    const val MAX_ENTRIES = 4_096
    const val MAX_UNCOMPRESSED_BYTES = 64L * 1024L * 1024L

    private val DOCTYPE_MARKERS = listOf(
        DOCTYPE_MARKER.encodeToByteArray(),
        DOCTYPE_MARKER.utf16Marker(littleEndian = true),
        DOCTYPE_MARKER.utf16Marker(littleEndian = false),
    )
}

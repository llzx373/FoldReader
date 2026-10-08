package com.llzx373.foldreader.core.format.markdown

import android.net.Uri
import com.llzx373.foldreader.core.format.BookContent
import com.llzx373.foldreader.core.format.BookMeta
import com.llzx373.foldreader.core.format.BookParser
import com.llzx373.foldreader.core.format.Chapter
import com.llzx373.foldreader.core.format.EncodingDetector
import java.io.File
import java.nio.channels.Channels
import java.nio.channels.SeekableByteChannel
import java.nio.charset.Charset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Markdown（.md/.markdown）解析器：**不压平**——渲染层直接吃 commonmark AST
 * （MarkdownReaderScreen 逐块渲染，Markdown 语法本身就是要显示出来的结构），
 * 所以这里没有 EPUB/DOCX/HTML 那份 converted/ 产物，也没有预热步骤。
 *
 * 职责只三件：标题元数据（第一个标题）、目录（AST 里的 h1-h6，偏移取 source span）、
 * 全文内容（内存字符串——Markdown 文件都很小，不需要 TXT 那套边读边建的偏移索引）。
 * 锚点坐标 = 剥掉 BOM 后的解码文本的字符偏移，进度/书签/目录共用。
 *
 * Uri 壳方法只做 读字节/解码/委托；核心逻辑走 File/String 参数的内部方法以便 JVM 单测。
 */
class MarkdownBookParser(
    private val openChannel: (Uri) -> SeekableByteChannel,
    private val displayNameOf: (Uri) -> String?,
) : BookParser {

    override suspend fun parseMeta(uri: Uri): BookMeta = withContext(Dispatchers.IO) {
        openChannel(uri).use { channel ->
            val bytes = readAllBytes(channel)
            val detection = EncodingDetector.detect(bytes)
            val text = decode(bytes)
            BookMeta(
                title = titleOf(newMarkdownParser().parse(text)) ?: fallbackTitle(uri),
                author = null,
                // 正文就是源文件本身（没有压平产物），编码如实上报，openContent 按它解码
                encoding = detection.charset.name(),
                byteSize = bytes.size.toLong(),
            )
        }
    }

    override suspend fun parseChapters(uri: Uri, charsetOverride: Charset?): List<Chapter> =
        withContext(Dispatchers.IO) {
            openChannel(uri).use { channel ->
                chaptersOf(decode(readAllBytes(channel), charsetOverride))
            }
        }

    override suspend fun openContent(uri: Uri, charsetOverride: Charset?): BookContent =
        withContext(Dispatchers.IO) {
            openChannel(uri).use { channel ->
                MarkdownContent(decode(readAllBytes(channel), charsetOverride))
            }
        }

    /** 阅读器用：整篇解码文本（AST 解析、块渲染、内容读取共用同一份，锚点同坐标系）。 */
    suspend fun readDecoded(uri: Uri, charsetOverride: Charset? = null): String =
        withContext(Dispatchers.IO) {
            openChannel(uri).use { channel -> decode(readAllBytes(channel), charsetOverride) }
        }

    internal fun parseChaptersFile(file: File, charsetOverride: Charset? = null): List<Chapter> =
        chaptersOf(decode(file.readBytes(), charsetOverride))

    internal fun openContentFile(file: File, charsetOverride: Charset? = null): BookContent =
        MarkdownContent(decode(file.readBytes(), charsetOverride))

    internal fun titleOfFile(file: File): String? =
        titleOf(newMarkdownParser().parse(decode(file.readBytes())))

    /**
     * 编码探测（同 TXT/HTML 管线）：BOM 优先，探测失败按 UTF-8；BOM 长度显式跳过
     * （UTF-16LE/BE 具名解码器不剥 BOM）。charsetOverride 只在调用方明确指定时生效，
     * 且须先跳过 BOM（库里存的编码名与 BOM 可能同时存在）。
     */
    internal fun decode(bytes: ByteArray, charsetOverride: Charset? = null): String {
        if (bytes.isEmpty()) return ""
        val detection = EncodingDetector.detect(bytes)
        val bom = EncodingDetector.bomLengthOf(bytes)
        return String(bytes, bom, bytes.size - bom, charsetOverride ?: detection.charset)
    }

    private fun readAllBytes(channel: SeekableByteChannel): ByteArray {
        channel.position(0)
        return Channels.newInputStream(channel).use { it.readBytes() }
    }

    private fun fallbackTitle(uri: Uri): String =
        displayNameOf(uri)
            ?.substringBeforeLast('.')
            ?.takeIf { it.isNotBlank() }
            ?: "未知书名"

    /** 全文驻内存的简单内容实现：Markdown 没有压平产物，源文件本身就是全部。 */
    private class MarkdownContent(private val text: String) : BookContent {
        override val charCount: Long get() = text.length.toLong()

        override suspend fun read(range: LongRange): String {
            val start = range.first.coerceIn(0L, text.length.toLong()).toInt()
            val endExclusive = (range.last + 1).coerceIn(0L, text.length.toLong()).toInt()
            return if (endExclusive > start) text.substring(start, endExclusive) else ""
        }
    }
}

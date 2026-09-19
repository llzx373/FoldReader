package com.llzx373.foldreader.core.format.txt

import android.net.Uri
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * 取文必须按调用方给的 bookId 走。
 *
 * 同一个源文件在库里可以有多行（原版 + 清洗版），按 URI 反查只能撞上其中一行：
 * 修好之前，打开清洗版那一本读到的是原版正文，偏移索引与章节还会写到原版那行上。
 */
@RunWith(RobolectricTestRunner::class)
class TxtBookParserBookIdTest {

    private val rawText = "第一章 原版\n\n广告：请收藏本站并投推荐票\n正文内容在这里。\n"
    private val cleanedText = "第一章 原版\n\n正文内容在这里。\n"

    private fun tempFile(prefix: String, text: String): File {
        val file = File.createTempFile(prefix, ".txt")
        file.deleteOnExit()
        file.writeText(text, Charsets.UTF_8)
        return file
    }

    /**
     * 与生产接线同形：给了 bookId 就取**那一本**的清洗副本；没给才回落到按 URI 反查
     * （这里的假反查固定指向原版，正是"库里最早那一行"的等价物）。
     */
    private fun parser(cleaned: File): TxtBookParser {
        val cleanedPathByBook = mapOf(RAW_BOOK_ID to null, CLEANED_BOOK_ID to cleaned.absolutePath)
        return TxtBookParser(
            context = RuntimeEnvironment.getApplication(),
            bookIdResolver = { _, bookId -> bookId ?: RAW_BOOK_ID },
            contentUriResolver = { uri, bookId ->
                cleanedPathByBook[bookId]?.let { Uri.fromFile(File(it)) } ?: uri
            },
        )
    }

    @Test
    fun `按 bookId 取文 清洗版读副本 原版读原文件`() = runBlocking {
        val raw = tempFile("foldreader-raw", rawText)
        val cleaned = tempFile("foldreader-cleaned", cleanedText)
        val uri = Uri.fromFile(raw)
        val parser = parser(cleaned)

        val cleanedContent = parser.openContent(uri, Charsets.UTF_8, CLEANED_BOOK_ID)
        val cleanedRead = cleanedContent.read(0L until cleanedContent.charCount)
        (cleanedContent as? java.io.Closeable)?.close()

        val rawContent = parser.openContent(uri, Charsets.UTF_8, RAW_BOOK_ID)
        val rawRead = rawContent.read(0L until rawContent.charCount)
        (rawContent as? java.io.Closeable)?.close()

        assertEquals(cleanedText, cleanedRead)
        assertEquals(rawText, rawRead)
    }

    @Test
    fun `按 bookId 建索引 索引算的是那一本的文件`() = runBlocking {
        val raw = tempFile("foldreader-raw-index", rawText)
        val cleaned = tempFile("foldreader-cleaned-index", cleanedText)
        val uri = Uri.fromFile(raw)
        val parser = parser(cleaned)

        assertEquals(cleanedText.length.toLong(), parser.index(uri, Charsets.UTF_8, CLEANED_BOOK_ID).charCount)
        assertEquals(rawText.length.toLong(), parser.index(uri, Charsets.UTF_8, RAW_BOOK_ID).charCount)
    }

    @Test
    fun `不给 bookId 时回落到按 URI 反查`() = runBlocking {
        val raw = tempFile("foldreader-raw-fallback", rawText)
        val cleaned = tempFile("foldreader-cleaned-fallback", cleanedText)
        val uri = Uri.fromFile(raw)

        // 这正是旧行为的形状：不传 bookId 就只剩"按 URI 猜"，于是两个入口拿到同一份正文。
        // 调用方一律显式传 bookId 才不会走到这里（见上面两条用例）。
        val content = parser(cleaned).openContent(uri, Charsets.UTF_8)
        val read = content.read(0L until content.charCount)
        (content as? java.io.Closeable)?.close()
        assertEquals(rawText, read)
    }

    private companion object {
        const val RAW_BOOK_ID = 1L
        const val CLEANED_BOOK_ID = 2L
    }
}

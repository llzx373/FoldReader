package com.llzx373.foldreader.core.format.txt

import com.llzx373.foldreader.core.format.TextCleaner
import java.io.BufferedReader
import java.io.File
import java.io.RandomAccessFile
import java.io.StringReader
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class CleanedCopyContentTest {

    private val tsMap = mapOf('體' to '体', '開' to '开')

    private fun writeCleanedCopy(text: String, options: TextCleaner.CleanOptions): Pair<File, String> {
        val file = File.createTempFile("foldreader-cleaned", ".txt")
        file.deleteOnExit()
        file.outputStream().buffered().writer(Charsets.UTF_8).buffered().use { writer ->
            TextCleaner.cleanStream(
                reader = BufferedReader(StringReader(text)),
                writer = writer,
                options = options,
                tsMap = tsMap,
            )
        }
        return file to file.readText(Charsets.UTF_8)
    }

    @Test
    fun `清洗副本建索引后窗口读取与清洗产物一致`() = runBlocking {
        val original = buildString {
            repeat(200) { i ->
                append("第").append(i).append("章 開卷身體測試內容。\n")
                append("\n")
                append("廣告：關注公眾號領取福利\n")
                append("正文段落，多字節字符混排 😀。\n")
            }
        }
        val options = TextCleaner.CleanOptions(
            removeBlankLines = true,
            adPatterns = listOf(Regex("广告|廣告")),
            traditionalToSimplified = true,
        )
        val (file, expected) = writeCleanedCopy(original, options)

        val index = TxtIndexer.index(
            channel = FileChannel.open(file.toPath(), StandardOpenOption.READ),
            charset = Charsets.UTF_8,
            bomLength = 0,
            blockChars = 64,
        )
        val content = TxtBookContent(
            channel = RandomAccessFile(file, "r").channel,
            charset = Charsets.UTF_8,
            offsetIndex = index.offsetIndex,
        )

        assertEquals(expected.length.toLong(), content.charCount)
        assertEquals(expected, content.read(0L..expected.length - 1L))
        assertEquals(expected.substring(100, 300), content.read(100L..299L))
        assertEquals(expected.takeLast(50), content.read(expected.length - 50L..expected.length - 1L))
    }

    @Test
    fun `清洗副本索引快照恢复后读取一致`() = runBlocking {
        val original = "開篇\n\n\n正文內容。\n廣告插入\n第二章 繼續。\n"
        val options = TextCleaner.CleanOptions(
            removeBlankLines = true,
            adPatterns = listOf(Regex("廣告|广告")),
            traditionalToSimplified = true,
        )
        val (file, expected) = writeCleanedCopy(original, options)

        val index = TxtIndexer.index(
            channel = FileChannel.open(file.toPath(), StandardOpenOption.READ),
            charset = Charsets.UTF_8,
            bomLength = 0,
            blockChars = 8,
        )
        val restored = com.llzx373.foldreader.core.format.OffsetIndex.restore(
            index.offsetIndex.snapshot(),
        )
        val content = TxtBookContent(
            channel = RandomAccessFile(file, "r").channel,
            charset = Charsets.UTF_8,
            offsetIndex = restored,
        )

        assertEquals(expected, content.read(0L..expected.length - 1L))
    }
}

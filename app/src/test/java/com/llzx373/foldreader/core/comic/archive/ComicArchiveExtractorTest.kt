package com.llzx373.foldreader.core.comic.archive

import com.llzx373.foldreader.core.comic.ComicExtractionStore
import com.llzx373.foldreader.core.comic.ComicTestImages
import com.llzx373.foldreader.core.comic.archive.ComicArchiveExtractor.extractRar
import com.llzx373.foldreader.core.comic.archive.ComicArchiveExtractor.extractSevenZip
import com.llzx373.foldreader.core.comic.archive.ComicArchiveExtractor.extractTar
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ComicArchiveExtractorTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val page1 = ComicTestImages.png(20, 20)
    private val page2 = ComicTestImages.jpeg(30, 30)

    private val entries = listOf(
        "10.png" to page2,
        "2.png" to page1,
        "1.png" to page2,
        "readme.txt" to "nope".toByteArray(),
        "__MACOSX/._1.png" to page1,
        "Thumbs.db" to page1,
    )

    private fun tarFile(): File {
        val file = temp.newFile("book.cbt")
        TarArchiveOutputStream(file.outputStream()).use { tar ->
            for ((name, bytes) in entries) {
                tar.putArchiveEntry(TarArchiveEntry(name).apply { size = bytes.size.toLong() })
                tar.write(bytes)
                tar.closeArchiveEntry()
            }
        }
        return file
    }

    private fun sevenZipFile(): File {
        val file = temp.newFile("book.cb7")
        SevenZOutputFile(file).use { out ->
            for ((name, bytes) in entries) {
                out.putArchiveEntry(
                    SevenZArchiveEntry().apply {
                        this.name = name
                        size = bytes.size.toLong()
                    },
                )
                out.write(bytes)
                out.closeArchiveEntry()
            }
        }
        return file
    }

    /** 样本是 WinRAR 打出来的真实 RAR5 归档，说明见 src/test/resources/comic/README.md。 */
    private fun comicRar(): File {
        val url = javaClass.getResource("/comic/pages.cbr")
        if (url != null && url.protocol == "file") return File(url.toURI())
        return File("src/test/resources/comic/pages.cbr")
    }

    @Test
    fun `tar 解压按自然序成页并过滤噪音`() {
        val store = ComicExtractionStore(temp.newFolder("comics"))
        val source = tarFile()

        val extracted = source.inputStream().use { extractTar(it, temp.newFolder("tar-pages")) }
        assertEquals(setOf("1.png", "2.png", "10.png"), extracted.map { it.first }.toSet())

        // 交给存储层后按原始名排序、加序号前缀
        val ordered = store.ensureExtracted("tar-hash") { dir ->
            source.inputStream().use { extractTar(it, dir) }
        }
        assertEquals(
            listOf("000000_1.png", "000001_2.png", "000002_10.png"),
            ordered.map { it.name },
        )
        // 1.png 里写的是 page2 的字节，索引必须跟内容对上
        assertArrayEquals(page2, ordered[0].readBytes())
    }

    @Test
    fun `7z 解压按自然序成页并过滤噪音`() {
        val file = sevenZipFile()
        val store = ComicExtractionStore(temp.newFolder("comics"))

        val ordered = store.ensureExtracted("7z-hash") { dir ->
            FileChannel.open(file.toPath(), StandardOpenOption.READ).use { channel ->
                extractSevenZip(channel, dir)
            }
        }

        assertEquals(
            listOf("000000_1.png", "000001_2.png", "000002_10.png"),
            ordered.map { it.name },
        )
        assertArrayEquals(page2, ordered[0].readBytes())
        assertArrayEquals(page1, ordered[1].readBytes())
    }

    @Test
    fun `7z 跳过不取的条目后仍能正确推进`() {
        val file = sevenZipFile()
        val target = temp.newFolder("7z-skip")

        val extracted = FileChannel.open(file.toPath(), StandardOpenOption.READ).use { channel ->
            extractSevenZip(channel, target)
        }

        // readme.txt 在被跳过之列，跳过顺序错位的话后面的页内容会对不上
        assertEquals(3, extracted.size)
        val byName = extracted.associateBy({ it.first }, { it.second.readBytes() })
        assertArrayEquals(page1, byName["2.png"])
        assertArrayEquals(page2, byName["10.png"])
    }

    @Test
    fun `rar 解压按自然序成页并过滤噪音`() {
        val store = ComicExtractionStore(temp.newFolder("comics"))

        val ordered = store.ensureExtracted("rar-hash") { dir ->
            comicRar().inputStream().use { extractRar(it, dir) }
        }

        assertEquals(
            listOf("000000_1.png", "000001_2.png", "000002_10.png"),
            ordered.map { it.name },
        )
        // 每个条目的内容是它自己的名字，索引必须跟内容对上
        assertEquals("1.png", ordered[0].readText())
        assertEquals("2.png", ordered[1].readText())
        assertEquals("10.png", ordered[2].readText())
    }

    @Test
    fun `rar 跳过不取的条目后仍能正确推进`() {
        val target = temp.newFolder("rar-skip")

        val extracted = comicRar().inputStream().use { extractRar(it, target) }

        // readme.txt / Thumbs.db / __MACOSX/._1.png 与 __MACOSX 目录条目都在被跳过之列，
        // 跳过顺序错位的话后面的页内容会对不上
        assertEquals(listOf("1.png", "10.png", "2.png"), extracted.map { it.first })
        assertEquals("10.png", extracted[1].second.readText())
    }
}

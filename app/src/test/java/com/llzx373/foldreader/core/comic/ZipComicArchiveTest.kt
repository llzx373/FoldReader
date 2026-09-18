package com.llzx373.foldreader.core.comic

import com.llzx373.foldreader.core.comic.archive.openLocalComicArchive
import com.llzx373.foldreader.core.comic.archive.openZipComicArchive
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ZipComicArchiveTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val page1 = ComicTestImages.png(40, 60)
    private val page2 = ComicTestImages.jpeg(80, 120)
    private val page3 = ComicTestImages.gif(30, 30)

    private fun zip(name: String, entries: List<Pair<String, ByteArray>>): File {
        val file = temp.newFile(name)
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((entryName, bytes) in entries) {
                zip.putNextEntry(ZipEntry(entryName))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return file
    }

    /** 打开的 archive 持有 channel，由 archive.close() 负责关闭。 */
    private fun open(file: File): ComicArchive =
        openZipComicArchive(FileChannel.open(file.toPath(), StandardOpenOption.READ))

    @Test
    fun `页序自然排序且噪音条目被过滤`() {
        val file = zip(
            "book.cbz",
            listOf(
                "10.png" to page1,
                "2.png" to page2,
                "1.png" to page3,
                "notes.txt" to "hello".toByteArray(),
                "__MACOSX/._1.png" to page1,
                "Thumbs.db" to page1,
            ),
        )

        open(file).use { archive ->
            assertEquals(ComicContainer.ZIP, archive.container)
            assertEquals(listOf("1.png", "2.png", "10.png"), archive.pages.map { it.name })
        }
    }

    @Test
    fun `按页序读出的字节与写入一致`() {
        val file = zip(
            "book.cbz",
            listOf("b.png" to page1, "a.png" to page2, "c.png" to page3),
        )

        open(file).use { archive ->
            assertArrayEquals(page2, archive.readPage(0))
            assertArrayEquals(page1, archive.readPage(1))
            assertArrayEquals(page3, archive.readPage(2))
            // 重复读走字节缓存，内容仍须一致
            assertArrayEquals(page2, archive.readPage(0))
        }
    }

    @Test
    fun `探测页尺寸不需要整页解码`() {
        val file = zip("book.cbz", listOf("1.png" to page1, "2.jpg" to page2))

        open(file).use { archive ->
            assertEquals(listOf(40, 60), archive.pageSize(0)?.toList())
            assertEquals(listOf(80, 120), archive.pageSize(1)?.toList())
        }
    }

    @Test
    fun `没有任何图片时报错而不是给出空书`() {
        val file = zip("book.cbz", listOf("readme.txt" to "x".toByteArray()))

        assertThrows(IOException::class.java) { open(file) }
    }

    @Test
    fun `本地目录容器按序号前缀给出正确页序`() {
        val dir = temp.newFolder("pages")
        File(dir, "000002_b.png").writeBytes(page2)
        File(dir, "000001_a.png").writeBytes(page1)
        File(dir, "000003_c.png").writeBytes(page3)

        openLocalComicArchive(ComicContainer.RAR, dir.listFiles()!!.sortedBy { it.name }).use { archive ->
            assertEquals(listOf("a.png", "b.png", "c.png"), archive.pages.map { it.name })
            assertArrayEquals(page1, archive.readPage(0))
            assertArrayEquals(page2, archive.readPage(1))
        }
    }
}

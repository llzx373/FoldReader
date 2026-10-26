package com.llzx373.foldreader.core.comic

import android.net.Uri
import com.llzx373.foldreader.core.comic.archive.ComicDirChild
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ComicArchiveFactoryTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun comicRar(): File {
        val url = javaClass.getResource("/comic/pages.cbr")
        if (url != null && url.protocol == "file") return File(url.toURI())
        return File("src/test/resources/comic/pages.cbr")
    }

    private fun factory(
        store: ComicExtractionStore,
        files: Map<String, File> = emptyMap(),
        docs: Map<String, ByteArray> = emptyMap(),
        tree: Map<String, List<ComicDirChild>> = emptyMap(),
        temporaryFiles: MutableList<File>? = null,
    ): ComicArchiveFactory {
        val tempDir = temporaryFiles?.let { temp.newFolder("factory-temp") }
        return ComicArchiveFactory(
            extractionStore = store,
            openChannel = { uri ->
                FileChannel.open(
                    files.getValue(uri.toString()).toPath(),
                    StandardOpenOption.READ,
                )
            },
            openDocumentStream = { key -> docs[key]?.inputStream() },
            listDocumentChildren = { uri -> tree[uri.toString()].orEmpty() },
            createTempArchive = {
                if (temporaryFiles == null) {
                    store.createTempArchive()
                } else {
                    File.createTempFile("rar-", ".tmp", tempDir).also(temporaryFiles::add)
                }
            },
        )
    }

    @Test
    fun `rar 经临时文件解压和读取 ComicInfo 后均清理`() = runBlocking {
        val uri = Uri.parse("content://test/book.cbr")
        val store = ComicExtractionStore(temp.newFolder("comics"))
        val temporaryFiles = mutableListOf<File>()
        val factory = factory(
            store,
            files = mapOf(uri.toString() to comicRar()),
            temporaryFiles = temporaryFiles,
        )

        factory.open(uri, ComicContainer.RAR, "rar").use { archive ->
            assertEquals(listOf("1.png", "2.png", "10.png"), archive.pages.map { it.name })
            assertEquals("2.png", String(archive.readPage(1)))
        }
        assertNull(factory.readComicInfo(uri, ComicContainer.RAR, "rar"))

        assertEquals(2, temporaryFiles.size)
        assertTrue(temporaryFiles.none { it.exists() })
    }

    @Test
    fun `本地 rar 直接读文件且不创建临时副本`() = runBlocking {
        val store = ComicExtractionStore(temp.newFolder("comics"))
        val temporaryFiles = mutableListOf<File>()
        val factory = factory(store, temporaryFiles = temporaryFiles)

        factory.open(Uri.fromFile(comicRar()), ComicContainer.RAR, "local-rar").use { archive ->
            assertEquals(listOf("1.png", "2.png", "10.png"), archive.pages.map { it.name })
        }

        assertTrue(temporaryFiles.isEmpty())
    }

    @Test
    fun `rar 解压失败也清理临时文件`() {
        val uri = Uri.parse("content://test/broken.cbr")
        val broken = temp.newFile("broken.cbr").also { it.writeBytes(ByteArray(128)) }
        val store = ComicExtractionStore(temp.newFolder("comics"))
        val temporaryFiles = mutableListOf<File>()
        val factory = factory(
            store,
            files = mapOf(uri.toString() to broken),
            temporaryFiles = temporaryFiles,
        )

        assertThrows(Exception::class.java) {
            runBlocking { factory.open(uri, ComicContainer.RAR, "broken") }
        }
        assertEquals(1, temporaryFiles.size)
        assertFalse(temporaryFiles.single().exists())
    }

    @Test
    fun `zip 复制本地保持自然页序和页内容`() = runBlocking {
        val uri = Uri.parse("content://test/book.cbz")
        val file = temp.newFile("book.cbz")
        val entries = listOf(
            "10.png" to byteArrayOf(10),
            "2.png" to byteArrayOf(2),
            "1.png" to byteArrayOf(1),
        )
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        val store = ComicExtractionStore(temp.newFolder("comics"))
        val factory = factory(store, files = mapOf(uri.toString() to file))

        val pages = factory.copyLocal(uri, ComicContainer.ZIP, "zip")

        assertEquals(
            listOf("000000_1.png", "000001_2.png", "000002_10.png"),
            pages.map { it.name },
        )
        listOf(1, 2, 10).forEachIndexed { index, value ->
            assertArrayEquals(byteArrayOf(value.toByte()), pages[index].readBytes())
        }
    }

    @Test
    fun `目录复制本地保持扫描页序和页内容`() = runBlocking {
        val root = Uri.parse("content://test/tree/root")
        val tree = mapOf(
            root.toString() to listOf(
                ComicDirChild("10.png", false, "doc://p10"),
                ComicDirChild("2.png", false, "doc://p2"),
                ComicDirChild("1.png", false, "doc://p1"),
            ),
        )
        val docs = mapOf(
            "doc://p10" to byteArrayOf(10),
            "doc://p2" to byteArrayOf(2),
            "doc://p1" to byteArrayOf(1),
        )
        val store = ComicExtractionStore(temp.newFolder("comics"))
        val factory = factory(store, docs = docs, tree = tree)

        val pages = factory.copyLocal(root, ComicContainer.FOLDER, "folder")

        assertEquals(
            listOf("000000_1.png", "000001_2.png", "000002_10.png"),
            pages.map { it.name },
        )
        listOf(1, 2, 10).forEachIndexed { index, value ->
            assertArrayEquals(byteArrayOf(value.toByte()), pages[index].readBytes())
        }
    }
}

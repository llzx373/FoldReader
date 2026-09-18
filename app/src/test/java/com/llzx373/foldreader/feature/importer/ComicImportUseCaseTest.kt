package com.llzx373.foldreader.feature.importer

import android.net.Uri
import com.llzx373.foldreader.core.comic.ComicArchiveFactory
import com.llzx373.foldreader.core.comic.ComicContainer
import com.llzx373.foldreader.core.comic.ComicExtractionStore
import com.llzx373.foldreader.core.comic.ComicTestImages
import com.llzx373.foldreader.core.comic.archive.ComicDirChild
import com.llzx373.foldreader.core.data.db.BookFormat
import com.llzx373.foldreader.core.data.db.BookSource
import com.llzx373.foldreader.core.data.repository.FakeBookshelfRepository
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 漫画登记：去重、按容器分档的页数解析、封面、预热排队。
 * 需要真实 `Uri` 与 `Bitmap`（封面编码），所以跑在 Robolectric 上。
 */
@RunWith(RobolectricTestRunner::class)
class ComicImportUseCaseTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val repository = FakeBookshelfRepository()
    private val prewarmCalls = mutableListOf<Triple<Long, String, BookFormat>>()

    private val page1 = ComicTestImages.png(60, 80)
    private val page2 = ComicTestImages.png(61, 81)
    private val page3 = ComicTestImages.png(62, 82)

    private fun cbz(name: String, pages: List<ByteArray> = listOf(page1, page2, page3)): File {
        val file = temp.newFile(name)
        ZipOutputStream(file.outputStream()).use { zip ->
            pages.forEachIndexed { index, bytes ->
                zip.putNextEntry(ZipEntry("%03d.png".format(index + 1)))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        // 记下 Uri → File 的对应：Robolectric 的 Uri.fromFile 在 Windows 上给出的
        // path 带前导斜杠，直接喂给 Paths.get 会打不开文件，测试里按真实 File 开通道
        return track(file)
    }

    private val files = mutableMapOf<String, File>()

    private fun track(file: File): File {
        files[Uri.fromFile(file).toString()] = file
        return file
    }

    private fun openChannelFor(key: String): FileChannel =
        FileChannel.open(
            (files[key] ?: error("测试未登记该 Uri 对应的文件: $key")).toPath(),
            StandardOpenOption.READ,
        )

    private fun useCase(
        coversDir: File? = temp.newFolder("covers"),
        tree: Map<String, List<ComicDirChild>> = emptyMap(),
        docs: Map<String, ByteArray> = emptyMap(),
    ) = ComicImportUseCase(
        bookshelfRepository = repository,
        archiveFactory = ComicArchiveFactory(
            extractionStore = ComicExtractionStore(temp.newFolder("comics")),
            openChannel = { uri -> openChannelFor(uri.toString()) },
            openDocumentStream = { key -> docs[key]?.inputStream() },
            listDocumentChildren = { uri -> tree[uri.toString()].orEmpty() },
        ),
        extractionStore = ComicExtractionStore(temp.newFolder("comics-cache")),
        openChannel = { key -> openChannelFor(key) },
        displayNameOf = { key -> displayNameOf(key) },
        coversDir = coversDir,
    )

    private var names: Map<String, String> = emptyMap()

    private fun displayNameOf(key: String): String? =
        names[key] ?: files[key]?.name ?: Uri.parse(key).lastPathSegment

    private fun uriOf(file: File): Uri = Uri.fromFile(file)

    @Test
    fun `zip 漫画登记页数并抽封面`() = runBlocking {
        val file = cbz("book.cbz")
        val useCase = useCase()

        val outcome = useCase.register(uriOf(file), ComicContainer.ZIP, BookSource.IMPORT)

        assertTrue("outcome=$outcome", outcome is ComicImportUseCase.Outcome.Registered)
        val book = repository.books.value.single()
        assertEquals("book", book.title)
        assertEquals(BookFormat.COMIC, book.format)
        assertEquals(ComicContainer.ZIP, book.comicContainer)
        assertEquals(3, book.comicPageCount)
        assertNull(book.comicLocalPath)
        assertEquals(BookSource.IMPORT, book.source)
        assertNotNull(book.coverPath)
        assertTrue(File(book.coverPath!!).isFile)
        // zip 导入即知页数，不需要预热
        assertTrue(prewarmCalls.isEmpty())
    }

    @Test
    fun `分组名随登记写入`() = runBlocking {
        val useCase = useCase()

        useCase.register(uriOf(cbz("grouped.cbz")), ComicContainer.ZIP, BookSource.EXTERNAL, groupName = " 第01卷 ")

        assertEquals("第01卷", repository.books.value.single().groupName)
    }

    @Test
    fun `同一路径重复登记判重复`() = runBlocking {
        val file = cbz("same.cbz")
        val useCase = useCase()
        useCase.register(uriOf(file), ComicContainer.ZIP, BookSource.IMPORT)

        val outcome = useCase.register(uriOf(file), ComicContainer.ZIP, BookSource.IMPORT)

        assertEquals(true, (outcome as ComicImportUseCase.Outcome.Duplicate).sameUri)
        assertEquals(1, repository.books.value.size)
    }

    @Test
    fun `不同路径的同内容容器按哈希判重复`() = runBlocking {
        val first = cbz("a.cbz")
        val second = track(File(temp.newFolder("elsewhere"), "b.cbz").also { first.copyTo(it) })
        val useCase = useCase()
        useCase.register(uriOf(first), ComicContainer.ZIP, BookSource.IMPORT)

        val outcome = useCase.register(uriOf(second), ComicContainer.ZIP, BookSource.IMPORT)

        assertEquals(false, (outcome as ComicImportUseCase.Outcome.Duplicate).sameUri)
        assertEquals(1, repository.books.value.size)
    }

    @Test
    fun `需要解压的容器导入时页数留空并标记待预热`() = runBlocking {
        val file = cbz("deferred.cbr")
        val useCase = useCase()

        val outcome = useCase.register(uriOf(file), ComicContainer.RAR, BookSource.IMPORT)

        val book = repository.books.value.single()
        assertNull(book.comicPageCount)
        // rar 抽封面要整本解压，导入阶段不做
        assertNull(book.coverPath)
        assertEquals(
            true,
            (outcome as ComicImportUseCase.Outcome.Registered).needsPreparation,
        )
    }

    @Test
    fun `zip 导入即知页数不需要预热`() = runBlocking {
        val useCase = useCase()

        val outcome = useCase.register(uriOf(cbz("ready.cbz")), ComicContainer.ZIP, BookSource.IMPORT)

        assertEquals(
            false,
            (outcome as ComicImportUseCase.Outcome.Registered).needsPreparation,
        )
    }

    @Test
    fun `目录漫画按扫描结果登记页数`() = runBlocking {
        val root = Uri.parse("content://test/tree/root/document/root")
        val tree = mapOf(
            root.toString() to listOf(
                ComicDirChild("002.png", isDirectory = false, key = "doc://p2"),
                ComicDirChild("001.png", isDirectory = false, key = "doc://p1"),
                ComicDirChild("cover.txt", isDirectory = false, key = "doc://txt"),
            ),
        )
        val docs = mapOf("doc://p1" to page1, "doc://p2" to page2)
        names = mapOf(root.toString() to "第01卷")
        val useCase = useCase(tree = tree, docs = docs)

        val outcome = useCase.register(root, ComicContainer.FOLDER, BookSource.EXTERNAL)

        assertTrue("outcome=$outcome", outcome is ComicImportUseCase.Outcome.Registered)
        val book = repository.books.value.single()
        assertEquals("第01卷", book.title)
        assertEquals(2, book.comicPageCount)
        assertEquals(ComicContainer.FOLDER, book.comicContainer)
        assertNotNull(book.coverPath)
        assertTrue(prewarmCalls.isEmpty())
    }

    @Test
    fun `目录内没有图片时报失败而不是建出空书`() = runBlocking {
        val root = Uri.parse("content://test/tree/empty/document/empty")
        val useCase = useCase(
            tree = mapOf(
                root.toString() to listOf(ComicDirChild("readme.txt", false, "doc://txt")),
            ),
        )

        val outcome = useCase.register(root, ComicContainer.FOLDER, BookSource.EXTERNAL)

        assertTrue(outcome is ComicImportUseCase.Outcome.Failure)
        assertTrue(repository.books.value.isEmpty())
    }

    @Test
    fun `容器内没有图片时登记失败`() = runBlocking {
        val file = cbz("empty.cbz", pages = emptyList())
        val useCase = useCase()

        val outcome = useCase.register(uriOf(file), ComicContainer.ZIP, BookSource.IMPORT)

        assertTrue(outcome is ComicImportUseCase.Outcome.Failure)
        assertTrue(repository.books.value.isEmpty())
    }

    @Test
    fun `封面目录为空时跳过封面`() = runBlocking {
        val useCase = useCase(coversDir = null)

        useCase.register(uriOf(cbz("nocover.cbz")), ComicContainer.ZIP, BookSource.IMPORT)

        assertNull(repository.books.value.single().coverPath)
    }

    @Test
    fun `无扩展名时回退默认标题`() = runBlocking {
        val file = cbz("noext")
        names = mapOf(uriOf(file).toString() to "   ")
        val useCase = useCase()

        useCase.register(uriOf(file), ComicContainer.ZIP, BookSource.IMPORT)

        assertEquals(ComicImportUseCase.DEFAULT_TITLE, repository.books.value.single().title)
    }
}

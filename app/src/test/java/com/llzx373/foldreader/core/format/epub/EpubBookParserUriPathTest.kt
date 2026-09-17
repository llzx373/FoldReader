package com.llzx373.foldreader.core.format.epub

import android.net.Uri
import com.llzx373.foldreader.core.format.BookContent
import com.llzx373.foldreader.core.format.txt.TxtBookContent
import com.llzx373.foldreader.core.format.txt.TxtIndexer
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.SeekableByteChannel
import java.nio.file.StandardOpenOption
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.kxml2.io.KXmlParser
import org.robolectric.RobolectricTestRunner

/**
 * 打开路径上的 Uri 壳方法必须做到「不整本复制源文件」：
 * [EpubBookParser.pageLabels] 每次打开都会调用，[EpubBookParser.imageFile] 每张图都会调用。
 * 用计数 channel 量化「从源文件读了多少字节」来证明没有发生整本拷贝
 * （采样哈希最多读 3×8KB，整本复制会读出接近文件大小的量）。
 */
@RunWith(RobolectricTestRunner::class)
class EpubBookParserUriPathTest {

    private class CountingChannel(private val delegate: SeekableByteChannel) : SeekableByteChannel {
        var bytesRead = 0L
            private set

        override fun read(dst: ByteBuffer): Int {
            val n = delegate.read(dst)
            if (n > 0) bytesRead += n
            return n
        }

        override fun write(src: ByteBuffer): Int = delegate.write(src)
        override fun position(): Long = delegate.position()
        override fun position(newPosition: Long): SeekableByteChannel {
            delegate.position(newPosition)
            return this
        }

        override fun size(): Long = delegate.size()
        override fun truncate(size: Long): SeekableByteChannel = delegate.truncate(size)
        override fun isOpen(): Boolean = delegate.isOpen()
        override fun close() = delegate.close()
    }

    private lateinit var channel: CountingChannel

    private fun newParserFor(dir: File): EpubBookParser = EpubBookParser(
        convertedDir = dir,
        openFlattenedContent = { file -> openTxt(file) },
        openChannel = { uri ->
            CountingChannel(RandomAccessFile(File(uri.path!!), "r").channel).also { channel = it }
        },
        displayNameOf = { null },
        newParser = { KXmlParser() },
        // JVM 单测无 BitmapFactory：注入假尺寸探测
        imageSizer = { intArrayOf(8, 8) },
    )

    private fun openTxt(file: File): BookContent {
        val index = TxtIndexer.index(
            FileChannel.open(file.toPath(), StandardOpenOption.READ),
            Charsets.UTF_8,
        )
        return TxtBookContent(RandomAccessFile(file, "r").channel, Charsets.UTF_8, index.offsetIndex)
    }

    /** 塞一个不可压缩的大条目，把「整本复制」与「采样哈希」的读取量拉开数量级。 */
    private fun bigEpub(entries: Map<String, ByteArray>): File {
        val file = File.createTempFile("epub-uri-path", ".epub")
        file.deleteOnExit()
        val withFiller = entries.toMutableMap()
        withFiller["OEBPS/filler.bin"] = Random(7).nextBytes(FILLER_BYTES)
        TestEpubs.writeRaw(file, withFiller)
        return file
    }

    private fun tempDir(): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "epub-uri-${System.nanoTime()}")
        dir.mkdirs()
        return dir
    }

    /** 与 ConvertedBookStore.contentHash(File) 同一套采样哈希，用来定位压平产物文件名。 */
    private fun hashOf(file: File): String =
        com.llzx373.foldreader.core.format.ContentHasher.hash(file.length()) { offset, length ->
            RandomAccessFile(file, "r").use { raf ->
                val buffer = ByteBuffer.allocate(length)
                raf.channel.position(offset)
                while (buffer.hasRemaining() && raf.channel.read(buffer) >= 0) Unit
                buffer.array().copyOf(buffer.position())
            }
        }

    private fun assertNoFullCopy(epub: File) {
        assertTrue("fixture 需足够大才能区分整本复制", epub.length() > SAMPLE_LIMIT * 2)
        assertTrue(
            "不应整本读取源文件：实际读出 ${channel.bytesRead} 字节，文件 ${epub.length()} 字节",
            channel.bytesRead < SAMPLE_LIMIT,
        )
    }

    @Test
    fun `pageLabels 走压平缓存不整本复制源文件`() {
        val dir = tempDir()
        val epub = bigEpub(TestEpubs.pageList().mapValues { it.value.toByteArray(Charsets.UTF_8) })
        val parser = newParserFor(dir)
        parser.ensureFlattenedFile(epub)

        val expected = parser.pageLabelsFile(epub)
        assertNotNull(expected)

        val actual = runBlocking { parser.pageLabels(Uri.fromFile(epub)) }

        assertEquals(expected, actual)
        assertNoFullCopy(epub)
    }

    @Test
    fun `textSpans 不整本复制源文件`() {
        val dir = tempDir()
        val epub = bigEpub(TestEpubs.styled())
        val parser = newParserFor(dir)
        parser.ensureFlattenedFile(epub)

        val actual = runBlocking { parser.textSpans(Uri.fromFile(epub)) }

        assertNotNull(actual)
        assertEquals(parser.textSpansFile(epub), actual)
        assertNoFullCopy(epub)
    }

    @Test
    fun `imageFile 用 channel 哈希定位图片且不整本复制源文件`() {
        val dir = tempDir()
        val epub = bigEpub(TestEpubs.styled())
        val parser = newParserFor(dir)
        parser.ensureFlattenedFile(epub)

        val file = runBlocking { parser.imageFile(Uri.fromFile(epub), "OEBPS/images/pic.png") }

        assertNotNull(file)
        assertTrue(file!!.isFile)
        assertTrue(file.readBytes().contentEquals(TestEpubs.PNG_BYTES))
        assertNoFullCopy(epub)
    }

    @Test
    fun `imageFile 对不存在的图片仍返回 null`() {
        val dir = tempDir()
        val epub = bigEpub(TestEpubs.styled())
        val parser = newParserFor(dir)
        parser.ensureFlattenedFile(epub)

        val file = runBlocking { parser.imageFile(Uri.fromFile(epub), "OEBPS/images/missing.png") }

        assertNull(file)
    }

    @Test
    fun `压平缓存命中复用同一解析实例不再读 sidecar`() {
        val dir = tempDir()
        val epub = File.createTempFile("epub-memo", ".epub").apply { deleteOnExit() }
        TestEpubs.write(epub, TestEpubs.epub2())
        val parser = newParserFor(dir)

        val first = parser.ensureFlattenedFile(epub)
        val second = parser.ensureFlattenedFile(epub)
        val third = parser.ensureFlattenedFile(epub)

        assertTrue("首次应真压平", first.fresh)
        assertFalse("后续不该谎报为刚压平（否则会重复回填章节）", second.fresh)
        assertSame("备忘命中应返回同一实例，避免重复解析 sidecar", second, third)
        assertEquals(first.chapters, second.chapters)
    }

    @Test
    fun `备忘命中不掩盖 sidecar 缺失`() {
        val dir = tempDir()
        val epub = File.createTempFile("epub-memo-sidecar", ".epub").apply { deleteOnExit() }
        TestEpubs.write(epub, TestEpubs.epub2())
        val parser = newParserFor(dir)

        assertTrue(parser.ensureFlattenedFile(epub).fresh)
        assertTrue(File(dir, "${hashOf(epub)}.spans").delete())

        val again = parser.ensureFlattenedFile(epub)

        assertTrue("sidecar 缺失必须触发重压平，不能被备忘掩盖", again.fresh)
    }

    @Test
    fun `备忘命中不掩盖压平版本降级`() {
        val dir = tempDir()
        val epub = File.createTempFile("epub-memo-version", ".epub").apply { deleteOnExit() }
        TestEpubs.write(epub, TestEpubs.epub2())
        val parser = newParserFor(dir)

        assertTrue(parser.ensureFlattenedFile(epub).fresh)
        File(dir, "${hashOf(epub)}.version").writeText("2", Charsets.UTF_8)

        val again = parser.ensureFlattenedFile(epub)

        assertTrue("版本降级必须触发重压平，不能被备忘掩盖", again.fresh)
    }

    @Test
    fun `压平产物被删除后备忘失效并重新压平`() {
        val dir = tempDir()
        val epub = File.createTempFile("epub-memo-drop", ".epub").apply { deleteOnExit() }
        TestEpubs.write(epub, TestEpubs.epub2())
        val parser = newParserFor(dir)

        val first = parser.ensureFlattenedFile(epub)
        assertTrue(first.file.delete())

        val second = parser.ensureFlattenedFile(epub)

        assertTrue("产物已删，备忘必须失效并重新压平", second.fresh)
        assertTrue(second.file.isFile)
    }

    private companion object {
        const val FILLER_BYTES = 256 * 1024

        /** 采样哈希上限（3 × 8KB）留出的余量。 */
        const val SAMPLE_LIMIT = 64L * 1024
    }
}

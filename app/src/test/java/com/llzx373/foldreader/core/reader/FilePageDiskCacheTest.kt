package com.llzx373.foldreader.core.reader

import android.graphics.Typeface
import com.llzx373.foldreader.core.format.BookContent
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class DiskTestBookContent(private val text: String) : BookContent {
    override val charCount: Long get() = text.length.toLong()
    override suspend fun read(range: LongRange): String {
        val from = range.first.coerceIn(0, text.length.toLong()).toInt()
        val to = (range.last + 1).coerceIn(0, text.length.toLong()).toInt()
        return text.substring(from, maxOf(from, to))
    }
}

private class DiskTestMeasurer(private val charWidthPx: Int = 10) : TextMeasurer {
    override fun measureLineBreaks(
        text: CharSequence,
        widthPx: Int,
        indentPx: Int,
        fontSizePx: Float,
        letterSpacingEm: Float,
        typeface: Typeface?,
    ): IntArray {
        if (text.isEmpty()) return IntArray(0)
        val charsPerLine = maxOf(1, (widthPx - indentPx) / charWidthPx)
        val out = ArrayList<Int>()
        var end = minOf(charsPerLine, text.length)
        out += end
        while (end < text.length) {
            end = minOf(end + charsPerLine, text.length)
            out += end
        }
        return out.toIntArray()
    }
}

class FilePageDiskCacheTest {

    private val config = LayoutConfig(
        fontSizeSp = 10f,
        lineSpacingMultiplier = 1f,
        marginLeftDp = 0f,
        marginTopDp = 0f,
        marginRightDp = 0f,
        marginBottomDp = 0f,
        firstLineIndentChars = 0,
    )

    private fun key(config: LayoutConfig = this.config, widthPx: Int = 600) =
        PaginatorKey(
            bookId = 7L,
            widthPx = widthPx,
            heightPx = 800,
            density = 2f,
            scaledDensity = 2f,
            config = config,
        )

    private fun tempDir(): File = Files.createTempDirectory("page-bounds-test").toFile()

    @Test
    fun `saved boundary index loads back`() {
        val dir = tempDir()
        val cache = FilePageDiskCache(dir)
        val bounds = longArrayOf(0L, 60L, 120L, 180L)
        cache.save(key(), charCount = 200L, bounds = bounds)
        val loaded = cache.load(key(), charCount = 200L)
        assertNotNull(loaded)
        assertTrue(bounds.contentEquals(loaded))
    }

    @Test
    fun `different key does not hit and keeps the original entry`() {
        val dir = tempDir()
        val cache = FilePageDiskCache(dir)
        val bounds = longArrayOf(0L, 60L)
        cache.save(key(), charCount = 200L, bounds = bounds)
        assertNull(cache.load(key(widthPx = 601), charCount = 200L))
        assertNull(cache.load(key(config = config.copy(fontSizeSp = 12f)), charCount = 200L))
        val loaded = cache.load(key(), charCount = 200L)
        assertNotNull(loaded)
        assertTrue(bounds.contentEquals(loaded))
    }

    @Test
    fun `avoidance mismatch invalidates and keeps the original entry`() {
        val dir = tempDir()
        val cache = FilePageDiskCache(dir)
        val bounds = longArrayOf(0L, 60L)
        cache.save(key(), charCount = 200L, bounds = bounds)
        assertNull(cache.load(key().copy(avoidance = PageAvoidance(oddTopLines = 1)), charCount = 200L))
        val loaded = cache.load(key(), charCount = 200L)
        assertNotNull(loaded)
        assertTrue(bounds.contentEquals(loaded))
    }

    @Test
    fun `char count mismatch invalidates and removes the file`() {
        val dir = tempDir()
        val cache = FilePageDiskCache(dir)
        cache.save(key(), charCount = 200L, bounds = longArrayOf(0L, 60L))
        assertNull(cache.load(key(), charCount = 201L))
        assertNull(cache.load(key(), charCount = 200L))
        assertTrue(dir.listFiles().orEmpty().none { it.name.endsWith(".bin") })
    }

    @Test
    fun `corrupt file returns null and is deleted`() {
        val dir = tempDir()
        val cache = FilePageDiskCache(dir)
        cache.save(key(), charCount = 200L, bounds = longArrayOf(0L, 60L))
        val file = dir.listFiles().orEmpty().single { it.name.endsWith(".bin") }
        file.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        assertNull(cache.load(key(), charCount = 200L))
        assertTrue(!file.exists())
    }

    @Test
    fun `paginator reloads persisted boundary index across instances`() = runBlocking {
        val dir = tempDir()
        val text = buildString {
            repeat(300) { i -> append("第${i}段内容".repeat(8)).append('\n') }
        }
        val k = key()

        fun newPaginator() = Paginator(
            content = DiskTestBookContent(text),
            config = config,
            measurer = DiskTestMeasurer(),
            widthPx = 600,
            heightPx = 800,
            density = 2f,
            scaledDensity = 2f,
            diskCache = FilePageDiskCache(dir),
            diskKey = k,
        )

        suspend fun paginateAll(p: Paginator): List<Page> {
            val pages = mutableListOf<Page>()
            var page = p.pageAt(0)
            pages += page
            while (page.charEnd < text.length.toLong()) {
                page = p.pageAt(page.charEnd)
                pages += page
            }
            return pages
        }

        val first = paginateAll(newPaginator())
        assertTrue(first.size > 1)
        // persistBounds 取第一个 Paginator 实例的边界快照
        val p1 = newPaginator()
        paginateAll(p1)
        p1.persistBounds()

        val second = paginateAll(newPaginator())
        assertEquals(first, second)
        Unit
    }
}

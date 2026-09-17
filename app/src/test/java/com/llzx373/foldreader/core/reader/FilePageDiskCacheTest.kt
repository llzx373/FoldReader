package com.llzx373.foldreader.core.reader

import android.graphics.Typeface
import com.llzx373.foldreader.core.format.BookContent
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    // ---- 增量追加 ----

    @Test
    fun `增量追加结果与整份写入完全一致`() {
        val incrementalDir = tempDir()
        val fullDir = tempDir()
        val incremental = FilePageDiskCache(incrementalDir)
        val full = FilePageDiskCache(fullDir)

        // 逐级增长的两份快照：先落 1 条，再追加到 3 条，与真实「每 64 页落盘一次」一致
        val partial = longArrayOf(0L, 60L)
        val all = longArrayOf(0L, 60L, 120L)
        incremental.save(key(), charCount = 200L, bounds = longArrayOf(0L))
        assertTrue(incremental.append(key(), charCount = 200L, allBounds = partial, persistedCount = 1))
        assertTrue(incremental.append(key(), charCount = 200L, allBounds = all, persistedCount = partial.size))

        full.save(key(), charCount = 200L, bounds = all)

        val loaded = incremental.load(key(), charCount = 200L)
        assertNotNull(loaded)
        assertTrue(all.contentEquals(loaded))
        // 逐字节相同：追加写不能改写头部布局
        val incrementalFile = incrementalDir.listFiles().orEmpty().single { it.name.endsWith(".bin") }
        val fullFile = fullDir.listFiles().orEmpty().single { it.name.endsWith(".bin") }
        assertEquals(fullFile.name, incrementalFile.name)
        assertTrue(fullFile.readBytes().contentEquals(incrementalFile.readBytes()))
    }

    @Test
    fun `追加条数与磁盘不符时拒绝增量`() {
        val dir = tempDir()
        val cache = FilePageDiskCache(dir)
        val all = longArrayOf(0L, 60L, 120L)
        cache.save(key(), charCount = 200L, bounds = longArrayOf(0L, 60L))

        // 磁盘上只有 2 条，却声称已落盘 1 条 → 前缀不一致，必须拒绝
        assertFalse(cache.append(key(), charCount = 200L, allBounds = all, persistedCount = 1))
        // 声称 3 条但实际只有 2 条 → 同样拒绝
        assertFalse(cache.append(key(), charCount = 200L, allBounds = all, persistedCount = 3))
        // 声称 2 条（与磁盘一致）→ 允许追加
        assertTrue(cache.append(key(), charCount = 200L, allBounds = all, persistedCount = 2))
        assertTrue(all.contentEquals(cache.load(key(), charCount = 200L)))
    }

    @Test
    fun `字符数变化时拒绝增量`() {
        val dir = tempDir()
        val cache = FilePageDiskCache(dir)
        cache.save(key(), charCount = 200L, bounds = longArrayOf(0L, 60L))

        assertFalse(
            cache.append(key(), charCount = 201L, allBounds = longArrayOf(0L, 60L, 120L), persistedCount = 2),
        )
    }

    @Test
    fun `文件不存在或排版不同时拒绝增量`() {
        val dir = tempDir()
        val cache = FilePageDiskCache(dir)

        assertFalse(cache.append(key(), charCount = 200L, allBounds = longArrayOf(0L, 60L), persistedCount = 1))

        cache.save(key(), charCount = 200L, bounds = longArrayOf(0L, 60L))
        // 版式不同的 key 落在别的文件上，不能拿它的条数去追加
        assertFalse(
            cache.append(
                key(config = config.copy(fontSizeSp = 12f)),
                charCount = 200L,
                allBounds = longArrayOf(0L, 60L, 120L),
                persistedCount = 2,
            ),
        )
    }

    @Test
    fun `无新增时追加是空操作`() {
        val dir = tempDir()
        val cache = FilePageDiskCache(dir)
        val bounds = longArrayOf(0L, 60L)
        cache.save(key(), charCount = 200L, bounds = bounds)
        val before = dir.listFiles().orEmpty().single { it.name.endsWith(".bin") }.readBytes()

        assertTrue(cache.append(key(), charCount = 200L, allBounds = bounds, persistedCount = 2))

        val after = dir.listFiles().orEmpty().single { it.name.endsWith(".bin") }.readBytes()
        assertTrue(before.contentEquals(after))
    }

    @Test
    fun `中断的追加不会破坏已落盘内容`() {
        val dir = tempDir()
        val cache = FilePageDiskCache(dir)
        cache.save(key(), charCount = 200L, bounds = longArrayOf(0L, 60L))
        val file = dir.listFiles().orEmpty().single { it.name.endsWith(".bin") }

        // 模拟「数据写了一半就崩溃」：尾部多出一段无效字节，条数仍是旧的 2
        val headerAndBody = file.readBytes()
        file.appendBytes(longArrayOf(999L, 888L).let { longs ->
            java.nio.ByteBuffer.allocate(longs.size * 8).also { buf ->
                longs.forEach { buf.putLong(it) }
            }.array()
        })

        // 条数未更新，读出来的仍是旧内容
        val loaded = cache.load(key(), charCount = 200L)
        assertNotNull(loaded)
        assertTrue(longArrayOf(0L, 60L).contentEquals(loaded))
        assertTrue(headerAndBody.isNotEmpty())
    }

    @Test
    fun `按书删除只影响目标书籍`() {
        val dir = tempDir()
        val cache = FilePageDiskCache(dir)
        cache.save(key(), charCount = 200L, bounds = longArrayOf(0L, 60L))
        cache.save(
            key().copy(bookId = 9L),
            charCount = 200L,
            bounds = longArrayOf(0L, 60L),
        )
        cache.save(
            key(config = config.copy(fontSizeSp = 12f)),
            charCount = 200L,
            bounds = longArrayOf(0L, 60L),
        )
        assertEquals(3, dir.listFiles().orEmpty().count { it.name.endsWith(".bin") })

        cache.deleteForBook(7L)

        assertEquals(1, dir.listFiles().orEmpty().count { it.name.endsWith(".bin") })
        assertNull(cache.load(key(), charCount = 200L))
        assertNotNull(cache.load(key().copy(bookId = 9L), charCount = 200L))
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

    @Test
    fun `周期性落盘首次整份写之后都走增量`() = runBlocking {
        val dir = tempDir()
        val cache = RecordingDiskCache(FilePageDiskCache(dir))
        val text = buildString {
            repeat(200) { i -> append("第${i}段内容".repeat(8)).append('\n') }
        }
        val k = key()
        val paginator = Paginator(
            content = DiskTestBookContent(text),
            config = config,
            measurer = DiskTestMeasurer(),
            widthPx = 600,
            heightPx = 800,
            density = 2f,
            scaledDensity = 2f,
            diskCache = cache,
            diskKey = k,
        )

        var page = paginator.pageAt(0)
        paginator.persistBounds()
        assertEquals("首次落盘前磁盘上什么都没有，必须整份写", 1, cache.saves)
        assertEquals(0, cache.appends)

        repeat(30) {
            page = paginator.pageAt(page.charEnd)
            paginator.persistBounds()
        }

        assertEquals("首次之后不该再整份重写", 1, cache.saves)
        assertTrue("后续落盘应走增量追加", cache.appends > 0)

        val persisted = FilePageDiskCache(dir).load(k, text.length.toLong())
        assertNotNull(persisted)
        assertEquals(paginator.boundaryPageCount, persisted!!.size)
    }
}

/** 记录 save/append 次数：验证周期性落盘确实从整份重写退化为增量追加。 */
private class RecordingDiskCache(private val delegate: PageDiskCache) : PageDiskCache {
    var saves = 0
        private set
    var appends = 0
        private set

    override fun load(key: PaginatorKey, charCount: Long): LongArray? = delegate.load(key, charCount)

    override fun save(key: PaginatorKey, charCount: Long, bounds: LongArray) {
        saves++
        delegate.save(key, charCount, bounds)
    }

    override fun append(
        key: PaginatorKey,
        charCount: Long,
        allBounds: LongArray,
        persistedCount: Int,
    ): Boolean {
        val ok = delegate.append(key, charCount, allBounds, persistedCount)
        if (ok) appends++
        return ok
    }

    override fun deleteForBook(bookId: Long) = delegate.deleteForBook(bookId)
}

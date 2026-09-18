package com.llzx373.foldreader.core.comic

import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ComicExtractionStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val page1 = ComicTestImages.png(20, 20)
    private val page2 = ComicTestImages.jpeg(30, 30)

    private fun store() = ComicExtractionStore(temp.newFolder("comics"))

    private fun write(dir: File, name: String, bytes: ByteArray): File =
        File(dir, name).also { it.writeBytes(bytes) }

    @Test
    fun `解压一次后命中缓存`() {
        val store = store()
        val calls = AtomicInteger()

        val first = store.ensureExtracted("h1") { dir ->
            calls.incrementAndGet()
            listOf("1.png" to write(dir, "p0", page1), "2.png" to write(dir, "p1", page2))
        }
        val second = store.ensureExtracted("h1") { error("命中缓存时不应再解压") }

        assertEquals(1, calls.get())
        assertEquals(listOf("000000_1.png", "000001_2.png"), first.map { it.name })
        assertEquals(first, second)
    }

    @Test
    fun `页序由原始条目名决定而不是写盘顺序`() {
        val store = store()

        val pages = store.ensureExtracted("h1") { dir ->
            // 故意按字典序写：10 排在 2 前面
            listOf(
                "10.png" to write(dir, "a", page2),
                "1.png" to write(dir, "b", page1),
                "2.png" to write(dir, "c", page2),
            )
        }

        assertEquals(
            listOf("000000_1.png", "000001_2.png", "000002_10.png"),
            pages.map { it.name },
        )
        assertArrayEquals(page1, pages[0].readBytes())
        // 缓存命中路径（按文件名排序）必须给出同样的顺序
        val cached = store.cachedPages("h1")!!
        assertEquals(pages.map { it.name }, cached.map { it.name })
    }

    @Test
    fun `版本不符时重新解压`() {
        val store = store()
        store.ensureExtracted("h1") { dir -> listOf("1.png" to write(dir, "p0", page1)) }

        File(store.cachePagesDir("h1").parentFile, ".extract-version").writeText("999")
        assertNull(store.cachedPages("h1"))

        var calls = 0
        store.ensureExtracted("h1") { dir ->
            calls++
            listOf("1.png" to write(dir, "p0", page1))
        }
        assertEquals(1, calls)
    }

    @Test
    fun `解压不出图片时报错并清掉半成品目录`() {
        val store = store()

        assertThrows(IOException::class.java) {
            store.ensureExtracted("h1") { dir ->
                write(dir, "readme.txt", "x".toByteArray())
                emptyList()
            }
        }
        assertFalse(store.cachePagesDir("h1").exists())
        assertNull(store.cachedPages("h1"))
    }

    @Test
    fun `sweep 清掉不在书架上的缓存但保留本地副本`() {
        val store = store()
        store.ensureExtracted("live") { dir -> listOf("1.png" to write(dir, "p", page1)) }
        store.ensureExtracted("dead") { dir -> listOf("1.png" to write(dir, "p", page1)) }
        store.ensureLocalCopy("live") { dir -> listOf("1.png" to write(dir, "p", page1)) }

        val deleted = store.sweep(setOf("live"))

        assertEquals(1, deleted)
        assertNotNull(store.cachedPages("live"))
        assertNull(store.cachedPages("dead"))
        assertNotNull(store.localPages("live"))
    }

    @Test
    fun `本地副本重复生成时复用已有结果`() {
        val store = store()
        val calls = AtomicInteger()

        store.ensureLocalCopy("h1") { dir ->
            calls.incrementAndGet()
            listOf("1.png" to write(dir, "p", page1))
        }
        store.ensureLocalCopy("h1") { error("已有本地副本时不应重新解包") }

        assertEquals(1, calls.get())
        assertNotNull(store.localPages("h1"))
        // 本地副本与缓存互不影响：缓存此时仍是空的
        assertNull(store.cachedPages("h1"))
    }

    @Test
    fun `删书清掉缓存与本地副本`() {
        val store = store()
        store.ensureExtracted("h1") { dir -> listOf("1.png" to write(dir, "p", page1)) }
        store.ensureLocalCopy("h1") { dir -> listOf("1.png" to write(dir, "p", page1)) }

        store.deleteAll("h1")

        assertNull(store.cachedPages("h1"))
        assertNull(store.localPages("h1"))
    }

    @Test
    fun `页名打平并替换非法字符`() {
        val store = store()

        assertEquals("ch1_001.jpg", store.pageFileName("ch1/001.jpg"))
        assertTrue(store.pageFileName("a:b*.jpg").none { it in charArrayOf(':', '*') })
    }
}

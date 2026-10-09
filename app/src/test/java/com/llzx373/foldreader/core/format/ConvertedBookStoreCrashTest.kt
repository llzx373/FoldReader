package com.llzx373.foldreader.core.format

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 压平缓存落盘顺序的崩溃自洽性：`.version` 必须是最后落笔。
 * 崩溃留下的「正文/sidecar 已就位、唯独 version 未写（或还是旧版本）」
 * 必须被判失效重压平，而不是静默读到错配的缓存。
 */
class ConvertedBookStoreCrashTest {

    private fun tempDir(): File = Files.createTempDirectory("converted-store-crash").toFile()

    private fun writeSidecars(dir: File, hash: String) {
        File(dir, "$hash.txt").writeText("新正文", Charsets.UTF_8)
        File(dir, "$hash.toc").writeText("", Charsets.UTF_8)
        File(dir, "$hash.anchors").writeText("", Charsets.UTF_8)
        File(dir, "$hash.pages").writeText("", Charsets.UTF_8)
        File(dir, "$hash.spans").writeText("", Charsets.UTF_8)
    }

    @Test
    fun `崩溃中间态 version 未落笔时缓存判失效`() {
        val dir = tempDir()
        val store = ConvertedBookStore(dir)
        writeSidecars(dir, "h1")
        // 唯独 .version 缺失：等同于 sidecar/正文已写好、写版本号之前崩溃
        assertNull("缺 version 的中间态不得命中缓存", store.cached("h1"))
    }

    @Test
    fun `崩溃中间态 version 仍是旧版本时缓存判失效`() {
        val dir = tempDir()
        val store = ConvertedBookStore(dir)
        writeSidecars(dir, "h2")
        File(dir, "h2.version").writeText("${ConvertedBookStore.FLATTEN_VERSION - 1}", Charsets.UTF_8)
        assertNull("旧版本号的中间态不得命中缓存", store.cached("h2"))
    }

    @Test
    fun `中间态判失效后重压平恢复正常`() {
        val dir = tempDir()
        val store = ConvertedBookStore(dir)
        writeSidecars(dir, "h3")

        val stored = store.store("h3") { out ->
            out.writeText("重压平正文", Charsets.UTF_8)
            FlattenContent(emptyList())
        }

        assertTrue(stored.fresh)
        assertEquals("重压平正文", stored.file.readText(Charsets.UTF_8))
        assertNotNull(store.cached("h3"))
    }

    @Test
    fun `正常落盘后缓存有效且内容一致`() {
        val dir = tempDir()
        val store = ConvertedBookStore(dir)

        store.store("h4") { out ->
            out.writeText("正文", Charsets.UTF_8)
            FlattenContent(chapters = listOf(Chapter("第一章", 0, 2)))
        }

        val cached = store.cached("h4")
        assertNotNull(cached)
        assertEquals("正文", cached!!.file.readText(Charsets.UTF_8))
        assertEquals(listOf(Chapter("第一章", 0, 2)), cached.chapters)
    }
}

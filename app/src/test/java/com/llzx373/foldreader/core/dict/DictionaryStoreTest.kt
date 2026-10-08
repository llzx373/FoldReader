package com.llzx373.foldreader.core.dict

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DictionaryStoreTest {

    private fun tempDir(): File =
        java.nio.file.Files.createTempDirectory("dict-store-test").toFile()

    /** 生成一部内存词典的三件套字节。 */
    private fun fixtureFiles(
        stem: String,
        entries: List<Pair<String, String>>,
        gzipDict: Boolean = false,
    ): Map<String, ByteArray> {
        val dictOut = ByteArrayOutputStream()
        val idxOut = ByteArrayOutputStream()
        entries.forEach { (word, def) ->
            val defBytes = def.toByteArray(Charsets.UTF_8)
            val offset = dictOut.size().toLong()
            dictOut.write(defBytes)
            idxOut.write(word.toByteArray(Charsets.UTF_8))
            idxOut.write(0)
            for (shift in listOf(24, 16, 8, 0)) {
                idxOut.write(((offset ushr shift) and 0xFF).toInt())
            }
            for (shift in listOf(24, 16, 8, 0)) {
                idxOut.write(((defBytes.size.toLong() ushr shift) and 0xFF).toInt())
            }
        }
        val dictBytes = dictOut.toByteArray()
        val dictFileBytes = if (gzipDict) {
            ByteArrayOutputStream().also { GZIPOutputStream(it).use { g -> g.write(dictBytes) } }.toByteArray()
        } else {
            dictBytes
        }
        val ifo = """
            StarDict's dict ifo file
            version=2.4.2
            wordcount=${entries.size}
            bookname=$stem
            sametypesequence=m
        """.trimIndent().toByteArray()
        return mapOf(
            "$stem.ifo" to ifo,
            "$stem.idx" to idxOut.toByteArray(),
            (if (gzipDict) "$stem.dict.dz" else "$stem.dict") to dictFileBytes,
        )
    }

    private fun openFileOf(files: Map<String, ByteArray>): (String) -> InputStream? =
        { name -> files[name]?.let { ByteArrayInputStream(it) } }

    @Test
    fun `导入三件套后列表可见且查词命中`() = runTest {
        val dir = tempDir()
        try {
            val store = DictionaryStore(dir)
            val info = store.import("oxford", openFileOf(fixtureFiles("oxford", listOf("apple" to "n. 苹果"))))
            assertEquals("oxford", info.bookName)
            assertEquals(1, store.list().size)

            val service = DictionaryLookupService(dir)
            val hit = service.lookup("apple")
            assertTrue(hit is LookupOutcome.LocalHit)
            assertEquals("n. 苹果", (hit as LookupOutcome.LocalHit).definition)
            assertEquals("oxford", hit.dictName)
            service.close()
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `dict dz 导入时解压且可查`() = runTest {
        val dir = tempDir()
        try {
            val store = DictionaryStore(dir)
            store.import("gz", openFileOf(fixtureFiles("gz", listOf("zip" to "n. 拉链"), gzipDict = true)))
            // dict.dz 不应原样保留，解压成 .dict
            val dictDir = File(dir, "gz")
            assertTrue(File(dictDir, "gz.dict").isFile)
            assertFalse(File(dictDir, "gz.dict.dz").exists())
            val service = DictionaryLookupService(dir)
            assertEquals("n. 拉链", (service.lookup("zip") as LookupOutcome.LocalHit).definition)
            service.close()
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `缺 idx 报错且不留半成品目录`() {
        val dir = tempDir()
        try {
            val store = DictionaryStore(dir)
            val files = fixtureFiles("bad", listOf("a" to "b")) - "bad.idx"
            try {
                store.import("bad", openFileOf(files))
                fail("缺 idx 应该报错")
            } catch (e: DictionaryStore.ImportException) {
                assertTrue(e.message!!.contains("bad.idx"))
            }
            assertTrue(store.list().isEmpty())
            assertFalse(File(dir, "bad").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `MDict 文件明确报不支持`() {
        val dir = tempDir()
        try {
            val store = DictionaryStore(dir)
            val files = fixtureFiles("m", listOf("a" to "b")) + ("m.mdx" to byteArrayOf(1, 2, 3))
            try {
                store.import("m", openFileOf(files))
                fail("mdx 应该报错")
            } catch (e: DictionaryStore.ImportException) {
                assertTrue(e.message!!.contains("MDict"))
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `删除词典后列表清空`() {
        val dir = tempDir()
        try {
            val store = DictionaryStore(dir)
            val info = store.import("oxford", openFileOf(fixtureFiles("oxford", listOf("a" to "b"))))
            assertTrue(store.delete(info.id))
            assertTrue(store.list().isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `findStems 只认三件套齐全的`() {
        val stems = DictionaryStore.findStems(
            listOf("oxford.ifo", "oxford.idx", "oxford.dict.dz", "broken.ifo", "broken.idx", "readme.txt"),
        )
        assertEquals(listOf("oxford"), stems)
    }

    @Test
    fun `非法词干安全化为目录名`() {
        assertEquals("a_b", DictionaryStore.sanitize("a/b"))
        assertEquals("", DictionaryStore.sanitize("..."))
    }
}

class DictionaryLookupServiceTest {

    private fun tempDir(): File =
        java.nio.file.Files.createTempDirectory("dict-lookup-test").toFile()

    private fun installDict(dir: File, stem: String, entries: List<Pair<String, String>>) {
        val store = DictionaryStore(dir)
        val dictOut = ByteArrayOutputStream()
        val idxOut = ByteArrayOutputStream()
        entries.forEach { (word, def) ->
            val defBytes = def.toByteArray(Charsets.UTF_8)
            val offset = dictOut.size().toLong()
            dictOut.write(defBytes)
            idxOut.write(word.toByteArray(Charsets.UTF_8))
            idxOut.write(0)
            for (shift in listOf(24, 16, 8, 0)) idxOut.write(((offset ushr shift) and 0xFF).toInt())
            for (shift in listOf(24, 16, 8, 0)) {
                idxOut.write(((defBytes.size.toLong() ushr shift) and 0xFF).toInt())
            }
        }
        val ifo = """
            StarDict's dict ifo file
            version=2.4.2
            wordcount=${entries.size}
            bookname=$stem
        """.trimIndent()
        store.import(stem) { name ->
            when (name) {
                "$stem.ifo" -> ByteArrayInputStream(ifo.toByteArray())
                "$stem.idx" -> ByteArrayInputStream(idxOut.toByteArray())
                "$stem.dict" -> ByteArrayInputStream(dictOut.toByteArray())
                else -> null
            }
        }
    }

    @Test
    fun `本地优先——命中时不关心 AI 是否可用`() = runTest {
        val dir = tempDir()
        try {
            installDict(dir, "d", listOf("apple" to "n. 苹果"))
            val service = DictionaryLookupService(dir, aiAvailable = { true })
            val outcome = service.lookup("apple")
            assertTrue(outcome is LookupOutcome.LocalHit)
            service.close()
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `未命中回落决策随 AI 可用性`() = runTest {
        val dir = tempDir()
        try {
            installDict(dir, "d", listOf("apple" to "n. 苹果"))
            DictionaryLookupService(dir, aiAvailable = { true }).use { service ->
                val miss = service.lookup("orange")
                assertTrue(miss is LookupOutcome.Miss)
                assertTrue((miss as LookupOutcome.Miss).aiAvailable)
            }
            DictionaryLookupService(dir, aiAvailable = { false }).use { service ->
                val miss = service.lookup("orange")
                assertFalse((miss as LookupOutcome.Miss).aiAvailable)
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `无词典时一律未命中且零 IO 负担`() = runTest {
        val dir = tempDir()
        try {
            val service = DictionaryLookupService(dir, aiAvailable = { false })
            assertFalse(service.hasDictionaries())
            val miss = service.lookup("apple")
            assertTrue(miss is LookupOutcome.Miss)
            service.close()
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `normalize 去标点且超长不查`() {
        val service = DictionaryLookupService(tempDir())
        assertEquals("apple", service.normalize("  \"apple\", "))
        assertNull(service.normalize("   "))
        assertNull(service.normalize("x".repeat(33)))
        assertEquals("x".repeat(32), service.normalize("x".repeat(32)))
        service.close()
    }

    @Test
    fun `多词典按序首个命中`() = runTest {
        val dir = tempDir()
        try {
            installDict(dir, "a-dict", listOf("apple" to "A 的释义"))
            installDict(dir, "b-dict", listOf("apple" to "B 的释义", "pear" to "n. 梨"))
            val service = DictionaryLookupService(dir)
            // 列表按书名排序：a-dict 在前
            assertEquals("A 的释义", (service.lookup("apple") as LookupOutcome.LocalHit).definition)
            // 第二部独有的词也能命中
            assertEquals("n. 梨", (service.lookup("pear") as LookupOutcome.LocalHit).definition)
            service.close()
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `invalidate 后重新扫描——新装词典立即可查`() = runTest {
        val dir = tempDir()
        try {
            val service = DictionaryLookupService(dir)
            assertTrue(service.lookup("apple") is LookupOutcome.Miss)
            installDict(dir, "d", listOf("apple" to "n. 苹果"))
            // 缓存未失效时仍查不到（词典清单已缓存为空）
            assertTrue(service.lookup("apple") is LookupOutcome.Miss)
            service.invalidate()
            assertTrue(service.lookup("apple") is LookupOutcome.LocalHit)
            service.close()
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `大小写回落——选中 Apple 命中小写词目`() = runTest {
        val dir = tempDir()
        try {
            installDict(dir, "d", listOf("apple" to "n. 苹果"))
            val service = DictionaryLookupService(dir)
            assertTrue(service.lookup("Apple") is LookupOutcome.LocalHit)
            service.close()
        } finally {
            dir.deleteRecursively()
        }
    }
}

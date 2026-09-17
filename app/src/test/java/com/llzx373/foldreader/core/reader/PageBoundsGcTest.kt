package com.llzx373.foldreader.core.reader

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * page_bounds/ 只增不减的两个来源：删书不清理、改版式不停新增。
 * GC 必须精确区分「孤儿」与「同书旧版式」，不能误删仍在书架上的书。
 */
class PageBoundsGcTest {

    private fun tempDir(): File = Files.createTempDirectory("page-bounds-gc").toFile()

    /** 造一个页边界文件，[ageMs] 越大越旧。 */
    private fun boundsFile(dir: File, bookId: Long, hash: Int, ageMs: Long = 0L): File {
        val file = File(dir, "${FILE_PREFIX}${bookId}_$hash$FILE_SUFFIX")
        file.writeBytes(byteArrayOf(1, 2, 3))
        if (ageMs > 0) file.setLastModified(System.currentTimeMillis() - ageMs)
        return file
    }

    private fun remaining(dir: File): Set<String> =
        dir.listFiles().orEmpty().map { it.name }.toSet()

    @Test
    fun `孤儿书号的文件被清理`() {
        val dir = tempDir()
        boundsFile(dir, bookId = 1L, hash = 11)
        boundsFile(dir, bookId = 2L, hash = 22)
        boundsFile(dir, bookId = 3L, hash = 33)

        val deleted = PageBoundsGc.sweep(dir, liveBookIds = setOf(2L))

        assertEquals(2, deleted)
        assertEquals(setOf("${FILE_PREFIX}2_22$FILE_SUFFIX"), remaining(dir))
    }

    @Test
    fun `同一本书仅保留最近若干份版式`() {
        val dir = tempDir()
        val kept = boundsFile(dir, bookId = 5L, hash = 100, ageMs = 1_000)
        boundsFile(dir, bookId = 5L, hash = 200, ageMs = 2_000)
        boundsFile(dir, bookId = 5L, hash = 300, ageMs = 3_000)
        boundsFile(dir, bookId = 5L, hash = 400, ageMs = 4_000)

        val deleted = PageBoundsGc.sweep(dir, liveBookIds = setOf(5L), keepVariantsPerBook = 2)

        assertEquals(2, deleted)
        assertEquals(
            setOf("${FILE_PREFIX}5_100$FILE_SUFFIX", "${FILE_PREFIX}5_200$FILE_SUFFIX"),
            remaining(dir),
        )
        assertTrue(kept.isFile)
    }

    @Test
    fun `版式份数未超上限时不动`() {
        val dir = tempDir()
        boundsFile(dir, bookId = 7L, hash = 1)
        boundsFile(dir, bookId = 7L, hash = 2)

        assertEquals(0, PageBoundsGc.sweep(dir, liveBookIds = setOf(7L), keepVariantsPerBook = 4))
        assertEquals(2, remaining(dir).size)
    }

    @Test
    fun `书架为空时清空全部`() {
        val dir = tempDir()
        boundsFile(dir, bookId = 1L, hash = 1)
        boundsFile(dir, bookId = 2L, hash = 2)

        assertEquals(2, PageBoundsGc.sweep(dir, liveBookIds = emptySet()))
        assertTrue(remaining(dir).isEmpty())
    }

    @Test
    fun `无关键字的文件与目录缺失都不受影响`() {
        val dir = tempDir()
        File(dir, "notes.txt").writeText("x")
        File(dir, "bounds_broken.bin").writeText("x")
        boundsFile(dir, bookId = 1L, hash = 1)

        assertEquals(1, PageBoundsGc.sweep(dir, liveBookIds = emptySet()))

        val others = remaining(dir)
        assertTrue(others.contains("notes.txt"))
        assertTrue(others.contains("bounds_broken.bin"))
        assertFalse(others.any { it.startsWith("${FILE_PREFIX}1_") })

        assertEquals(0, PageBoundsGc.sweep(File(dir, "missing"), emptySet()))
    }

    @Test
    fun `负哈希的文件名也能解析出书号`() {
        assertEquals(42L, boundsBookIdOf("${FILE_PREFIX}42_-123456$FILE_SUFFIX"))
        assertEquals(1L, boundsBookIdOf("${FILE_PREFIX}1_7$FILE_SUFFIX"))
        assertNull(boundsBookIdOf("notes.txt"))
        assertNull(boundsBookIdOf("${FILE_PREFIX}abc_7$FILE_SUFFIX"))
        assertNull(boundsBookIdOf("${FILE_PREFIX}1$FILE_SUFFIX"))
    }
}

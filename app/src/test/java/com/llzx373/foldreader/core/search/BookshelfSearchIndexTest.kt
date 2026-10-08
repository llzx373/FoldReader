package com.llzx373.foldreader.core.search

import com.llzx373.foldreader.core.data.db.BookEntity
import com.llzx373.foldreader.core.data.db.BookFormat
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** M34 书架全文搜索：索引文件管理与命中口径。 */
class BookshelfSearchIndexTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun book(
        id: Long,
        format: BookFormat = BookFormat.TXT,
        cleanedFilePath: String? = null,
        hash: String = "hash$id",
    ) = BookEntity(
        id = id,
        title = "书$id",
        author = null,
        fileUri = "content://book/$id",
        contentHash = hash,
        format = format,
        totalChars = 1000,
        encoding = "UTF-8",
        importedAt = id,
        lastReadAt = null,
        cleanedFilePath = cleanedFilePath,
    )

    @Test
    fun `签名覆盖索引范围——漫画与无副本扫描件不参与`() {
        val index = BookshelfSearchIndex(tmp.newFolder())
        assertNotNull(index.signatureOf(book(1)))
        assertNotNull(index.signatureOf(book(2, format = BookFormat.MARKDOWN)))
        assertNotNull(index.signatureOf(book(3, format = BookFormat.EPUB, cleanedFilePath = "/x/y.txt")))
        // 没有压平产物的 EPUB / 漫画：没有正文可索引
        assertNull(index.signatureOf(book(4, format = BookFormat.EPUB)))
        assertNull(index.signatureOf(book(5, format = BookFormat.COMIC)))
        assertNull(index.signatureOf(book(6, format = BookFormat.PDF)))
        // 重洗后签名变化（旧索引成孤儿）
        val before = index.signatureOf(book(7))
        val after = index.signatureOf(book(7, cleanedFilePath = "/x/c.txt"))
        assertNotNull(before)
        assertTrue(before != after)
    }

    @Test
    fun `写入后命中偏移即原文偏移——供阅读器直接跳转`() = runBlocking {
        val dir = tmp.newFolder()
        val index = BookshelfSearchIndex(dir)
        val text = "第一段没有目标词。第二段里出现了目标关键词，后面还有一句。第三段目标关键词又一次出现。"
        index.write(1L, index.signatureOf(book(1))!!, text)
        assertEquals(setOf(1L), index.indexedBookIds())

        val results = searchShelfIndex(index, listOf(book(1)), "目标关键词")
        assertEquals(1, results.size)
        val result = results[0]
        assertEquals(2, result.totalHits)
        // 偏移指回原文命中起点（阅读器 charOffset 语义）
        result.previews.forEach { hit ->
            assertEquals(
                "目标关键词",
                text.substring(hit.offset.toInt(), hit.offset.toInt() + hit.matchLength),
            )
        }
    }

    @Test
    fun `按命中数降序且无命中不上榜`() = runBlocking {
        val index = BookshelfSearchIndex(tmp.newFolder())
        index.write(1L, index.signatureOf(book(1))!!, "甲词")
        index.write(2L, index.signatureOf(book(2))!!, "甲词 甲词 甲词")
        index.write(3L, index.signatureOf(book(3))!!, "完全没有")

        val results = searchShelfIndex(index, listOf(book(1), book(2), book(3)), "甲词")
        assertEquals(listOf(2L, 1L), results.map { it.bookId })
    }

    @Test
    fun `孤儿清理与按书删除`() {
        val dir = tmp.newFolder()
        val index = BookshelfSearchIndex(dir)
        index.write(1L, index.signatureOf(book(1))!!, "内容一")
        index.write(2L, index.signatureOf(book(2))!!, "内容二")
        assertEquals(2, index.indexedBookIds().size)

        index.deleteBook(1L)
        assertEquals(setOf(2L), index.indexedBookIds())
        assertFalse(File(dir, "1.x.txt").exists())

        index.clear()
        assertTrue(index.indexedBookIds().isEmpty())
        assertEquals(0L, index.totalBytes())
    }

    @Test
    fun `解码显式编码优先且剥掉 BOM`() {
        val gbk = "汉字编码".toByteArray(charset("GBK"))
        assertEquals("汉字编码", decodeIndexText(gbk, "GBK"))
        // 空串自动探测（这段 GBK 字节不是合法 UTF-8）
        assertEquals("汉字编码", decodeIndexText(gbk, ""))
        // UTF-8 BOM 不进正文（阅读器坐标不含 BOM）
        val bomUtf8 = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            "正文".toByteArray(Charsets.UTF_8)
        assertEquals("正文", decodeIndexText(bomUtf8, ""))
    }
}

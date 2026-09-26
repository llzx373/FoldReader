package com.llzx373.foldreader.core.ocr

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * OCR 文本层（选字/搜索）与缓存读写：选区命中、snippet 截取、版本失效、删书清理。
 */
class OcrTextLayerTest {

    private fun line(text: String, l: Float, t: Float, r: Float, b: Float) =
        OcrTextLine(text, OcrRect(l, t, r, b), 0.9f)

    private val page0 = OcrPage(
        listOf(
            line("第一章 风雪夜归人", 0.1f, 0.05f, 0.8f, 0.09f),
            line("他从风雪中走来，身后没有脚印。", 0.1f, 0.12f, 0.8f, 0.16f),
            line("只有一盏灯还亮着。", 0.1f, 0.19f, 0.6f, 0.23f),
        ),
    )
    private val page1 = OcrPage(
        listOf(line("风雪又起。", 0.1f, 0.05f, 0.5f, 0.09f)),
    )

    @Test
    fun `选区命中相交行并返回并集框与拼接文字`() {
        // 框住前两行
        val selection = OcrRect(0.05f, 0.04f, 0.85f, 0.17f)
        val (union, text) = OcrTextLayer.select(page0.lines, selection)!!
        assertEquals("第一章 风雪夜归人他从风雪中走来，身后没有脚印。", text)
        assertEquals(0.1f, union.left, 0.001f)
        assertEquals(0.8f, union.right, 0.001f)
        assertEquals(0.16f, union.bottom, 0.01f) // 只到第二行底
    }

    @Test
    fun `选区无命中返回 null`() {
        val selection = OcrRect(0.05f, 0.8f, 0.5f, 0.9f)
        assertNull(OcrTextLayer.select(page0.lines, selection))
    }

    @Test
    fun `跨页搜索命中页号计数与大小写不敏感`() {
        val hits = OcrTextLayer.search(mapOf(0 to page0, 1 to page1), "风雪")
        assertEquals(2, hits.size)
        assertEquals(0, hits[0].first)
        assertEquals(2, hits[0].second) // 页 0 命中两处
        assertEquals(1, hits[1].first)
        assertEquals(1, hits[1].second)
        assertTrue(hits[0].third.contains("风雪"))
    }

    @Test
    fun `snippet 截取命中点上下文`() {
        val page = OcrPage(listOf(line("abcdefghijklmnopqrstuvwxyz关键字0123456789abcdefghijklmnopqrstuvwxyz", 0f, 0f, 1f, 0.1f)))
        val hits = OcrTextLayer.search(mapOf(0 to page), "关键字")
        assertEquals(1, hits.size)
        val snippet = hits[0].third
        assertTrue(snippet.startsWith("ghijklmnopqrstuvwxyz")) // 前 20 字
        assertTrue(snippet.endsWith("0123456789abcdefghijklmnopqrst")) // 命中点后 30 字
    }

    @Test
    fun `空白查询返回空`() {
        assertTrue(OcrTextLayer.search(mapOf(0 to page0), "  ").isEmpty())
    }
}

/**
 * OCR 页编解码与 PdfOcrStore 缓存：往返一致、版本不符失效、删书清理。
 */
class PdfOcrStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var store: PdfOcrStore
    private val bookId = 3L

    @Before
    fun setUp() {
        store = PdfOcrStore(tempFolder.newFolder("pdf_ocr"))
    }

    private fun page(text: String) =
        OcrPage(listOf(OcrTextLine(text, OcrRect(0.1f, 0.1f, 0.9f, 0.2f), 0.87f)))

    @Test
    fun `保存读取往返一致`() {
        store.save(bookId, 5, page("雪"))
        val loaded = store.load(bookId, 5)
        assertNotNull(loaded)
        assertEquals("雪", loaded!!.lines[0].text)
        assertEquals(0.87f, loaded.lines[0].confidence, 0.001f)
        assertEquals(0.1f, loaded.lines[0].box.left, 0.001f)
        assertTrue(store.hasAny(bookId))
    }

    @Test
    fun `未缓存页返回 null`() {
        assertNull(store.load(bookId, 99))
        assertFalse(store.hasAny(bookId))
    }

    @Test
    fun `版本不符的缓存整体失效`() {
        store.save(bookId, 0, page("旧"))
        val file = File(File(tempFolder.root, "pdf_ocr/$bookId"), "0.ocr.json")
        file.writeText(file.readText().replace("\"version\":${OcrPageCodec.OCR_VERSION}", "\"version\":0"))
        assertNull(store.load(bookId, 0))
    }

    @Test
    fun `损坏文件视为未缓存不抛异常`() {
        val dir = File(tempFolder.root, "pdf_ocr/$bookId").apply { mkdirs() }
        File(dir, "1.ocr.json").writeText("{not json")
        assertNull(store.load(bookId, 1))
    }

    @Test
    fun `删书清理全部页缓存`() {
        store.save(bookId, 0, page("a"))
        store.save(bookId, 1, page("b"))
        store.deleteBook(bookId)
        assertNull(store.load(bookId, 0))
        assertFalse(store.hasAny(bookId))
    }

    @Test
    fun `气泡编解码往返一致`() {
        val bubbles = listOf(
            OcrBubble(
                0,
                OcrRect(0.1f, 0.1f, 0.5f, 0.4f),
                listOf(OcrTextLine("台詞", OcrRect(0.2f, 0.15f, 0.4f, 0.2f), 0.92f)),
                0.85f,
            ),
        )
        val decoded = OcrPageCodec.decodeBubbles(OcrPageCodec.encodeBubbles(bubbles))!!
        assertEquals(1, decoded.size)
        assertEquals("台詞", decoded[0].text)
        assertEquals(0.85f, decoded[0].confidence, 0.001f)
        assertEquals(0.5f, decoded[0].rect.right, 0.001f)
    }
}

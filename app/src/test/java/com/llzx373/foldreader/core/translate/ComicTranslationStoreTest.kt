package com.llzx373.foldreader.core.translate

import com.llzx373.foldreader.core.ocr.OcrBubble
import com.llzx373.foldreader.core.ocr.OcrRect
import com.llzx373.foldreader.core.ocr.OcrTextLine
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 漫画页级产物存储：OCR 缓存与译文分文件、按语言共存、重译不丢 OCR、
 * 单页作废、删书清理、损坏容错。
 */
class ComicTranslationStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var store: ComicTranslationStore
    private val bookId = 9L

    @Before
    fun setUp() {
        store = ComicTranslationStore(tempFolder.newFolder("comic_translate"))
    }

    private fun bubbles() = listOf(
        OcrBubble(0, OcrRect(0.1f, 0.1f, 0.5f, 0.3f), listOf(OcrTextLine("行くぞ", OcrRect(0.2f, 0.15f, 0.4f, 0.2f), 0.9f)), 0.85f),
        OcrBubble(1, OcrRect(0.6f, 0.5f, 0.9f, 0.7f), listOf(OcrTextLine("待って", OcrRect(0.65f, 0.55f, 0.85f, 0.6f), 0.8f)), 0.75f),
    )

    @Test
    fun `OCR 缓存与译文分文件且译文按语言共存`() {
        store.saveOcr(bookId, 3, bubbles())
        store.saveTranslation(bookId, "ZH_HANS", 3, listOf("走吧", "等等"))
        store.saveTranslation(bookId, "EN", 3, listOf("Let's go", "Wait"))

        val dir = File(tempFolder.root, "comic_translate/$bookId")
        assertTrue(File(dir, "3.ocr.json").isFile)
        assertTrue(File(dir, "3.ZH_HANS.json").isFile)
        assertTrue(File(dir, "3.EN.json").isFile)
        assertEquals(listOf("走吧", "等等"), store.loadTranslation(bookId, "ZH_HANS", 3)!!.texts)
        assertEquals(listOf("Let's go", "Wait"), store.loadTranslation(bookId, "EN", 3)!!.texts)
        assertEquals(2, store.translatedPages(bookId, "ZH_HANS") + store.translatedPages(bookId, "EN"))
    }

    @Test
    fun `换语言重译不动 OCR 缓存`() {
        store.saveOcr(bookId, 0, bubbles())
        store.saveTranslation(bookId, "ZH_HANS", 0, listOf("走吧", "等等"))
        store.deleteTranslation(bookId, "ZH_HANS", 0)

        assertNull(store.loadTranslation(bookId, "ZH_HANS", 0))
        // OCR 缓存原样还在：重译不需要重跑识别
        val ocr = store.loadOcr(bookId, 0)
        assertEquals(2, ocr!!.size)
        assertEquals("行くぞ", ocr[0].text)
    }

    @Test
    fun `未缓存页返回 null 且损坏文件不抛异常`() {
        assertNull(store.loadOcr(bookId, 99))
        assertNull(store.loadTranslation(bookId, "ZH_HANS", 99))
        val dir = File(tempFolder.root, "comic_translate/$bookId").apply { mkdirs() }
        File(dir, "1.ocr.json").writeText("{broken")
        File(dir, "1.ZH_HANS.json").writeText("[1,2")
        assertNull(store.loadOcr(bookId, 1))
        assertNull(store.loadTranslation(bookId, "ZH_HANS", 1))
    }

    @Test
    fun `translatedPages 与 hasAny`() {
        assertFalse(store.hasAny(bookId, "ZH_HANS"))
        store.saveTranslation(bookId, "ZH_HANS", 0, listOf("a"))
        store.saveTranslation(bookId, "ZH_HANS", 2, listOf("b", "c"))
        assertTrue(store.hasAny(bookId, "ZH_HANS"))
        assertEquals(2, store.translatedPages(bookId, "ZH_HANS"))
        assertEquals(0, store.translatedPages(bookId, "EN"))
    }

    @Test
    fun `删书清掉该书全部产物`() {
        store.saveOcr(bookId, 0, bubbles())
        store.saveTranslation(bookId, "ZH_HANS", 0, listOf("走吧", "等等"))
        store.deleteBook(bookId)
        assertNull(store.loadOcr(bookId, 0))
        assertFalse(store.hasAny(bookId, "ZH_HANS"))
        assertFalse(File(tempFolder.root, "comic_translate/$bookId").exists())
    }

    @Test
    fun `气泡缓存往返保留置信度与行坐标`() {
        store.saveOcr(bookId, 7, bubbles())
        val loaded = store.loadOcr(bookId, 7)!!
        assertEquals(2, loaded.size)
        assertEquals(0.85f, loaded[0].confidence, 0.001f)
        assertEquals(0.2f, loaded[0].lines[0].box.left, 0.001f)
        assertEquals(1, loaded[1].index)
    }

    @Test
    fun `微调随页存取且重建 OCR 缓存时作废`() {
        store.saveOcr(bookId, 3, bubbles())
        val adjusted = OcrRect(0.15f, 0.12f, 0.55f, 0.32f)
        store.saveAdjustments(bookId, 3, BubbleAdjustments(rects = mapOf(0 to adjusted)))

        // 往返：覆盖矩形按气泡序号取回
        val loaded = store.loadAdjustments(bookId, 3)!!
        assertEquals(adjusted, loaded.rects[0])
        assertNull(store.loadAdjustments(bookId, 4))

        // 识别缓存重建后序号可能漂移：重写 OCR 缓存连带作废同页微调
        store.saveOcr(bookId, 3, bubbles())
        assertNull(store.loadAdjustments(bookId, 3))

        // 单独作废
        store.saveAdjustments(bookId, 3, BubbleAdjustments(rects = mapOf(1 to adjusted)))
        store.deleteAdjustments(bookId, 3)
        assertNull(store.loadAdjustments(bookId, 3))
    }
}

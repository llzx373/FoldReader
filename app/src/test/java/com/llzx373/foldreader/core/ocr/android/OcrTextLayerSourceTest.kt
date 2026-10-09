package com.llzx373.foldreader.core.ocr.android

import android.graphics.Bitmap
import com.llzx373.foldreader.core.ocr.ModelCatalog
import com.llzx373.foldreader.core.ocr.OcrPage
import com.llzx373.foldreader.core.ocr.OcrRect
import com.llzx373.foldreader.core.ocr.OcrTextLine
import com.llzx373.foldreader.core.ocr.PdfOcrStore
import com.llzx373.foldreader.core.paged.PagedImageSource
import com.llzx373.foldreader.core.paged.PagedPageImage
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * OCR 文本层装饰器（B4）：沙箱渲染的页位图识别完必须回收（成功/失败同口径）；
 * 内存缓存在并发 selectText/search 下线程安全、同页只识别一次。
 */
@RunWith(RobolectricTestRunner::class)
class OcrTextLayerSourceTest {

    /** 无原生文字层的假来源：每次 loadPage 新渲一张位图并记录，供断言回收。 */
    private class FakeDelegate(
        override val pageCount: Int,
    ) : PagedImageSource {
        val rendered = Collections.synchronizedList(mutableListOf<Bitmap>())

        override suspend fun loadPage(index: Int, targetWidth: Int, targetHeight: Int): PagedPageImage {
            val bmp = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            rendered += bmp
            return PagedPageImage.Still(bmp)
        }

        override suspend fun loadThumbnail(index: Int, width: Int, height: Int): Bitmap? = null
        override fun close() = Unit
    }

    @get:Rule
    val tmp = TemporaryFolder()

    private fun page() =
        OcrPage(listOf(OcrTextLine("风雪夜归人", OcrRect(0.1f, 0.1f, 0.8f, 0.2f), 0.9f)))

    private fun newSource(
        delegate: FakeDelegate,
        recognizeCount: AtomicInteger = AtomicInteger(),
    ) = OcrTextLayerSource(
        delegate = delegate,
        bookId = 7L,
        store = PdfOcrStore(tmp.newFolder()),
        recSpecProvider = { ModelCatalog.REC_CH },
        recognize = { _, _ ->
            recognizeCount.incrementAndGet()
            page()
        },
    )

    @Test
    fun `识别完成后页位图被回收`() = runBlocking {
        val delegate = FakeDelegate(pageCount = 2)
        val source = newSource(delegate)

        source.selectText(0, 0f, 0f, 1f, 1f)
        source.selectText(1, 0f, 0f, 1f, 1f)

        assertEquals(2, delegate.rendered.size)
        assertTrue("渲染位图必须全部回收", delegate.rendered.all { it.isRecycled })
    }

    @Test
    fun `识别失败页位图同样回收`() = runBlocking {
        val delegate = FakeDelegate(pageCount = 1)
        val source = OcrTextLayerSource(
            delegate = delegate,
            bookId = 7L,
            store = PdfOcrStore(tmp.newFolder()),
            recSpecProvider = { ModelCatalog.REC_CH },
            recognize = { _, _ -> throw IllegalStateException("识别炸了") },
        )

        assertNull(source.selectText(0, 0f, 0f, 1f, 1f))
        assertTrue(delegate.rendered.single().isRecycled)
    }

    @Test
    fun `并发 selectText 与 search 下同页只识别一次且位图全部回收`() = runBlocking {
        val delegate = FakeDelegate(pageCount = 30)
        val recognizeCount = AtomicInteger()
        val source = newSource(delegate, recognizeCount)

        val jobs = (0 until 30).flatMap { index ->
            listOf(
                async(Dispatchers.Default) { source.selectText(index, 0.05f, 0.05f, 0.9f, 0.25f) },
                async(Dispatchers.Default) { source.search("风雪", maxHits = 10, snippetPages = 30) },
            )
        }
        jobs.awaitAll()

        assertEquals("每页只应识别一次", 30, recognizeCount.get())
        assertTrue(delegate.rendered.all { it.isRecycled })
    }
}

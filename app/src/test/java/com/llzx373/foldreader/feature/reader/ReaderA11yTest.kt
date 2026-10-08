package com.llzx373.foldreader.feature.reader

import com.llzx373.foldreader.core.ocr.OcrRect
import com.llzx373.foldreader.core.reader.Page
import com.llzx373.foldreader.core.reader.PageLine
import com.llzx373.foldreader.core.translate.ComicPageTranslation
import com.llzx373.foldreader.core.translate.TranslatedBubble
import org.junit.Assert.assertEquals
import org.junit.Test

/** M35 TalkBack 语义文本拼装（contentDescription / liveRegion 播报内容）。 */
class ReaderA11yTest {

    private fun line(text: String, imagePath: String? = null, imageAlt: String? = null) = PageLine(
        charStart = 0,
        charEnd = text.length.toLong(),
        text = text,
        isParagraphStart = false,
        isParagraphEnd = false,
        imagePath = imagePath,
        imageAlt = imageAlt,
    )

    private fun page(vararg lines: PageLine) = Page(
        charStart = 0,
        charEnd = 100,
        lines = lines.toList(),
        paddingLeft = 0f,
        paddingRight = 0f,
    )

    @Test
    fun `文本行直拼成播报文本`() {
        assertEquals("第一行第二行", page(line("第一行"), line("第二行")).accessibilityText())
    }

    @Test
    fun `图片行替换为占位描述且带 alt`() {
        val p = page(line("前文"), line("\uFFFC", imagePath = "a.jpg", imageAlt = "地图"), line("后文"))
        assertEquals("前文［图片：地图］后文", p.accessibilityText())
    }

    @Test
    fun `图片行无 alt 也保留占位不留静默空洞`() {
        val p = page(line("前文"), line("\uFFFC", imagePath = "a.jpg"))
        assertEquals("前文［图片］", p.accessibilityText())
    }

    private fun bubble(text: String?) = TranslatedBubble(
        rect = OcrRect(0f, 0f, 1f, 1f),
        text = text,
        lowConfidence = false,
        erased = false,
    )

    @Test
    fun `页式页面语义为页码 1 基`() {
        assertEquals("第 3 页，共 20 页", pagedPageDescription(2, 20))
    }

    @Test
    fun `页式页面语义附气泡译文且跳过未译气泡`() {
        val translation = ComicPageTranslation(listOf(bubble("你好"), bubble(null), bubble("世界")))
        assertEquals("第 1 页，共 5 页。译文：你好；世界", pagedPageDescription(0, 5, translation))
    }

    @Test
    fun `页式页面语义忽略抹除条目`() {
        val erased = TranslatedBubble(
            rect = OcrRect(0f, 0f, 1f, 1f),
            text = null,
            lowConfidence = false,
            erased = true,
        )
        assertEquals("第 1 页，共 5 页", pagedPageDescription(0, 5, ComicPageTranslation(listOf(erased))))
    }

    @Test
    fun `双页跨页语义报区间`() {
        assertEquals("第 4 至 5 页，共 20 页", pagedSpreadDescription(listOf(3, 4), 20))
        assertEquals("第 20 页，共 20 页", pagedSpreadDescription(listOf(19), 20))
    }

    @Test
    fun `双页跨页语义合并两页译文`() {
        val translationFor: (Int) -> ComicPageTranslation = {
            ComicPageTranslation(listOf(bubble("第${it + 1}页译文")))
        }
        assertEquals(
            "第 1 至 2 页，共 9 页。译文：第1页译文；第2页译文",
            pagedSpreadDescription(listOf(0, 1), 9, translationFor),
        )
    }
}

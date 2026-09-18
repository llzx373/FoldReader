package com.llzx373.foldreader.feature.comic

import com.llzx373.foldreader.core.data.settings.ComicFitMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComicLogicTest {

    private fun pages(index: ComicSpreadIndex, page: Int): List<Int> = index.pagesOf(page)

    @Test
    fun `单页模式每页各自成跨页`() {
        val index = buildComicSpreadIndex(pageCount = 4, dual = false, coverAlone = true)

        assertEquals(4, index.spreadCount)
        assertEquals(listOf(0), pages(index, 0))
        assertEquals(listOf(3), pages(index, 3))
        assertEquals(1, index.next(0))
        assertNull(index.previous(0))
        assertNull(index.next(3))
    }

    @Test
    fun `双页基础配对`() {
        val index = buildComicSpreadIndex(pageCount = 5, dual = true, coverAlone = false)

        assertEquals(listOf(0, 1), pages(index, 0))
        assertEquals(listOf(0, 1), pages(index, 1))
        assertEquals(listOf(2, 3), pages(index, 2))
        // 末页落单
        assertEquals(listOf(4), pages(index, 4))
        assertEquals(3, index.spreadCount)
        assertTrue(index.isLastSpread(4))
    }

    @Test
    fun `封面单独成页时后续配对整体后移`() {
        val index = buildComicSpreadIndex(pageCount = 6, dual = true, coverAlone = true)

        assertEquals(listOf(0), pages(index, 0))
        assertEquals(listOf(1, 2), pages(index, 1))
        assertEquals(listOf(3, 4), pages(index, 3))
        assertEquals(listOf(5), pages(index, 5))

        assertEquals(1, index.next(0))
        assertEquals(3, index.next(1))
        assertEquals(0, index.previous(1))
        assertEquals(1, index.previous(3))
    }

    @Test
    fun `宽图独占整宽并让后续配对错位`() {
        // 第 2 页是横向跨页大图：0-1 一对，2 独占，3-4 一对，5 落单
        val wide = BooleanArray(6).also { it[2] = true }
        val index = buildComicSpreadIndex(pageCount = 6, dual = true, coverAlone = false, wide = wide)

        assertEquals(listOf(0, 1), pages(index, 0))
        assertEquals(listOf(2), pages(index, 2))
        assertEquals(listOf(3, 4), pages(index, 3))
        assertEquals(listOf(5), pages(index, 5))

        assertEquals(2, index.next(0))
        assertEquals(3, index.next(2))
        // 从宽图往后翻不会跳过第 3 页
        assertEquals(2, index.previous(3))
    }

    @Test
    fun `宽图与封面单独同时生效`() {
        val wide = BooleanArray(6).also { it[3] = true }
        val index = buildComicSpreadIndex(pageCount = 6, dual = true, coverAlone = true, wide = wide)

        assertEquals(listOf(0), pages(index, 0))
        assertEquals(listOf(1, 2), pages(index, 1))
        assertEquals(listOf(3), pages(index, 3))
        assertEquals(listOf(4, 5), pages(index, 4))
    }

    @Test
    fun `尺寸数组长度不符时忽略宽图独占`() {
        val index = buildComicSpreadIndex(
            pageCount = 4,
            dual = true,
            coverAlone = false,
            wide = BooleanArray(2).also { it[0] = true },
        )

        assertEquals(listOf(0, 1), pages(index, 0))
    }

    @Test
    fun `空书不崩`() {
        val index = buildComicSpreadIndex(pageCount = 0, dual = true, coverAlone = true)

        assertEquals(ComicSpreadIndex.EMPTY, index)
        assertEquals(0, index.spreadCount)
        assertEquals(emptyList<Int>(), index.pagesOf(1))
        assertEquals(0, index.startOf(5))
        assertNull(index.next(0))
    }

    @Test
    fun `跨页起点归一化`() {
        val index = buildComicSpreadIndex(pageCount = 6, dual = true, coverAlone = false)

        assertEquals(0, index.startOf(1))
        assertEquals(2, index.startOf(3))
        assertEquals(4, index.startOf(5))
        // (0,1) 是第 0 个跨页，(2,3) 是第 1 个
        assertEquals(1, index.spreadOf(3))
        assertEquals(2, index.spreadOf(5))
    }

    @Test
    fun `宽高比判定`() {
        assertTrue(isWidePage(2000, 1400))
        assertTrue(isWidePage(1160, 1000))
        assertFalse(isWidePage(1000, 1400))
        assertFalse(isWidePage(0, 0))
    }

    @Test
    fun `进度与页序号互算`() {
        assertEquals(0f, comicProgressOf(0, 200), 0f)
        assertEquals(0.5f, comicProgressOf(100, 200), 0.0001f)
        assertEquals(0f, comicProgressOf(0, 0), 0f)

        assertEquals(0, comicPageFromFraction(0f, 200))
        assertEquals(100, comicPageFromFraction(0.5f, 200))
        // 拖到最右不能越过末页
        assertEquals(199, comicPageFromFraction(1f, 200))
        assertEquals(0, comicPageFromFraction(0.5f, 0))
    }

    @Test
    fun `进度往返在整页位置稳定`() {
        val pageCount = 37
        for (page in 0 until pageCount) {
            val round = comicPageFromFraction(comicProgressOf(page, pageCount), pageCount)
            assertEquals("第 $page 页往返后应回到原页", page, round)
        }
    }

    @Test
    fun `页码文本单页与区间`() {
        assertEquals("第 1/5 页", comicPageNumberText(listOf(0), 5))
        assertEquals("第 1–2/5 页", comicPageNumberText(listOf(0, 1), 5))
        assertEquals("第 5/5 页", comicPageNumberText(listOf(4), 5))
        assertNull(comicPageNumberText(emptyList(), 5))
        assertNull(comicPageNumberText(listOf(9), 5))
    }

    @Test
    fun `适应整页等比缩到容器内`() {
        val (w, h) = comicBaseSize(1000, 2000, 500f, 2000f, ComicFitMode.FIT_PAGE)

        assertEquals(500f, w, 0.01f)
        assertEquals(1000f, h, 0.01f)
    }

    @Test
    fun `适应宽度铺满宽且高度溢出`() {
        val (w, h) = comicBaseSize(1000, 2000, 500f, 600f, ComicFitMode.FIT_WIDTH)

        assertEquals(500f, w, 0.01f)
        // 高宽同比放大后超出容器，正是"要能上下拖"的场景
        assertEquals(1000f, h, 0.01f)
    }

    @Test
    fun `适应高度铺满高`() {
        val (w, h) = comicBaseSize(1000, 2000, 500f, 600f, ComicFitMode.FIT_HEIGHT)

        assertEquals(600f, h, 0.01f)
        assertEquals(300f, w, 0.01f)
    }

    @Test
    fun `原图尺寸不做任何缩放`() {
        val (w, h) = comicBaseSize(1000, 2000, 500f, 600f, ComicFitMode.ORIGINAL)

        assertEquals(1000f, w, 0.01f)
        assertEquals(2000f, h, 0.01f)
    }

    @Test
    fun `尺寸异常时不给几何`() {
        val (w, h) = comicBaseSize(0, 2000, 500f, 600f, ComicFitMode.FIT_PAGE)

        assertEquals(0f, w, 0f)
        assertEquals(0f, h, 0f)
    }

    @Test
    fun `平移偏移不超出内容边缘`() {
        // 内容比容器宽 300：左右各能拖 150
        assertEquals(150f, clampComicOffset(999f, 800f, 500f), 0.01f)
        assertEquals(-150f, clampComicOffset(-999f, 800f, 500f), 0.01f)
        assertEquals(40f, clampComicOffset(40f, 800f, 500f), 0.01f)
    }

    @Test
    fun `内容小于容器时只能居中`() {
        assertEquals(0f, clampComicOffset(120f, 300f, 500f), 0.01f)
        assertEquals(0f, clampComicOffset(-120f, 300f, 500f), 0.01f)
    }

    // ---- 页内锚点：归一化命中、反查、缩放锚点 ----

    /** 一组落位参数，避免十几个浮点在每条断言里重复。 */
    private data class Geom(
        val cw: Float,
        val ch: Float,
        val bw: Float,
        val bh: Float,
        val scale: Float,
        val ox: Float,
        val oy: Float,
    ) {
        fun normalizedAt(x: Float, y: Float) = comicNormalizedAt(x, y, cw, ch, bw, bh, scale, ox, oy)

        fun screenOf(nx: Float, ny: Float) = comicScreenPosition(nx, ny, cw, ch, bw, bh, scale, ox, oy)

        fun rect() = comicDrawRect(cw, ch, bw, bh, scale, ox, oy)
    }

    /** 归一化落点要能原样还原回屏幕位置，否则「长按处」与「画出来的锚点」会差开。 */
    @Test
    fun `归一化命中与屏幕反查互为逆运算`() {
        val cases = listOf(
            Geom(1000f, 1500f, 800f, 1200f, 1f, 0f, 0f),
            Geom(1000f, 1500f, 1000f, 1600f, 2.5f, -120f, 200f),
            Geom(600f, 900f, 1920f, 1080f, 1.3f, 40f, -80f),
        )
        val nx = 0.37f
        val ny = 0.62f
        for (g in cases) {
            val (x, y) = g.screenOf(nx, ny)
            val back = g.normalizedAt(x, y)!!
            assertEquals("nx @ $g", nx, back.first, 0.0005f)
            assertEquals("ny @ $g", ny, back.second, 0.0005f)
        }
    }

    @Test
    fun `页外落点不给归一化坐标`() {
        val g = Geom(1000f, 1500f, 800f, 1200f, 1f, 0f, 0f)
        val rect = g.rect()

        assertNull(g.normalizedAt(rect.left - 1f, 10f))
        assertNull(g.normalizedAt(rect.left + 1f, rect.top - 1f))
        assertNull(g.normalizedAt(rect.left + rect.width + 1f, 10f))
        assertNull(g.normalizedAt(10f, rect.top + rect.height + 1f))
        // 边界上要给值
        assertEquals(1f, g.normalizedAt(rect.left + rect.width, rect.top + rect.height)!!.first, 0.0001f)
    }

    /**
     * 缩放锚点的唯一要求：缩放前后，锚点下面**还是同一块内容**。
     *
     * 双指缩放此前用的是另一个公式，锚点偏离页面中心时会整体漂移——这条测试就是钉住这一点的。
     */
    @Test
    fun `缩放后锚点下方的内容不动`() {
        val g = Geom(1000f, 1500f, 800f, 1200f, 1f, 0f, 0f)

        // 落位矩形：x∈[100,900]，y∈[150,1350]，锚点必须取在页内
        for (anchor in listOf(200f to 300f, 500f to 750f, 830f to 1300f)) {
            val (ax, ay) = anchor
            val before = g.normalizedAt(ax, ay)!!

            val (ox, oy) = comicZoomAnchored(ax, ay, g.cw, g.ch, g.bw, g.bh, 1f, 0f, 0f, 2f)
            val after = comicNormalizedAt(ax, ay, g.cw, g.ch, g.bw, g.bh, 2f, ox, oy)!!

            assertEquals("nx @ $anchor", before.first, after.first, 0.0005f)
            assertEquals("ny @ $anchor", before.second, after.second, 0.0005f)
        }
    }

    @Test
    fun `连续缩放时锚点同样保持不动`() {
        val g = Geom(1000f, 1500f, 800f, 1200f, 1f, 0f, 0f)
        // 先放大到 2 倍（此时已有平移），再从 2 倍缩放到 3.5 倍：锚点必须仍然不动
        val (ox1, oy1) = comicZoomAnchored(300f, 400f, g.cw, g.ch, g.bw, g.bh, 1f, 0f, 0f, 2f)
        val before = comicNormalizedAt(700f, 900f, g.cw, g.ch, g.bw, g.bh, 2f, ox1, oy1)!!

        val (ox2, oy2) = comicZoomAnchored(700f, 900f, g.cw, g.ch, g.bw, g.bh, 2f, ox1, oy1, 3.5f)
        val after = comicNormalizedAt(700f, 900f, g.cw, g.ch, g.bw, g.bh, 3.5f, ox2, oy2)!!

        assertEquals(before.first, after.first, 0.0005f)
        assertEquals(before.second, after.second, 0.0005f)
    }

    @Test
    fun `尺寸非法时缩放锚点退回原偏移`() {
        val (ox, oy) = comicZoomAnchored(100f, 100f, 500f, 500f, 0f, 0f, 1f, 30f, 40f, 2f)

        assertEquals(30f, ox, 0f)
        assertEquals(40f, oy, 0f)
    }

    @Test
    fun `落位随适配模式与缩放变化`() {
        // comicBaseSize 先算「适配后的基准尺寸」，comicDrawRect 只管居中与缩放，两步分开
        val (fitW, fitH) = comicBaseSize(800, 1200, 1000f, 1000f, ComicFitMode.FIT_PAGE)
        assertEquals(1000f, fitH, 0.01f)

        // FIT_PAGE：整页装进 1000x1000，水平居中
        val fitPage = comicDrawRect(1000f, 1000f, fitW, fitH, 1f, 0f, 0f)
        assertEquals(1000f, fitPage.height, 0.01f)
        assertEquals((1000f - fitPage.width) / 2f, fitPage.left, 0.01f)

        // FIT_WIDTH 同图：铺满宽度
        val (wideW, _) = comicBaseSize(800, 1200, 1000f, 1000f, ComicFitMode.FIT_WIDTH)
        val fitWidth = comicDrawRect(1000f, 1000f, wideW, fitH, 1f, 0f, 0f)
        assertEquals(1000f, fitWidth.width, 0.01f)
        assertEquals(0f, fitWidth.left, 0.01f)

        // 放大 2 倍：宽高都翻倍，仍以容器中心为基准
        val zoomed = comicDrawRect(1000f, 1000f, fitW, fitH, 2f, 0f, 0f)
        assertEquals(fitPage.width * 2f, zoomed.width, 0.01f)
        assertEquals(fitPage.height * 2f, zoomed.height, 0.01f)
    }
}

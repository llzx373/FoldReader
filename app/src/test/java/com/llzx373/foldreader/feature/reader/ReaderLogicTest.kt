package com.llzx373.foldreader.feature.reader

import androidx.compose.ui.geometry.Rect
import com.llzx373.foldreader.core.data.settings.DualPageMode
import com.llzx373.foldreader.core.data.settings.PageTurnMode
import com.llzx373.foldreader.core.data.settings.PdfReadingMode
import com.llzx373.foldreader.core.data.settings.TapAction
import com.llzx373.foldreader.core.foldable.FoldingPosture
import com.llzx373.foldreader.core.foldable.HingeOrientation
import com.llzx373.foldreader.core.foldable.Posture
import com.llzx373.foldreader.core.foldable.WidthCategory
import com.llzx373.foldreader.core.format.Chapter
import com.llzx373.foldreader.core.reader.PageAvoidance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderLogicTest {

    @Test
    fun `PDF 阅读模式：没定过时按文档是否有正文决定`() {
        // 文本型默认当电子书读（能重排、能搜索、能调字号）
        assertEquals(PdfReadingMode.TEXT, resolvePdfReadingMode(null, hasExtractedText = true))
        // 扫描件只能按页渲染
        assertEquals(PdfReadingMode.PAGED, resolvePdfReadingMode(null, hasExtractedText = false))
    }

    @Test
    fun `PDF 阅读模式：用户定过就听用户的`() {
        // 扫描件被手动切成文本模式也算用户的选择（首次打开会得到一句明确报错，不是白屏）
        assertEquals(PdfReadingMode.TEXT, resolvePdfReadingMode(PdfReadingMode.TEXT, hasExtractedText = false))
        // 文本型也能被手动切回页式——"能取字"不等于"必须取字"
        assertEquals(PdfReadingMode.PAGED, resolvePdfReadingMode(PdfReadingMode.PAGED, hasExtractedText = true))
    }

    private val chapters = listOf(
        Chapter("卷首", 0L, 100L),
        Chapter("第一章", 100L, 250L),
        Chapter("第二章", 250L, 400L),
    )

    @Test
    fun `tap zones follow hotspot ratio`() {
        assertEquals(TapZone.PREVIOUS, tapZoneOf(10f, 1000f, 0.3f))
        assertEquals(TapZone.PREVIOUS, tapZoneOf(299f, 1000f, 0.3f))
        assertEquals(TapZone.MIDDLE, tapZoneOf(500f, 1000f, 0.3f))
        assertEquals(TapZone.NEXT, tapZoneOf(701f, 1000f, 0.3f))
        assertEquals(TapZone.NEXT, tapZoneOf(990f, 1000f, 0.3f))
        assertEquals(TapZone.MIDDLE, tapZoneOf(10f, 0f, 0.3f))
    }

    @Test
    fun `tap zone bottom strip turns next`() {
        assertEquals(TapZone.NEXT, tapZoneOf(500f, 1000f, 0.3f, y = 950f, heightPx = 1000f))
        assertEquals(TapZone.NEXT, tapZoneOf(100f, 1000f, 0.3f, y = 900f, heightPx = 1000f))
        assertEquals(TapZone.NEXT, tapZoneOf(500f, 1000f, 0.3f, y = 701f, heightPx = 1000f))
        assertEquals(TapZone.MIDDLE, tapZoneOf(500f, 1000f, 0.3f, y = 700f, heightPx = 1000f))
        assertEquals(TapZone.MIDDLE, tapZoneOf(500f, 1000f, 0.3f, y = 100f, heightPx = 1000f))
        assertEquals(TapZone.PREVIOUS, tapZoneOf(100f, 1000f, 0.3f, y = 100f, heightPx = 1000f))
        assertEquals(TapZone.MIDDLE, tapZoneOf(500f, 1000f, 0.3f, y = 950f, heightPx = 0f))
    }

    /**
     * 中间点击层照 [middleZoneRect] 铺，所以这块矩形必须与 [tapZoneOf] 的判定完全对上——
     * 对不上就会「点的位置被判成右区（翻页），却被点击层当成中间区接走」。
     */
    @Test
    fun `middle zone rect matches tap zone judgement`() {
        val width = 1000f
        val height = 2000f
        val rect = middleZoneRect(width, height, 0.3f)

        assertEquals(300f, rect.left, 0.001f)
        assertEquals(400f, rect.width, 0.001f)
        // 高度只到「底边翻页条」之上：底边那一条判的是 NEXT，不能被点击层盖住
        assertEquals(1400f, rect.height, 0.001f)

        // 矩形内任意一点都必须判成中间区
        assertEquals(TapZone.MIDDLE, tapZoneOf(rect.left, width, 0.3f, y = 0f, heightPx = height))
        assertEquals(TapZone.MIDDLE, tapZoneOf(rect.left + 1f, width, 0.3f, y = 10f, heightPx = height))
        assertEquals(
            TapZone.MIDDLE,
            tapZoneOf(rect.left + rect.width - 1f, width, 0.3f, y = rect.height - 1f, heightPx = height),
        )
        // 矩形外一像素就必须不再是中间区
        assertEquals(TapZone.PREVIOUS, tapZoneOf(rect.left - 1f, width, 0.3f, y = 10f, heightPx = height))
        assertEquals(TapZone.NEXT, tapZoneOf(rect.left + rect.width + 1f, width, 0.3f, y = 10f, heightPx = height))
        assertEquals(TapZone.NEXT, tapZoneOf(rect.left + 1f, width, 0.3f, y = rect.height + 1f, heightPx = height))
        // 边界那一行本身判的是中间区（判定是 `y > 阈值`），而点击层的盒子上界是开的，
        // 所以这一像素行会落回下层按单击处理——一像素的差可忽略，这里如实钉住语义
        assertEquals(TapZone.MIDDLE, tapZoneOf(rect.left + 1f, width, 0.3f, y = rect.height, heightPx = height))
    }

    @Test
    fun `middle zone rect clamps hotspot ratio like tap zone`() {
        // 非法比例按同一套夹取，否则点击层与判定会各算各的
        assertEquals(middleZoneRect(1000f, 1000f, 5f).left, 1000f * 0.45f, 0.001f)
        assertEquals(middleZoneRect(1000f, 1000f, 0f).left, 1000f * 0.05f, 0.001f)
    }

    /**
     * 漫画阅读器没有底边翻页条（底边随左右热区分区），热区判定因此不带 y，
     * 中间区就是整高——点击层要照这个口径铺，否则底边中间那块会漏接双击。
     */
    @Test
    fun `无底边条时中间区是整高且与不带 y 的热区判定一致`() {
        val width = 1000f
        val height = 2000f
        val ratio = 0.3f
        val rect = middleZoneRect(width, height, ratio, bottomStripEnabled = false)

        assertEquals(300f, rect.left, 0.001f)
        assertEquals(400f, rect.width, 0.001f)
        assertEquals(2000f, rect.height, 0.001f)

        // 判定不带 y，所以整高每一行都按 x 分区
        assertEquals(TapZone.MIDDLE, tapZoneOf(rect.left, width, ratio))
        assertEquals(TapZone.MIDDLE, tapZoneOf(rect.left + rect.width - 1f, width, ratio))
        assertEquals(TapZone.PREVIOUS, tapZoneOf(rect.left - 1f, width, ratio))
        assertEquals(TapZone.NEXT, tapZoneOf(rect.left + rect.width + 1f, width, ratio))

        // 有底边条的老口径不受影响：仍然是不到屏幕底
        assertEquals(1400f, middleZoneRect(width, height, ratio).height, 0.001f)
    }

    /**
     * 底边翻页条恒为「下一页」，且与 x 无关。调用方靠 [isBottomPagingStrip] 在 RTL 下把它
     * 单独摘出来，否则会走左右热区的 rtl 映射，被翻成上一页。
     */
    @Test
    fun `bottom paging strip is detected regardless of x`() {
        val width = 1000f
        val height = 2000f
        val ratio = 0.3f
        val stripY = height * 0.7f + 1f

        assertTrue(isBottomPagingStrip(stripY, height, ratio))
        // 与 x 无关：左中右都算底边条
        assertEquals(TapZone.NEXT, tapZoneOf(10f, width, ratio, y = stripY, heightPx = height))
        assertEquals(TapZone.NEXT, tapZoneOf(500f, width, ratio, y = stripY, heightPx = height))
        assertEquals(TapZone.NEXT, tapZoneOf(990f, width, ratio, y = stripY, heightPx = height))

        // 条外不是；尺寸未知时也不认（与 tapZoneOf 的 y/heightPx 缺省语义一致）
        assertFalse(isBottomPagingStrip(height * 0.7f, height, ratio))
        assertFalse(isBottomPagingStrip(stripY, 0f, ratio))
    }

    @Test
    fun `double tap needs both time and position within limits`() {
        val timeout = 300L
        val slop = 40f

        assertTrue(isDoubleTap(100f, 100f, 110f, 105f, elapsedMs = 120L, timeoutMs = timeout, slopPx = slop))
        // 位置对但超时：算两次单击
        assertFalse(isDoubleTap(100f, 100f, 110f, 105f, elapsedMs = 400L, timeoutMs = timeout, slopPx = slop))
        // 时间对但位置差太远：算两次单击
        assertFalse(isDoubleTap(100f, 100f, 300f, 105f, elapsedMs = 120L, timeoutMs = timeout, slopPx = slop))
        assertFalse(isDoubleTap(100f, 100f, 110f, 300f, elapsedMs = 120L, timeoutMs = timeout, slopPx = slop))
        // 边界：恰好卡在超时与容差上算双击
        assertTrue(isDoubleTap(100f, 100f, 140f, 140f, elapsedMs = timeout, timeoutMs = timeout, slopPx = slop))
        assertFalse(isDoubleTap(100f, 100f, 141f, 100f, elapsedMs = 0L, timeoutMs = timeout, slopPx = slop))
    }

    @Test
    fun `middle tap falls back to single when double is not configured`() {
        assertEquals(
            TapAction.TOGGLE_MENU,
            resolveMiddleTap(TapAction.TOGGLE_MENU, TapAction.NONE, isDouble = true),
        )
        assertEquals(
            TapAction.TOGGLE_ZOOM,
            resolveMiddleTap(TapAction.TOGGLE_MENU, TapAction.TOGGLE_ZOOM, isDouble = true),
        )
        assertEquals(
            TapAction.TOGGLE_MENU,
            resolveMiddleTap(TapAction.TOGGLE_MENU, TapAction.TOGGLE_ZOOM, isDouble = false),
        )
    }

    /**
     * 不支持的动作必须整层不挂：中间点击层会让**单击**也等一个双击超时，
     * 若文本阅读器为它不支持的「缩放」挂了层，页面翻页就会平白慢 300ms。
     */
    @Test
    fun `zoom action is paged only and none never mounts a layer`() {
        assertFalse(supportsTapAction(TapAction.NONE, paged = true))
        assertFalse(supportsTapAction(TapAction.NONE, paged = false))
        assertTrue(supportsTapAction(TapAction.TOGGLE_ZOOM, paged = true))
        assertFalse(supportsTapAction(TapAction.TOGGLE_ZOOM, paged = false))
        assertTrue(supportsTapAction(TapAction.TOGGLE_MENU, paged = false))
        assertTrue(supportsTapAction(TapAction.TOGGLE_BOOKMARK, paged = false))
    }

    @Test
    fun `volume key dispatch splits scroll and page modes`() {
        assertEquals(VolumeKeyDispatch.PAGE_PREV, volumeKeyDispatch(volumeUp = true, scrollMode = false))
        assertEquals(VolumeKeyDispatch.PAGE_NEXT, volumeKeyDispatch(volumeUp = false, scrollMode = false))
        assertEquals(VolumeKeyDispatch.SCROLL_BACK, volumeKeyDispatch(volumeUp = true, scrollMode = true))
        assertEquals(VolumeKeyDispatch.SCROLL_FORTH, volumeKeyDispatch(volumeUp = false, scrollMode = true))
    }

    @Test
    fun `page number text single and spread`() {
        assertEquals("第 3/120 页", pageNumberText(3, hasRightPage = false, totalPages = 120))
        assertEquals("第 3–4/120 页", pageNumberText(3, hasRightPage = true, totalPages = 120))
        assertEquals("第 120/120 页", pageNumberText(120, hasRightPage = true, totalPages = 120))
        assertEquals("第 120/120 页", pageNumberText(150, hasRightPage = false, totalPages = 120))
        assertEquals(null, pageNumberText(0, hasRightPage = false, totalPages = 0))
        assertEquals(null, pageNumberText(5, hasRightPage = false, totalPages = 0))
        assertEquals(null, pageNumberText(0, hasRightPage = false, totalPages = 120))
    }

    @Test
    fun `chapter progress text degrades without catalog`() {
        assertEquals("第 2/3 章 · 40.0%", chapterProgressText(1, 3, 0.4f))
        assertEquals("第 1/3 章", chapterProgressText(0, 3, -1f))
        assertEquals(null, chapterProgressText(0, 0, 0.5f))
        assertEquals(null, chapterProgressText(0, 1, 0.5f))
    }

    @Test
    fun `in chapter fraction from chapter bounds`() {
        assertEquals(0.4f, inChapterFraction(chapters, 1, 160L), 0.001f)
        assertEquals(0f, inChapterFraction(chapters, 1, 100L), 0.001f)
        assertEquals(1f, inChapterFraction(chapters, 2, 9999L), 0.001f)
        assertEquals(-1f, inChapterFraction(chapters, 9, 100L), 0.001f)
        assertEquals(-1f, inChapterFraction(emptyList(), 0, 0L), 0.001f)
    }

    @Test
    fun `chapter index tracks offset`() {
        assertEquals(0, chapterIndexAt(chapters, 0L))
        assertEquals(0, chapterIndexAt(chapters, 99L))
        assertEquals(1, chapterIndexAt(chapters, 100L))
        assertEquals(2, chapterIndexAt(chapters, 399L))
        assertEquals(2, chapterIndexAt(chapters, 10_000L))
    }

    @Test
    fun `chapter index boundary cases`() {
        assertEquals(0, chapterIndexAt(emptyList(), 123L))
        assertEquals(0, chapterIndexAt(chapters, -5L))
    }

    @Test
    fun `progress percent clamps to range`() {
        assertEquals(0f, progressPercentOf(0L, 1000L))
        assertEquals(0.5f, progressPercentOf(500L, 1000L))
        assertEquals(1f, progressPercentOf(2000L, 1000L))
        assertEquals(0f, progressPercentOf(10L, 0L))
    }

    @Test
    fun `percent formatting`() {
        assertEquals("52.4%", formatPercent(0.524f))
        assertEquals("100.0%", formatPercent(1f))
    }

    @Test
    fun `margin levels map to dp pairs`() {
        assertEquals(8f to 12f, marginDpFor(0))
        assertEquals(16f to 24f, marginDpFor(1))
        assertEquals(24f to 36f, marginDpFor(2))
        assertEquals(8f to 12f, marginDpFor(-1))
        assertEquals(24f to 36f, marginDpFor(99))
    }

    @Test
    fun `font key display names`() {
        assertEquals("默认", com.llzx373.foldreader.core.reader.FontManager.displayNameOf("default"))
        assertEquals("衬线", com.llzx373.foldreader.core.reader.FontManager.displayNameOf("serif"))
        assertEquals("等宽", com.llzx373.foldreader.core.reader.FontManager.displayNameOf("monospace"))
        assertEquals("MyFont", com.llzx373.foldreader.core.reader.FontManager.displayNameOf("file:MyFont.otf"))
    }

    @Test
    fun `page turn mode cycling covers all three modes`() {
        assertEquals(PageTurnMode.NONE, nextPageTurnMode(PageTurnMode.COVER))
        assertEquals(PageTurnMode.SCROLL, nextPageTurnMode(PageTurnMode.NONE))
        assertEquals(PageTurnMode.COVER, nextPageTurnMode(PageTurnMode.SCROLL))
    }

    private val flatVerticalHinge = FoldingPosture(
        posture = Posture.FLAT,
        hingeBounds = Rect(500f, 0f, 520f, 1800f),
        hingeOrientation = HingeOrientation.VERTICAL,
    )

    private val flatHorizontalHinge = FoldingPosture(
        posture = Posture.FLAT,
        hingeBounds = Rect(0f, 500f, 1000f, 500f),
        hingeOrientation = HingeOrientation.HORIZONTAL,
    )

    @Test
    fun `dual page mode resolution`() {
        val flatVertical = flatVerticalHinge
        val flatHorizontal = flatVerticalHinge.copy(hingeOrientation = HingeOrientation.HORIZONTAL)
        val halfOpened = flatVertical.copy(posture = Posture.HALF_OPENED)
        val closed = FoldingPosture.Closed

        assertEquals(PageLayoutMode.DUAL, resolvePageLayoutMode(
            flatVertical, WidthCategory.COMPACT, windowPortrait = false, pref = DualPageMode.AUTO))
        assertEquals(PageLayoutMode.DUAL, resolvePageLayoutMode(
            flatHorizontal, WidthCategory.COMPACT, windowPortrait = false, pref = DualPageMode.AUTO))
        assertEquals(PageLayoutMode.SINGLE, resolvePageLayoutMode(
            halfOpened, WidthCategory.COMPACT, windowPortrait = false, pref = DualPageMode.AUTO))
        assertEquals(PageLayoutMode.SINGLE, resolvePageLayoutMode(
            halfOpened, WidthCategory.EXPANDED, windowPortrait = false, pref = DualPageMode.AUTO))
        assertEquals(PageLayoutMode.SINGLE, resolvePageLayoutMode(
            closed, WidthCategory.COMPACT, windowPortrait = false, pref = DualPageMode.AUTO))
        assertEquals(PageLayoutMode.SINGLE, resolvePageLayoutMode(
            closed, WidthCategory.EXPANDED, windowPortrait = false, pref = DualPageMode.AUTO))
        assertEquals(PageLayoutMode.DUAL, resolvePageLayoutMode(
            closed, WidthCategory.EXPANDED, windowPortrait = false, pref = DualPageMode.AUTO,
            wideScreenDualPage = true))
        assertEquals(PageLayoutMode.SINGLE, resolvePageLayoutMode(
            closed, WidthCategory.MEDIUM, windowPortrait = false, pref = DualPageMode.AUTO,
            wideScreenDualPage = true))

        // FLAT 时部分设备上报零面积铰链 bounds（折痕不遮挡），只要 FoldingFeature 存在即双页
        val zeroWidthHinge = flatVertical.copy(hingeBounds = Rect(500f, 0f, 500f, 1800f))
        assertEquals(PageLayoutMode.DUAL, resolvePageLayoutMode(
            zeroWidthHinge, WidthCategory.COMPACT, windowPortrait = false, pref = DualPageMode.AUTO))
        val zeroHeightHinge = flatHorizontal.copy(hingeBounds = Rect(0f, 500f, 1000f, 500f))
        assertEquals(PageLayoutMode.DUAL, resolvePageLayoutMode(
            zeroHeightHinge, WidthCategory.COMPACT, windowPortrait = false, pref = DualPageMode.AUTO))

        assertEquals(PageLayoutMode.DUAL, resolvePageLayoutMode(
            closed, WidthCategory.COMPACT, windowPortrait = false, pref = DualPageMode.FORCE_DUAL))
        assertEquals(PageLayoutMode.SINGLE, resolvePageLayoutMode(
            halfOpened, WidthCategory.EXPANDED, windowPortrait = false, pref = DualPageMode.FORCE_DUAL))
        assertEquals(PageLayoutMode.SINGLE, resolvePageLayoutMode(
            flatVertical, WidthCategory.EXPANDED, windowPortrait = false, pref = DualPageMode.FORCE_SINGLE))
    }

    /**
     * 阔折叠展开后竖着拿：它上报水平铰链，且竖持宽度常常仍在 EXPANDED 断点之上，
     * 于是"FLAT + 铰链"与"EXPANDED + 宽屏双页"两条路都会判成双页——每页只剩半幅宽，
     * 窄到无法成行。方向必须是双页的前置条件。
     */
    @Test
    fun `竖持退回单页`() {
        // 展开 + 铰链：竖持单页，横持照旧双页
        assertEquals(PageLayoutMode.SINGLE, resolvePageLayoutMode(
            flatHorizontalHinge, WidthCategory.EXPANDED, windowPortrait = true,
            pref = DualPageMode.AUTO))
        assertEquals(PageLayoutMode.DUAL, resolvePageLayoutMode(
            flatHorizontalHinge, WidthCategory.EXPANDED, windowPortrait = false,
            pref = DualPageMode.AUTO))
        assertEquals(PageLayoutMode.SINGLE, resolvePageLayoutMode(
            flatVerticalHinge, WidthCategory.MEDIUM, windowPortrait = true, pref = DualPageMode.AUTO))

        // 无铰链宽屏 + "宽屏双页"开关：竖持同样退回单页
        assertEquals(PageLayoutMode.SINGLE, resolvePageLayoutMode(
            FoldingPosture.Closed, WidthCategory.EXPANDED, windowPortrait = true,
            pref = DualPageMode.AUTO, wideScreenDualPage = true))

        // "强制双页"是用户的显式选择，不受方向限制
        assertEquals(PageLayoutMode.DUAL, resolvePageLayoutMode(
            FoldingPosture.Closed, WidthCategory.COMPACT, windowPortrait = true,
            pref = DualPageMode.FORCE_DUAL))
        assertEquals(PageLayoutMode.DUAL, resolvePageLayoutMode(
            flatHorizontalHinge, WidthCategory.EXPANDED, windowPortrait = true,
            pref = DualPageMode.FORCE_DUAL))
    }

    @Test
    fun `dual split centers for horizontal hinge and hingeless wide screens`() {
        val flatHorizontal = FoldingPosture(
            posture = Posture.FLAT,
            hingeBounds = Rect(0f, 800f, 2000f, 830f),
            hingeOrientation = HingeOrientation.HORIZONTAL,
        )
        assertEquals(1000f to 1000f,
            dualSplit(flatHorizontal, Rect(0f, 800f, 2000f, 830f), 2000f))
        assertEquals(1000f to 1000f, dualSplit(FoldingPosture.Closed, null, 2000f))
        assertEquals(0f to 0f, dualSplit(FoldingPosture.Closed, null, 0f))
    }

    @Test
    fun `dual split follows vertical hinge bounds`() {
        val flatVertical = FoldingPosture(
            posture = Posture.FLAT,
            hingeBounds = Rect(500f, 0f, 540f, 1800f),
            hingeOrientation = HingeOrientation.VERTICAL,
        )
        assertEquals(500f to 540f,
            dualSplit(flatVertical, Rect(500f, 0f, 540f, 1800f), 2000f))
        assertEquals(0f to 0f,
            dualSplit(flatVertical, Rect(-20f, 0f, 0f, 1800f), 2000f))
    }

    @Test
    fun `dual page width takes the smaller safe area`() {
        assertEquals(400, dualPageWidthPx(700, 400))
        assertEquals(400, dualPageWidthPx(400, 700))
        assertEquals(500, dualPageWidthPx(500, 500))
    }

    @Test
    fun `scroll dual column flag`() {
        assertTrue(isDualColumnScroll(PageLayoutMode.DUAL, scrollMode = true, tabletopActive = false))
        assertFalse(isDualColumnScroll(PageLayoutMode.DUAL, scrollMode = false, tabletopActive = false))
        assertFalse(isDualColumnScroll(PageLayoutMode.SINGLE, scrollMode = true, tabletopActive = false))
        assertFalse(isDualColumnScroll(PageLayoutMode.DUAL, scrollMode = true, tabletopActive = true))
    }

    @Test
    fun `max line chars converge to page width comfort`() {
        assertEquals(40, capMaxLineChars(40, 2000f, 0f, 20f))
        assertEquals(25, capMaxLineChars(40, 500f, 0f, 20f))
        assertEquals(9, capMaxLineChars(40, 240f, 60f, 20f))
        assertEquals(18, capMaxLineChars(18, 2000f, 0f, 20f))
        assertEquals(1, capMaxLineChars(40, 10f, 0f, 20f))
    }

    private val leftPage = ContentRect(0f, 0f, 500f, 800f)
    private val rightPage = ContentRect(500f, 0f, 500f, 800f)

    @Test
    fun `cutout on right page top reserves odd top lines`() {
        // 开孔底缘距页顶 45px、行高 20px → 预留 ceil(45/20)=3 行
        val cutout = ContentRect(700f, 0f, 60f, 45f)
        val a = cameraAvoidanceLines(listOf(cutout), leftPage, rightPage, lineHeightPx = 20f)
        assertEquals(3, a.oddTopLines)
        assertEquals(0, a.evenBottomLines)
        assertTrue(a.active)
    }

    @Test
    fun `cutout on left page bottom reserves even bottom lines`() {
        // 开孔顶缘距页底 40px、行高 20px → 预留 2 行
        val cutout = ContentRect(50f, 760f, 60f, 40f)
        val a = cameraAvoidanceLines(listOf(cutout), leftPage, rightPage, lineHeightPx = 20f)
        assertEquals(0, a.oddTopLines)
        assertEquals(2, a.evenBottomLines)
    }

    @Test
    fun `cutout outside pages or wrong half is ignored`() {
        val farAway = ContentRect(2000f, 2000f, 60f, 60f)
        val rightBottom = ContentRect(700f, 700f, 60f, 60f) // 右页下半不算顶部遮挡
        val leftTop = ContentRect(50f, 10f, 60f, 60f) // 左页上半不算底部遮挡
        for (cutout in listOf(farAway, rightBottom, leftTop)) {
            val a = cameraAvoidanceLines(listOf(cutout), leftPage, rightPage, lineHeightPx = 20f)
            assertFalse(a.active)
            assertEquals(PageAvoidance(), a)
        }
    }

    @Test
    fun `avoidance lines clamp to 8 and empty on zero line height`() {
        val huge = ContentRect(700f, 0f, 60f, 400f)
        val a = cameraAvoidanceLines(listOf(huge), leftPage, rightPage, lineHeightPx = 20f)
        assertEquals(8, a.oddTopLines)
        val zero = cameraAvoidanceLines(listOf(huge), leftPage, rightPage, lineHeightPx = 0f)
        assertEquals(PageAvoidance(), zero)
    }

    /**
     * chapterIndexAt 改二分后必须与原线性实现完全等价：
     * 它在滚动路径上是主线程热点（每页 4 次），在搜索分组里是每个命中 1 次。
     */
    @Test
    fun `chapterIndexAt 与线性实现逐点等价`() {
        fun linear(list: List<Chapter>, offset: Long): Int =
            list.indexOfLast { offset >= it.charStart }.coerceAtLeast(0)

        val cases = listOf(
            emptyList(),
            chapters,
            listOf(Chapter("唯一", 0L, 10L)),
            listOf(Chapter("首章不从 0 开始", 500L, 900L)),
        )
        for (list in cases) {
            val probe = listOf(-1000L, -1L, 0L, 1L, 99L, 100L, 101L, 249L, 250L, 399L, 400L, 10_000L)
            for (offset in probe) {
                assertEquals(
                    "list=${list.map { it.charStart }} offset=$offset",
                    linear(list, offset),
                    chapterIndexAt(list, offset),
                )
            }
        }
    }

    /**
     * 章节兜底补扫的判定：过去只要在实时索引就一律跳过，导致
     * 「章节没写进库」这种状态在首次打开时无法自愈（用户看到的就是没有目录）。
     */
    @Test
    fun `章节兜底补扫：TXT 实时索引期间跳过 便宜的重扫照常`() {
        // 没有实时索引：什么格式都扫
        assertTrue(shouldScanChaptersInBackground(liveIndexing = false, cheapChapterScan = false))
        assertTrue(shouldScanChaptersInBackground(liveIndexing = false, cheapChapterScan = true))

        // 实时索引期间：TXT 贵，跳过；EPUB/FB2 只读 sidecar，照常兜底
        assertFalse(shouldScanChaptersInBackground(liveIndexing = true, cheapChapterScan = false))
        assertTrue(shouldScanChaptersInBackground(liveIndexing = true, cheapChapterScan = true))
    }

    @Test
    fun `chapterIndexAt 随机用例与线性实现等价`() {
        fun linear(list: List<Chapter>, offset: Long): Int =
            list.indexOfLast { offset >= it.charStart }.coerceAtLeast(0)

        val random = java.util.Random(20240917)
        repeat(50) {
            var start = random.nextInt(500).toLong()
            val list = ArrayList<Chapter>()
            repeat(random.nextInt(40)) {
                val end = start + random.nextInt(400) + 1
                list += Chapter("c${list.size}", start, end)
                start = end + random.nextInt(50)
            }
            val probeCount = 200
            repeat(probeCount) {
                val offset = random.nextInt((start + 100).toInt().coerceAtLeast(1)).toLong()
                assertEquals(linear(list, offset), chapterIndexAt(list, offset))
            }
        }
    }
}

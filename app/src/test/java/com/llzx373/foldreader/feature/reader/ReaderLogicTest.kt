package com.llzx373.foldreader.feature.reader

import androidx.compose.ui.geometry.Rect
import com.llzx373.foldreader.core.data.settings.DualPageMode
import com.llzx373.foldreader.core.data.settings.PageTurnMode
import com.llzx373.foldreader.core.foldable.FoldingPosture
import com.llzx373.foldreader.core.foldable.HingeOrientation
import com.llzx373.foldreader.core.foldable.Posture
import com.llzx373.foldreader.core.foldable.WidthCategory
import com.llzx373.foldreader.core.format.Chapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderLogicTest {

    private val chapters = listOf(
        Chapter("卷首", 0L, 100L),
        Chapter("第一章", 100L, 250L),
        Chapter("第二章", 250L, 400L),
    )

    @Test
    fun `tap zones follow hotspot ratio`() {
        assertEquals(TapZone.PREVIOUS, tapZoneOf(10f, 1000f, 0.3f))
        assertEquals(TapZone.PREVIOUS, tapZoneOf(299f, 1000f, 0.3f))
        assertEquals(TapZone.MENU, tapZoneOf(500f, 1000f, 0.3f))
        assertEquals(TapZone.NEXT, tapZoneOf(701f, 1000f, 0.3f))
        assertEquals(TapZone.NEXT, tapZoneOf(990f, 1000f, 0.3f))
        assertEquals(TapZone.MENU, tapZoneOf(10f, 0f, 0.3f))
    }

    @Test
    fun `tap zone bottom strip turns next`() {
        assertEquals(TapZone.NEXT, tapZoneOf(500f, 1000f, 0.3f, y = 950f, heightPx = 1000f))
        assertEquals(TapZone.NEXT, tapZoneOf(100f, 1000f, 0.3f, y = 900f, heightPx = 1000f))
        assertEquals(TapZone.NEXT, tapZoneOf(500f, 1000f, 0.3f, y = 701f, heightPx = 1000f))
        assertEquals(TapZone.MENU, tapZoneOf(500f, 1000f, 0.3f, y = 700f, heightPx = 1000f))
        assertEquals(TapZone.MENU, tapZoneOf(500f, 1000f, 0.3f, y = 100f, heightPx = 1000f))
        assertEquals(TapZone.PREVIOUS, tapZoneOf(100f, 1000f, 0.3f, y = 100f, heightPx = 1000f))
        assertEquals(TapZone.MENU, tapZoneOf(500f, 1000f, 0.3f, y = 950f, heightPx = 0f))
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
    fun `page turn mode cycling covers all four modes`() {
        assertEquals(com.llzx373.foldreader.core.data.settings.PageTurnMode.NONE,
            nextPageTurnMode(com.llzx373.foldreader.core.data.settings.PageTurnMode.COVER))
        assertEquals(com.llzx373.foldreader.core.data.settings.PageTurnMode.SCROLL,
            nextPageTurnMode(com.llzx373.foldreader.core.data.settings.PageTurnMode.NONE))
        assertEquals(com.llzx373.foldreader.core.data.settings.PageTurnMode.SIMULATION,
            nextPageTurnMode(com.llzx373.foldreader.core.data.settings.PageTurnMode.SCROLL))
        assertEquals(com.llzx373.foldreader.core.data.settings.PageTurnMode.COVER,
            nextPageTurnMode(com.llzx373.foldreader.core.data.settings.PageTurnMode.SIMULATION))
    }

    @Test
    fun `dual page mode resolution`() {
        val flatVertical = FoldingPosture(
            posture = Posture.FLAT,
            hingeBounds = Rect(500f, 0f, 520f, 1800f),
            hingeOrientation = HingeOrientation.VERTICAL,
        )
        val flatHorizontal = flatVertical.copy(hingeOrientation = HingeOrientation.HORIZONTAL)
        val halfOpened = flatVertical.copy(posture = Posture.HALF_OPENED)
        val closed = FoldingPosture.Closed

        assertEquals(PageLayoutMode.DUAL,
            resolvePageLayoutMode(flatVertical, WidthCategory.COMPACT, DualPageMode.AUTO))
        assertEquals(PageLayoutMode.DUAL,
            resolvePageLayoutMode(flatHorizontal, WidthCategory.COMPACT, DualPageMode.AUTO))
        assertEquals(PageLayoutMode.SINGLE,
            resolvePageLayoutMode(halfOpened, WidthCategory.COMPACT, DualPageMode.AUTO))
        assertEquals(PageLayoutMode.SINGLE,
            resolvePageLayoutMode(halfOpened, WidthCategory.EXPANDED, DualPageMode.AUTO))
        assertEquals(PageLayoutMode.SINGLE,
            resolvePageLayoutMode(closed, WidthCategory.COMPACT, DualPageMode.AUTO))
        assertEquals(PageLayoutMode.SINGLE,
            resolvePageLayoutMode(closed, WidthCategory.EXPANDED, DualPageMode.AUTO))
        assertEquals(PageLayoutMode.DUAL,
            resolvePageLayoutMode(closed, WidthCategory.EXPANDED, DualPageMode.AUTO,
                wideScreenDualPage = true))
        assertEquals(PageLayoutMode.SINGLE,
            resolvePageLayoutMode(closed, WidthCategory.MEDIUM, DualPageMode.AUTO,
                wideScreenDualPage = true))

        // FLAT 时部分设备上报零面积铰链 bounds（折痕不遮挡），只要 FoldingFeature 存在即双页
        val zeroWidthHinge = flatVertical.copy(hingeBounds = Rect(500f, 0f, 500f, 1800f))
        assertEquals(PageLayoutMode.DUAL,
            resolvePageLayoutMode(zeroWidthHinge, WidthCategory.COMPACT, DualPageMode.AUTO))
        val zeroHeightHinge = flatHorizontal.copy(hingeBounds = Rect(0f, 500f, 1000f, 500f))
        assertEquals(PageLayoutMode.DUAL,
            resolvePageLayoutMode(zeroHeightHinge, WidthCategory.COMPACT, DualPageMode.AUTO))

        assertEquals(PageLayoutMode.DUAL,
            resolvePageLayoutMode(closed, WidthCategory.COMPACT, DualPageMode.FORCE_DUAL))
        assertEquals(PageLayoutMode.SINGLE,
            resolvePageLayoutMode(halfOpened, WidthCategory.EXPANDED, DualPageMode.FORCE_DUAL))
        assertEquals(PageLayoutMode.SINGLE,
            resolvePageLayoutMode(flatVertical, WidthCategory.EXPANDED, DualPageMode.FORCE_SINGLE))
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
    fun `effective page turn mode matrix`() {
        // 未显式设置：双页姿态默认仿真，单页默认覆盖（忽略存储值）
        assertEquals(PageTurnMode.SIMULATION,
            effectivePageTurnMode(PageTurnMode.COVER, explicit = false, dualPage = true))
        assertEquals(PageTurnMode.COVER,
            effectivePageTurnMode(PageTurnMode.COVER, explicit = false, dualPage = false))
        assertEquals(PageTurnMode.SIMULATION,
            effectivePageTurnMode(PageTurnMode.SCROLL, explicit = false, dualPage = true))
        // 显式设置后：一切姿态用用户值
        assertEquals(PageTurnMode.SCROLL,
            effectivePageTurnMode(PageTurnMode.SCROLL, explicit = true, dualPage = true))
        assertEquals(PageTurnMode.SIMULATION,
            effectivePageTurnMode(PageTurnMode.SIMULATION, explicit = true, dualPage = false))
        assertEquals(PageTurnMode.NONE,
            effectivePageTurnMode(PageTurnMode.NONE, explicit = true, dualPage = true))
    }

    @Test
    fun `max line chars converge to page width comfort`() {
        assertEquals(40, capMaxLineChars(40, 2000f, 0f, 20f))
        assertEquals(25, capMaxLineChars(40, 500f, 0f, 20f))
        assertEquals(9, capMaxLineChars(40, 240f, 60f, 20f))
        assertEquals(18, capMaxLineChars(18, 2000f, 0f, 20f))
        assertEquals(1, capMaxLineChars(40, 10f, 0f, 20f))
    }
}

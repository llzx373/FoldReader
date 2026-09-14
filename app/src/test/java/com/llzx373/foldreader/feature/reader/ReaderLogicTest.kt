package com.llzx373.foldreader.feature.reader

import androidx.compose.ui.geometry.Rect
import com.llzx373.foldreader.core.data.settings.DualPageMode
import com.llzx373.foldreader.core.foldable.FoldingPosture
import com.llzx373.foldreader.core.foldable.HingeOrientation
import com.llzx373.foldreader.core.foldable.Posture
import com.llzx373.foldreader.core.foldable.WidthCategory
import com.llzx373.foldreader.core.format.Chapter
import org.junit.Assert.assertEquals
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
        assertEquals(PageLayoutMode.SINGLE,
            resolvePageLayoutMode(flatHorizontal, WidthCategory.COMPACT, DualPageMode.AUTO))
        assertEquals(PageLayoutMode.SINGLE,
            resolvePageLayoutMode(halfOpened, WidthCategory.COMPACT, DualPageMode.AUTO))
        assertEquals(PageLayoutMode.SINGLE,
            resolvePageLayoutMode(closed, WidthCategory.COMPACT, DualPageMode.AUTO))
        assertEquals(PageLayoutMode.DUAL,
            resolvePageLayoutMode(closed, WidthCategory.EXPANDED, DualPageMode.AUTO))
        assertEquals(PageLayoutMode.SINGLE,
            resolvePageLayoutMode(closed, WidthCategory.MEDIUM, DualPageMode.AUTO))

        val zeroWidthHinge = flatVertical.copy(hingeBounds = Rect(500f, 0f, 500f, 1800f))
        assertEquals(PageLayoutMode.SINGLE,
            resolvePageLayoutMode(zeroWidthHinge, WidthCategory.COMPACT, DualPageMode.AUTO))

        assertEquals(PageLayoutMode.DUAL,
            resolvePageLayoutMode(closed, WidthCategory.COMPACT, DualPageMode.FORCE_DUAL))
        assertEquals(PageLayoutMode.SINGLE,
            resolvePageLayoutMode(flatVertical, WidthCategory.EXPANDED, DualPageMode.FORCE_SINGLE))
    }
}

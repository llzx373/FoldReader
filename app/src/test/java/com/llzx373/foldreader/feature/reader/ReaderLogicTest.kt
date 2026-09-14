package com.llzx373.foldreader.feature.reader

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
    fun `page turn mode cycling skips simulation`() {
        assertEquals(com.llzx373.foldreader.core.data.settings.PageTurnMode.NONE,
            nextPageTurnMode(com.llzx373.foldreader.core.data.settings.PageTurnMode.COVER))
        assertEquals(com.llzx373.foldreader.core.data.settings.PageTurnMode.SCROLL,
            nextPageTurnMode(com.llzx373.foldreader.core.data.settings.PageTurnMode.NONE))
        assertEquals(com.llzx373.foldreader.core.data.settings.PageTurnMode.COVER,
            nextPageTurnMode(com.llzx373.foldreader.core.data.settings.PageTurnMode.SCROLL))
        assertEquals(com.llzx373.foldreader.core.data.settings.PageTurnMode.COVER,
            nextPageTurnMode(com.llzx373.foldreader.core.data.settings.PageTurnMode.SIMULATION))
    }
}

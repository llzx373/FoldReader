package com.llzx373.foldreader.feature.reader

import androidx.compose.ui.geometry.Rect
import com.llzx373.foldreader.core.foldable.FoldingPosture
import com.llzx373.foldreader.core.foldable.HingeOrientation
import com.llzx373.foldreader.core.foldable.Posture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TabletopLayoutTest {

    private val hinge = Rect(0f, 900f, 1200f, 936f)

    private fun posture(p: Posture, o: HingeOrientation?) =
        FoldingPosture(p, hinge, o)

    @Test
    fun `horizontal half-opened splits into content and panel`() {
        val layout = resolveTabletopLayout(
            posture(Posture.HALF_OPENED, HingeOrientation.HORIZONTAL),
            hinge, 1200f, 2000f,
        )!!
        assertEquals(ContentRect(0f, 0f, 1200f, 900f), layout.content)
        assertEquals(ContentRect(0f, 936f, 1200f, 1064f), layout.panel)
    }

    @Test
    fun `flat or closed never tabletop`() {
        assertNull(resolveTabletopLayout(
            posture(Posture.FLAT, HingeOrientation.HORIZONTAL), hinge, 1200f, 2000f))
        assertNull(resolveTabletopLayout(FoldingPosture.Closed, null, 1200f, 2000f))
    }

    @Test
    fun `vertical half-opened is not tabletop`() {
        assertNull(resolveTabletopLayout(
            posture(Posture.HALF_OPENED, HingeOrientation.VERTICAL), hinge, 1200f, 2000f))
    }

    @Test
    fun `degenerate hinge at screen edge yields null`() {
        val topHinge = Rect(0f, 0f, 1200f, 20f)
        assertNull(resolveTabletopLayout(
            posture(Posture.HALF_OPENED, HingeOrientation.HORIZONTAL), topHinge, 1200f, 2000f))
    }

    @Test
    fun `vertical half-opened content rect still avoids hinge`() {
        val rect = contentRectFor(
            posture(Posture.HALF_OPENED, HingeOrientation.VERTICAL),
            Rect(500f, 0f, 540f, 2000f), 1200f, 2000f,
        )
        assertEquals(ContentRect(540f, 0f, 660f, 2000f), rect)
    }
}

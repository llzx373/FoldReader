package com.llzx373.foldreader.feature.reader.peel

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

class PeelGeometryTest {

    private val w = 1080f
    private val h = 2340f
    private val br = PeelCorner.BOTTOM_RIGHT

    private fun near(expected: Offset, actual: Offset, eps: Float = 2f) {
        assertTrue(
            "expected $expected but was $actual",
            abs(expected.x - actual.x) <= eps && abs(expected.y - actual.y) <= eps,
        )
    }

    @Test
    fun `说明书自动轨迹 t=0 是右下角小卷`() {
        val a = autoPlayTouch(0f, br, w, h)
        near(Offset(1044f, 2292f), a)
        val frame = peelFrame(a, br, w, h, bindingOnly = false)!!
        near(Offset(1044f, 2292f), frame.touch)
        near(Offset(1005f, 2340f), frame.bezierStart1, eps = 3f)
        near(Offset(1080f, 2284f), frame.bezierStart2, eps = 3f)
        assertTrue(frame.touchToCorner in 55f..65f)
        assertTrue(peelBackArea(frame) / (w * h) < 0.01f)
    }

    @Test
    fun `说明书自动轨迹半程主帧`() {
        val a = autoPlayTouch(0.5f, br, w, h)
        near(Offset(544f, 1801f), a)
        val frame = peelFrame(a, br, w, h, bindingOnly = false)!!
        near(Offset(544f, 1801f), frame.touch)
        near(Offset(272f, 2340f), frame.bezierStart1, eps = 3f)
        near(Offset(1080f, 1536f), frame.bezierStart2, eps = 3f)
        near(Offset(812f, 2071f), frame.mid, eps = 3f)
        val backPct = peelBackArea(frame) / (w * h)
        assertTrue("back area should be a sheet not a strip, was $backPct", backPct in 0.03f..0.08f)
        assertTrue(frame.touchToCorner in 740f..780f)
    }

    @Test
    fun `终点钉在装订边且触点落到对页`() {
        val a = autoPlayTouch(1f, br, w, h)
        near(Offset(-764f, 1576f), a, eps = 3f)
        val frame = peelFrame(a, br, w, h, bindingOnly = true)!!
        assertTrue(frame.touch.x < 0f)
        assertTrue(hypot(frame.touch.x, frame.touch.y - h) <= w + 1.5f)
        assertTrue(hypot(frame.touch.x - w, frame.touch.y) > 1f)
        assertTrue(peelBackArea(frame) / (w * h) > 0.12f)
    }

    @Test
    fun `对边圆约束把过远触点拉回`() {
        val raw = Offset(w - 8f, h * 0.55f)
        val clamped = clampPeelTouch(raw, br, w, h)
        val f = Offset(w, h)
        // 底边圆：圆心 (0,H) 半径 W
        assertTrue(hypot(clamped.x - 0f, clamped.y - h) <= w + 1f)
        assertTrue(hypot(clamped.x - w, clamped.y - 0f) <= h + 1f)
        assertTrue(hypot(clamped.x - f.x, clamped.y - f.y) > PEEL_MIN_DRAG)
        assertFalse(clamped == raw)
    }

    @Test
    fun `Householder 反射等距且页角与触点互为像`() {
        val frame = peelFrame(autoPlayTouch(0.5f, br, w, h), br, w, h)!!
        near(frame.touch, peelReflected(frame.cornerPoint, frame), eps = 1.5f)
        near(frame.cornerPoint, peelReflected(frame.touch, frame), eps = 1.5f)
        val p = Offset(200f, 400f)
        val q = Offset(800f, 1900f)
        val d0 = hypot(p.x - q.x, p.y - q.y)
        val rp = peelReflected(p, frame)
        val rq = peelReflected(q, frame)
        val d1 = hypot(rp.x - rq.x, rp.y - rq.y)
        assertEquals(d0, d1, 0.5f)
    }

    @Test
    fun `矩阵与 peelReflected 一致`() {
        val frame = peelFrame(autoPlayTouch(0.375f, br, w, h), br, w, h)!!
        val m = peelReflectionMatrixValues(frame)
        val p = Offset(600f, 2000f)
        val viaFn = peelReflected(p, frame)
        val viaM = Offset(
            m[0] * p.x + m[1] * p.y + m[2],
            m[3] * p.x + m[4] * p.y + m[5],
        )
        near(viaFn, viaM, eps = 0.6f)
    }

    @Test
    fun `起手过近则没有帧`() {
        assertNull(peelFrame(Offset(w - 2f, h - 2f), br, w, h))
    }

    @Test
    fun `向后翻镜像到左下角`() {
        val a = autoPlayTouch(0f, PeelCorner.BOTTOM_LEFT, w, h)
        near(Offset(w - 1044f, 2292f), a)
        assertTrue(a.x < 80f)
    }

    @Test
    fun `双页右叶不越中缝`() {
        val (left, right) = peelLeaves(
            dual = true,
            contentWidth = 2000f,
            contentHeight = 1600f,
            pageWidth = 960f,
            splitLeft = 980f,
            splitRight = 1020f,
        )
        assertNotNull(right)
        assertEquals(960f, left.width, 0.1f)
        assertEquals(960f, right!!.width, 0.1f)
        assertTrue(left.right <= 980f + 1f)
        assertTrue(right.originX >= 1020f - 1f)
        val active = activePeelLeaf(forward = true, left, right)
        assertEquals(right.originX, active.originX, 0.1f)
        val back = activePeelLeaf(forward = false, left, right)
        assertEquals(left.originX, back.originX, 0.1f)
    }

    @Test
    fun `单页就是整块内容区`() {
        val (left, right) = peelLeaves(false, 1080f, 2340f, 1080f, 0f, 0f)
        assertNull(right)
        assertEquals(1080f, left.width, 0.1f)
        assertEquals(0f, left.originX, 0.1f)
    }

    @Test
    fun `悬停上半内容区按叶高算轨迹`() {
        val leafH = 900f
        val a = autoPlayTouch(0.5f, br, w, leafH)
        val frame = peelFrame(a, br, w, leafH)!!
        assertTrue(frame.cornerPoint.y <= leafH + 0.1f)
        assertTrue(peelBackArea(frame) > 0f)
    }

    @Test
    fun `双页左右叶不相交`() {
        val (left, right) = peelLeaves(true, 2000f, 1600f, 960f, 980f, 1020f)
        assertNotNull(right)
        assertTrue(left.right <= right!!.originX + 0.1f)
    }

    @Test
    fun `双页终帧纸背伸到对页但装订角仍钉住`() {
        val pageW = 960f
        val pageH = 1600f
        val a = autoPlayTouch(1f, br, pageW, pageH)
        val frame = peelFrame(a, br, pageW, pageH, bindingOnly = true)!!
        assertTrue(frame.touch.x < 0f)
        assertTrue(hypot(frame.touch.x, frame.touch.y - pageH) <= pageW + 1.5f)
        val (left, right) = peelLeaves(true, 2000f, pageH, pageW, 980f, 1020f)
        val (extL, extR) = peelFlapExtend(br, right!!, left, right)
        assertTrue(extL > 900f)
        assertEquals(0f, extR, 0.1f)
        assertTrue(frame.touch.x > -extL)
    }

    @Test
    fun `双页终帧触点落到对页远角才能盖住另一叶`() {
        val pageW = 960f
        val pageH = 1600f
        val opp = 960f
        near(Offset(-opp, pageH), dualCoverPoint(br, pageW, pageH, opp))
        val a = autoPlayTouch(1f, br, pageW, pageH, opp)
        near(Offset(-opp, pageH), a, eps = 2f)
        val frame = peelFrame(a, br, pageW, pageH, bindingOnly = true, oppositeWidth = opp)!!
        assertTrue(frame.touch.x <= -pageW + 2f)
        assertEquals(pageH, frame.touch.y, 2f)
        assertTrue(hypot(frame.touch.x, frame.touch.y - pageH) <= pageW + 1.5f)
        near(Offset(-opp, 0f), dualCoverPoint(PeelCorner.TOP_RIGHT, pageW, pageH, opp))
        near(Offset(pageW + opp, pageH), dualCoverPoint(PeelCorner.BOTTOM_LEFT, pageW, pageH, opp))
        near(Offset(pageW + opp, 0f), dualCoverPoint(PeelCorner.TOP_LEFT, pageW, pageH, opp))
    }

    @Test
    fun `横屏按短边比例即可完成不必拖过对角线`() {
        val w = 2340f
        val h = 1080f
        val far = Offset(w - 200f, h)
        val nearTouch = Offset(w - 80f, h)
        assertTrue(peelEndShouldComplete(far, br, w, h, 0f, 0f))
        assertFalse(peelEndShouldComplete(nearTouch, br, w, h, 0f, 0f))
        val swipeLike = Offset(w - 110f, h)
        assertTrue(peelEndShouldComplete(swipeLike, br, w, h, 0f, 0f, completeDistancePx = 100f))
        assertFalse(peelEndShouldComplete(nearTouch, br, w, h, 0f, 0f, completeDistancePx = 100f))
    }

    @Test
    fun `底边与侧边反射落在 A-C 连线上`() {
        val frame = peelFrame(autoPlayTouch(0.5f, br, w, h), br, w, h)!!
        val f = frame.cornerPoint
        val c1 = frame.bezierControl1
        val c2 = frame.bezierControl2
        near(c1, peelReflected(c1, frame), eps = 1.5f)
        near(c2, peelReflected(c2, frame), eps = 1.5f)
        val onBottom = Offset((f.x + c1.x) / 2f, f.y)
        val onRight = Offset(f.x, (f.y + c2.y) / 2f)
        assertTrue(
            "bottom edge should map onto A-C1, dist=${peelDistanceToLine(peelReflected(onBottom, frame), frame.touch, c1)}",
            peelDistanceToLine(peelReflected(onBottom, frame), frame.touch, c1) < 1.5f,
        )
        assertTrue(
            "right edge should map onto A-C2, dist=${peelDistanceToLine(peelReflected(onRight, frame), frame.touch, c2)}",
            peelDistanceToLine(peelReflected(onRight, frame), frame.touch, c2) < 1.5f,
        )
    }

    @Test
    fun `页角选择上半用顶角`() {
        assertEquals(PeelCorner.TOP_RIGHT, peelCornerFor(Offset(1000f, 200f), w, h, forward = true))
        assertEquals(PeelCorner.BOTTOM_LEFT, peelCornerFor(Offset(80f, 2000f), w, h, forward = false))
    }
}

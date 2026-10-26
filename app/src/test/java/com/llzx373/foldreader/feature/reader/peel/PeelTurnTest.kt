package com.llzx373.foldreader.feature.reader.peel

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeelTurnTest {

    private val w = 1080f
    private val h = 2340f
    private val br = PeelCorner.BOTTOM_RIGHT

    @Test
    fun `半程主帧超过完成比例`() {
        val touch = autoPlayTouch(0.5f, br, w, h)
        assertTrue(peelEndShouldComplete(touch, br, w, h, velocityX = 0f, velocityY = 0f))
    }

    @Test
    fun `起手小卷未过线且无甩速则取消`() {
        val touch = autoPlayTouch(0f, br, w, h)
        assertFalse(peelEndShouldComplete(touch, br, w, h, velocityX = 0f, velocityY = 0f))
    }

    @Test
    fun `未过线但沿离开页角方向甩得够快则完成`() {
        val touch = autoPlayTouch(0.05f, br, w, h)
        val f = peelCornerPoint(br, w, h)
        val nx = touch.x - f.x
        val ny = touch.y - f.y
        val len = kotlin.math.hypot(nx, ny)
        val vx = nx / len * (PEEL_FLING_PX_PER_SEC + 50f)
        val vy = ny / len * (PEEL_FLING_PX_PER_SEC + 50f)
        assertTrue(peelEndShouldComplete(touch, br, w, h, vx, vy))
    }

    @Test
    fun `甩向页角则取消`() {
        val touch = autoPlayTouch(0.05f, br, w, h)
        val f = peelCornerPoint(br, w, h)
        val nx = touch.x - f.x
        val ny = touch.y - f.y
        val len = kotlin.math.hypot(nx, ny)
        val vx = -nx / len * 2000f
        val vy = -ny / len * 2000f
        assertFalse(peelEndShouldComplete(touch, br, w, h, vx, vy))
    }

    @Test
    fun `完成弹簧过冲时触点不越过终点`() {
        val origin = Offset(700f, 1900f)
        val target = Offset(-w, h)
        val clamped = peelClampCompleteTouch(Offset(-w - 300f, h + 200f), origin, target)
        assertEquals(target, clamped)
        val backward = peelClampCompleteTouch(Offset(900f, 1700f), origin, target)
        assertEquals(origin, backward)
    }

    @Test
    fun `完成首帧沿用拖动时的装订约束`() {
        assertFalse(peelCrossedBinding(Offset(500f, 1800f), br, w))
        assertTrue(peelCrossedBinding(Offset(-20f, h), br, w))
        assertTrue(peelCrossedBinding(Offset(w + 20f, h), PeelCorner.BOTTOM_LEFT, w))
    }
}

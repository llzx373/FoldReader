package com.llzx373.foldreader.core.foldable

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FoldingPostureTest {

    private val verticalHingeBounds = Rect(412f, 0f, 424f, 2208f)
    private val horizontalHingeBounds = Rect(0f, 1098f, 1768f, 1110f)

    @Test
    fun `无铰链设备返回 CLOSED 且铰链信息为空`() {
        val posture = (null as HingeInfo?).toFoldingPosture()

        assertEquals(Posture.CLOSED, posture.posture)
        assertNull(posture.hingeBounds)
        assertNull(posture.hingeOrientation)
    }

    @Test
    fun `垂直铰链 HALF_OPENED 映射为 HALF_OPENED 姿态`() {
        val posture = HingeInfo(
            bounds = verticalHingeBounds,
            orientation = HingeOrientation.VERTICAL,
            state = HingeState.HALF_OPENED,
        ).toFoldingPosture()

        assertEquals(Posture.HALF_OPENED, posture.posture)
        assertEquals(verticalHingeBounds, posture.hingeBounds)
        assertEquals(HingeOrientation.VERTICAL, posture.hingeOrientation)
    }

    @Test
    fun `垂直铰链 FLAT 为展开书式姿态`() {
        val posture = HingeInfo(
            bounds = verticalHingeBounds,
            orientation = HingeOrientation.VERTICAL,
            state = HingeState.FLAT,
        ).toFoldingPosture()

        assertEquals(Posture.FLAT, posture.posture)
        assertEquals(HingeOrientation.VERTICAL, posture.hingeOrientation)
        assertEquals(verticalHingeBounds, posture.hingeBounds)
    }

    @Test
    fun `水平铰链 FLAT 映射为 FLAT 姿态`() {
        val posture = HingeInfo(
            bounds = horizontalHingeBounds,
            orientation = HingeOrientation.HORIZONTAL,
            state = HingeState.FLAT,
        ).toFoldingPosture()

        assertEquals(Posture.FLAT, posture.posture)
        assertEquals(HingeOrientation.HORIZONTAL, posture.hingeOrientation)
        assertEquals(horizontalHingeBounds, posture.hingeBounds)
    }

    @Test
    fun `水平铰链 HALF_OPENED 为半折桌面姿态`() {
        val posture = HingeInfo(
            bounds = horizontalHingeBounds,
            orientation = HingeOrientation.HORIZONTAL,
            state = HingeState.HALF_OPENED,
        ).toFoldingPosture()

        assertEquals(Posture.HALF_OPENED, posture.posture)
        assertEquals(HingeOrientation.HORIZONTAL, posture.hingeOrientation)
        assertEquals(horizontalHingeBounds, posture.hingeBounds)
    }
}

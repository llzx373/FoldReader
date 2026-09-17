package com.llzx373.foldreader.core.foldable

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowPortraitTest {

    @Test
    fun `窗口方向只看真实宽高`() {
        // 实测阔折叠（Xiaomi 2608BPX34C 内屏 2364×1672px @440dpi）
        assertFalse(isPortraitWindow(860, 608))
        assertTrue(isPortraitWindow(608, 860))

        assertTrue(isPortraitWindow(360, 800))
        assertFalse(isPortraitWindow(800, 360))
        // 宽高相等时不判竖向：没有"并排会压窄"的问题
        assertFalse(isPortraitWindow(600, 600))
        assertFalse(isPortraitWindow(0, 0))
    }
}

package com.llzx373.foldreader.core.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ReadingPreferencesTest {

    @Test
    fun `枚举名有效时解析为对应枚举`() {
        assertEquals(ReadingTheme.NIGHT, enumOrDefault("NIGHT", ReadingTheme.GREEN))
        assertEquals(PageTurnMode.SCROLL, enumOrDefault("SCROLL", PageTurnMode.COVER))
    }

    @Test
    fun `枚举名非法时回落默认值`() {
        assertEquals(ReadingTheme.GREEN, enumOrDefault("NOT_A_THEME", ReadingTheme.GREEN))
    }

    @Test
    fun `枚举名为空时回落默认值`() {
        assertEquals(PageTurnMode.COVER, enumOrDefault(null, PageTurnMode.COVER))
    }

    @Test
    fun `默认阅读偏好符合约定`() {
        val defaults = ReadingPreferences()

        assertEquals(18f, defaults.fontSizeSp, 0.0001f)
        assertEquals(1.5f, defaults.lineSpacingMultiplier, 0.0001f)
        assertEquals(ReadingTheme.GREEN, defaults.themeId)
        assertEquals(PageTurnMode.COVER, defaults.pageTurnMode)
        assertEquals(0.3f, defaults.pageTurnHotspotRatio, 0.0001f)
        assertFalse(defaults.volumeKeyPagingEnabled)
        assertFalse(defaults.keepScreenOn)
    }
}

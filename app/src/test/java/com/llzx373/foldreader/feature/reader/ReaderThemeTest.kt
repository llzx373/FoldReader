package com.llzx373.foldreader.feature.reader

import androidx.compose.ui.graphics.Color
import com.llzx373.foldreader.core.data.settings.ReadingTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderThemeTest {

    @Test
    fun `纸张主题使用暖纸底深暖灰正文和油墨蓝`() {
        val colors = readerColors(ReadingTheme.PAPER)

        assertEquals(Color(0xFFF5F4ED), colors.background)
        assertEquals(Color(0xFF2D2C28), colors.text)
        assertEquals(Color(0xFF1B365D), colors.accent)
    }

    @Test
    fun `纸张替换主题列表中的纯黑`() {
        assertTrue(themePresets.contains(ReadingTheme.PAPER to "纸张"))
        assertFalse(themePresets.any { it.second == "纯黑" })
    }
}

package com.llzx373.foldreader.core.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingPreferencesTest {

    @Test
    fun `枚举名有效时解析为对应枚举`() {
        assertEquals(ReadingTheme.NIGHT, enumOrDefault("NIGHT", ReadingTheme.GREEN))
        assertEquals(PageTurnMode.SCROLL, enumOrDefault("SCROLL", PageTurnMode.COVER))
        assertEquals(PageTurnMode.SIMULATION, enumOrDefault("SIMULATION", PageTurnMode.COVER))
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
        assertEquals(40, defaults.maxLineChars)
        assertEquals(0.4f, defaults.paragraphSpacingEm, 0.0001f)
        assertEquals(0f, defaults.letterSpacingEm, 0.0001f)
        assertEquals(ReadingTheme.GREEN, defaults.themeId)
        assertEquals(PageTurnMode.COVER, defaults.pageTurnMode)
        assertEquals(0.3f, defaults.pageTurnHotspotRatio, 0.0001f)
        assertFalse(defaults.volumeKeyPagingEnabled)
        assertFalse(defaults.keepScreenOn)
    }

    @Test
    fun `分页参数新字段按值读写`() {
        val prefs = ReadingPreferences(
            maxLineChars = 24,
            paragraphSpacingEm = 0.8f,
            letterSpacingEm = 0.1f,
        )
        assertEquals(24, prefs.maxLineChars)
        assertEquals(0.8f, prefs.paragraphSpacingEm, 0.0001f)
        assertEquals(0.1f, prefs.letterSpacingEm, 0.0001f)
        val restored = prefs.copy()
        assertEquals(prefs, restored)
        assertEquals(prefs.hashCode(), restored.hashCode())
    }

    @Test
    fun `滑动翻页手势开关默认开启`() {
        assertTrue(ReadingPreferences().swipeGestureEnabled)
    }

    /**
     * 横滑判据的两个阈值有默认值，且刻意不是「屏宽比例」量级：
     * 40dp 才是一拇指的自然行程，原实现按屏宽 15% 在展开态会长到 110–130dp。
     */
    @Test
    fun `横滑判定阈值有可用默认值`() {
        val defaults = ReadingPreferences()

        assertEquals(40f, defaults.swipeDistanceDp, 0.0001f)
        assertEquals(500f, defaults.swipeFlingVelocityDpPerSec, 0.0001f)
        assertTrue(defaults.swipeDistanceDp in 20f..80f)
        assertTrue(defaults.swipeFlingVelocityDpPerSec in 200f..1200f)
    }

    @Test
    fun `横滑判定阈值按值读写`() {
        val prefs = ReadingPreferences(swipeDistanceDp = 25f, swipeFlingVelocityDpPerSec = 800f)

        assertEquals(25f, prefs.swipeDistanceDp, 0.0001f)
        assertEquals(800f, prefs.swipeFlingVelocityDpPerSec, 0.0001f)
        val restored = prefs.copy()
        assertEquals(prefs, restored)
        assertEquals(prefs.hashCode(), restored.hashCode())
    }

    @Test
    fun `滑动翻页手势开关按值读写`() {
        val prefs = ReadingPreferences(swipeGestureEnabled = false)

        assertFalse(prefs.swipeGestureEnabled)
        val restored = prefs.copy()
        assertEquals(prefs, restored)
        assertEquals(prefs.hashCode(), restored.hashCode())
    }

    @Test
    fun `自定义章节规则默认为空列表`() {
        assertEquals(emptyList<String>(), ReadingPreferences().customChapterRules)
    }

    @Test
    fun `自定义章节规则按值读写`() {
        val rules = listOf("^【.+】$", "^卷 \\d+ .+")
        val prefs = ReadingPreferences(customChapterRules = rules)

        assertEquals(rules, prefs.customChapterRules)
        val restored = prefs.copy()
        assertEquals(prefs, restored)
        assertEquals(prefs.hashCode(), restored.hashCode())
    }

    @Test
    fun `自定义章节规则编码解码往返一致`() {
        val rules = listOf("^【.+】$", "^第[稀松]章$", "^\\d{1,3}、.*")

        assertEquals(rules, decodeCustomChapterRules(encodeCustomChapterRules(rules)))
        assertEquals(emptyList<String>(), decodeCustomChapterRules(null))
        assertEquals(emptyList<String>(), decodeCustomChapterRules(""))
        assertEquals(emptyList<String>(), decodeCustomChapterRules(encodeCustomChapterRules(emptyList())))
    }

    @Test
    fun `去广告行规则默认为空列表`() {
        assertEquals(emptyList<String>(), ReadingPreferences().adCleanRules)
    }

    @Test
    fun `规则列表编码解码往返一致`() {
        val rules = listOf("公众号", "^【广告】.*$", "感谢支持\\s*正版")

        assertEquals(rules, decodeRuleList(encodeRuleList(rules)))
        assertEquals(emptyList<String>(), decodeRuleList(null))
        assertEquals(emptyList<String>(), decodeRuleList(""))
        assertEquals(emptyList<String>(), decodeRuleList(encodeRuleList(emptyList())))
    }
}

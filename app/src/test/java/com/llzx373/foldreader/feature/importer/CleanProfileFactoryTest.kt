package com.llzx373.foldreader.feature.importer

import com.llzx373.foldreader.core.data.settings.ReadingPreferences
import com.llzx373.foldreader.core.format.clean.CleanLevel
import com.llzx373.foldreader.core.format.clean.CleanToggles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 清洗配方映射：档位取预设、CUSTOM 取逐项开关、繁简正交、坏正则不炸整条链路。
 * 「浏览」与「批量导入」跟随设置页靠的就是这里的 [ReadingPreferences.cleanLevel] 分支。
 */
class CleanProfileFactoryTest {

    @Test
    fun `非自定义档位取档位预设 忽略逐项开关`() {
        val prefs = ReadingPreferences(
            cleanLevel = CleanLevel.CONSERVATIVE,
            // 逐项开关只在 CUSTOM 档有意义，这里刻意留成空集：它不该被用上
            cleanToggles = CleanToggles.NONE,
        )

        val profile = cleanProfileOf(prefs, CleanLevel.CONSERVATIVE, convertTraditional = false)

        assertEquals(CleanLevel.CONSERVATIVE, profile.level)
        assertEquals(CleanToggles.preset(CleanLevel.CONSERVATIVE), profile.toggles)
    }

    @Test
    fun `自定义档位取逐项开关`() {
        val custom = CleanToggles.NONE.copy(unifyChars = true, reflowParagraphs = true)
        val prefs = ReadingPreferences(cleanLevel = CleanLevel.CUSTOM, cleanToggles = custom)

        val profile = cleanProfileOf(prefs, CleanLevel.CUSTOM, convertTraditional = false)

        assertEquals(custom, profile.toggles)
    }

    @Test
    fun `繁简由参数决定 与档位无关`() {
        val prefs = ReadingPreferences(cleanLevel = CleanLevel.AGGRESSIVE)

        assertFalse(cleanProfileOf(prefs, CleanLevel.AGGRESSIVE, false).toggles.traditionalToSimplified)
        assertTrue(cleanProfileOf(prefs, CleanLevel.AGGRESSIVE, true).toggles.traditionalToSimplified)
    }

    @Test
    fun `自定义广告正则进配方 写坏的那条被丢掉`() {
        val prefs = ReadingPreferences(adCleanRules = listOf("广告\\d+", "（["))

        val profile = cleanProfileOf(prefs, CleanLevel.STANDARD, convertTraditional = false)

        assertEquals(1, profile.adPatterns.size)
        assertTrue(profile.adPatterns.single().containsMatchIn("广告12"))
    }

    @Test
    fun `无任何规则时 isNoop 为真`() {
        val prefs = ReadingPreferences(
            cleanLevel = CleanLevel.CUSTOM,
            cleanToggles = CleanToggles.NONE,
            adCleanRules = emptyList(),
        )

        assertTrue(cleanProfileOf(prefs, CleanLevel.CUSTOM, false).isNoop)
    }
}

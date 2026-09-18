package com.llzx373.foldreader.core.format.clean

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CleanTogglesTest {

    @Test
    fun `全部关闭是空配方`() {
        assertTrue(CleanProfile.NONE.isNoop)
        assertTrue(CleanProfile(toggles = CleanToggles.NONE).isNoop)
        assertFalse(CleanProfile(level = CleanLevel.STANDARD).isNoop)
    }

    @Test
    fun `有自定义广告正则时不是空配方`() {
        val profile = CleanProfile(
            level = CleanLevel.CUSTOM,
            toggles = CleanToggles.NONE,
            adPatterns = listOf(Regex("广告")),
        )
        assertFalse(profile.isNoop)
    }

    @Test
    fun `档位越高启用的规则越多且逐级包含`() {
        val entries = CleanToggles.ENTRIES
        fun enabled(level: CleanLevel) = entries.filter { it.get(CleanToggles.preset(level)) }.map { it.key }

        val conservative = enabled(CleanLevel.CONSERVATIVE)
        val standard = enabled(CleanLevel.STANDARD)
        val aggressive = enabled(CleanLevel.AGGRESSIVE)

        assertTrue(standard.containsAll(conservative))
        assertTrue(aggressive.containsAll(standard))
        assertTrue(aggressive.size > standard.size)
        assertTrue(standard.size > conservative.size)
    }

    @Test
    fun `保守档不做段落重组与章节修复`() {
        val toggles = CleanToggles.preset(CleanLevel.CONSERVATIVE)
        assertFalse(toggles.reflowParagraphs)
        assertFalse(toggles.repairChapters)
        assertFalse(toggles.canonicalIndent)
        assertTrue(toggles.unifyChars)
        assertTrue(toggles.filterNoise)
    }

    @Test
    fun `预设与档位一致地构造 profile`() {
        assertEquals(
            CleanToggles.preset(CleanLevel.CONSERVATIVE),
            CleanProfile(level = CleanLevel.CONSERVATIVE).toggles,
        )
    }

    @Test
    fun `序列化往返一致`() {
        for (level in listOf(CleanLevel.CONSERVATIVE, CleanLevel.STANDARD, CleanLevel.AGGRESSIVE)) {
            val toggles = CleanToggles.preset(level)
            assertEquals(toggles, CleanToggles.decode(CleanToggles.encode(toggles)))
        }
        val arbitrary = CleanToggles.NONE.copy(unifyChars = true, maskRuns = true, normalizeQuotes = true)
        assertEquals(arbitrary, CleanToggles.decode(CleanToggles.encode(arbitrary)))
    }

    @Test
    fun `序列化长度等于开关条目数`() {
        assertEquals(CleanToggles.ENTRIES.size, CleanToggles.encode(CleanToggles.NONE).length)
    }

    @Test
    fun `长度不符的串判为无效`() {
        assertNull(CleanToggles.decode(null))
        assertNull(CleanToggles.decode(""))
        assertNull(CleanToggles.decode("0101"))
    }

    @Test
    fun `条目 key 唯一且可写回`() {
        assertEquals(CleanToggles.ENTRIES.size, CleanToggles.ENTRIES.map { it.key }.toSet().size)
        CleanToggles.ENTRIES.forEach { entry ->
            val flipped = entry.set(CleanToggles.NONE, true)
            assertTrue("${entry.key} 写不进去", entry.get(flipped))
            val back = entry.set(flipped, false)
            assertFalse(entry.get(back))
        }
    }

    @Test
    fun `条目 key 与备份字段可见`() {
        assertNotNull(CleanToggles.ENTRIES.firstOrNull { it.key == "traditionalToSimplified" })
        assertNotNull(CleanToggles.ENTRIES.firstOrNull { it.key == "reflowParagraphs" })
    }
}

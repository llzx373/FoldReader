package com.llzx373.foldreader.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScrollSelectionMappingTest {

    private val pageStart = 1000L
    private val pageText = "白日依山尽，黄河入海流。欲穷千里目，更上一层楼。"

    @Test
    fun `unique text maps to its page offset`() {
        val range = mapSelectedTextToOffsets(pageStart, pageText, "黄河入海流")!!
        assertEquals(pageStart + 6, range.first)
        assertEquals(pageStart + 11, range.last + 1)
    }

    @Test
    fun `missing text returns null`() {
        assertNull(mapSelectedTextToOffsets(pageStart, pageText, "床前明月光"))
        assertNull(mapSelectedTextToOffsets(pageStart, pageText, ""))
        assertNull(mapSelectedTextToOffsets(pageStart, "", "白日"))
    }

    @Test
    fun `repeated text without hint takes first match`() {
        val text = "甲乙丙甲乙丙"
        val range = mapSelectedTextToOffsets(0L, text, "甲乙丙")!!
        assertEquals(0L until 3L, range)
    }

    @Test
    fun `repeated text resolves to match nearest the hint`() {
        val text = "甲乙丙丁甲乙丙戊甲乙丙"
        // hint 落在第二处附近 → 取第二处
        assertEquals(4L until 7L, mapSelectedTextToOffsets(0L, text, "甲乙丙", preferCharIndex = 5L))
        // hint 落在第三处附近 → 取第三处
        assertEquals(8L until 11L, mapSelectedTextToOffsets(0L, text, "甲乙丙", preferCharIndex = 9L))
        // hint 在文本之前 → 取第一处
        assertEquals(0L until 3L, mapSelectedTextToOffsets(0L, text, "甲乙丙", preferCharIndex = 0L))
    }

    @Test
    fun `page offset shifts result`() {
        val text = "甲乙丙甲乙丙"
        assertEquals(103L until 106L, mapSelectedTextToOffsets(100L, text, "甲乙丙", preferCharIndex = 105L))
    }
}

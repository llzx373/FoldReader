package com.llzx373.foldreader.core.format

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** 内存 BookContent 的区间切片口径。 */
class StringBookContentTest {

    private val content = StringBookContent("第一段\n第二段\n第三段")

    @Test
    fun `charCount 即字符串长且恒为终值`() {
        assertEquals(11L, content.charCount)
        assertEquals(true, content.isCharCountFinal)
    }

    @Test
    fun `read 按闭区间切片`() = runBlocking {
        assertEquals("第一段", content.read(0L..2L))
        assertEquals("第二段", content.read(4L..6L))
        assertEquals("\n", content.read(3L..3L))
    }

    @Test
    fun `read 越界 clamp 不抛异常`() = runBlocking {
        assertEquals("第三段", content.read(8L..99L))
        assertEquals("", content.read(99L..120L))
        assertEquals("", content.read(5L..4L))
    }
}

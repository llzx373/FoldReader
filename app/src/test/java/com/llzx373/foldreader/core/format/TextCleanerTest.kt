package com.llzx373.foldreader.core.format

import java.io.BufferedReader
import java.io.StringReader
import java.io.StringWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class TextCleanerTest {

    private val tsMap = mapOf('體' to '体', '學' to '学', '廣' to '广', '開' to '开')

    @Test
    fun `无操作选项时恒等返回原文`() {
        val text = "第一行\n\n廣告行\n"
        val options = TextCleaner.CleanOptions()

        assertSame(text, TextCleaner.clean(text, options, tsMap))
    }

    @Test
    fun `去空行移除仅含空白字符的行`() {
        val text = "第一行\n\n   \n\t\n第二行\n"
        val options = TextCleaner.CleanOptions(removeBlankLines = true)

        assertEquals("第一行\n第二行\n", TextCleaner.clean(text, options))
    }

    @Test
    fun `未开启去空行时空行保留`() {
        val text = "第一行\n\n第二行\n"
        val options = TextCleaner.CleanOptions()

        assertEquals(text, TextCleaner.clean(text, options))
    }

    @Test
    fun `广告行匹配任一正则整行删除`() {
        val text = "正文一\n请关注公众号xx\n正文二\n【广告】下载app\n正文三\n"
        val options = TextCleaner.CleanOptions(
            adPatterns = listOf(Regex("公众号"), Regex("^【广告】")),
        )

        assertEquals("正文一\n正文二\n正文三\n", TextCleaner.clean(text, options))
    }

    @Test
    fun `繁简转换逐字映射`() {
        val text = "身體學習開門\n"
        val options = TextCleaner.CleanOptions(traditionalToSimplified = true)

        assertEquals("身体学習开門\n", TextCleaner.clean(text, options, tsMap))
    }

    @Test
    fun `繁简转换无映射表时原样输出`() {
        val text = "身體學習\n"
        val options = TextCleaner.CleanOptions(traditionalToSimplified = true)

        assertEquals("身體學習\n", TextCleaner.clean(text, options, emptyMap()))
    }

    @Test
    fun `管线顺序为繁简再去广告再去空行`() {
        val text = "開頭\n廣告推送\n\n結尾\n"
        val options = TextCleaner.CleanOptions(
            removeBlankLines = true,
            adPatterns = listOf(Regex("广告")),
            traditionalToSimplified = true,
        )

        assertEquals("开頭\n結尾\n", TextCleaner.clean(text, options, tsMap))
    }

    @Test
    fun `空输入清理后仍为空`() {
        val options = TextCleaner.CleanOptions(
            removeBlankLines = true,
            adPatterns = listOf(Regex("广告")),
            traditionalToSimplified = true,
        )

        assertEquals("", TextCleaner.clean("", options, tsMap))
    }

    @Test
    fun `流式版本与整段清理结果一致且兼容 CRLF`() {
        val text = "第一行\r\n\r\n廣告行\r\n第二行\r\n"
        val options = TextCleaner.CleanOptions(
            removeBlankLines = true,
            adPatterns = listOf(Regex("广告")),
            traditionalToSimplified = true,
        )

        val expected = TextCleaner.clean(text, options, tsMap)
        val writer = StringWriter()
        TextCleaner.cleanStream(BufferedReader(StringReader(text)), writer, options, tsMap)

        assertEquals("第一行\n第二行\n", expected)
        assertEquals(expected, writer.toString())
    }

    @Test
    fun `TS 映射表解析连续字符对并跳过代理对`() {
        val parsed = TsCharMap.parse("體体學学\uD840\uDC00\uD841\uDC01")

        assertEquals(mapOf('體' to '体', '學' to '学'), parsed)
    }
}

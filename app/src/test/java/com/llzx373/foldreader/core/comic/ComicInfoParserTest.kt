package com.llzx373.foldreader.core.comic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser
import org.xmlpull.v1.XmlPullParser

/** ComicInfo.xml 解析（M31）：字段提取、宽松容错、非 ComicInfo 根拒绝。 */
class ComicInfoParserTest {

    private val newParser: () -> XmlPullParser = { KXmlParser() }

    private fun parse(xml: String) = ComicInfoParser.parse(xml.toByteArray(Charsets.UTF_8), newParser)

    @Test
    fun `完整 ComicInfo 解析出系列卷号作者`() {
        val info = parse(
            """<?xml version="1.0" encoding="utf-8"?>
            <ComicInfo>
              <Title>某科学的一方通行 第3卷</Title>
              <Series>某科学的一方通行</Series>
              <Number>3</Number>
              <Writer>鎌池和馬</Writer>
              <Publisher>角川</Publisher>
              <Summary>简介文本</Summary>
            </ComicInfo>""",
        )!!
        assertEquals("某科学的一方通行", info.series)
        assertEquals("3", info.number)
        assertEquals("鎌池和馬", info.writer)
        assertEquals("某科学的一方通行 第3卷", info.title)
        assertEquals("角川", info.publisher)
        assertEquals("简介文本", info.summary)
    }

    @Test
    fun `缺字段宽容解析`() {
        val info = parse("<ComicInfo><Series>Foo</Series></ComicInfo>")!!
        assertEquals("Foo", info.series)
        assertNull(info.number)
        assertNull(info.writer)
    }

    @Test
    fun `非 ComicInfo 根拒绝`() {
        assertNull(parse("<Package><Title>foo</Title></Package>"))
        assertNull(parse("not xml at all"))
        assertNull(parse(""))
    }

    @Test
    fun `空白字段视为缺失，嵌套结构不误收`() {
        val info = parse(
            """<ComicInfo>
              <Series>Foo</Series>
              <Writer>  </Writer>
              <Pages><Page Image="0"/><Page Image="1"/></Pages>
            </ComicInfo>""",
        )!!
        assertEquals("Foo", info.series)
        assertNull(info.writer)
    }

    @Test
    fun `一个认识字段都没有时返回 null`() {
        assertNull(parse("<ComicInfo><Count>12</Count></ComicInfo>"))
    }

    @Test
    fun `条目路径判定大小写不敏感且任意深度`() {
        assertTrue(ComicInfoParser.isComicInfoPath("ComicInfo.xml"))
        assertTrue(ComicInfoParser.isComicInfoPath("vol3/comicinfo.XML"))
        assertTrue(!ComicInfoParser.isComicInfoPath("ComicInfo.xml.bak"))
        assertTrue(!ComicInfoParser.isComicInfoPath("01.jpg"))
    }
}

package com.llzx373.foldreader.core.format.epub

import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.kxml2.io.KXmlParser
import org.xmlpull.v1.XmlPullParser

class EpubStructureTest {

    private val newParser: () -> XmlPullParser = { KXmlParser() }

    private fun parse(entries: LinkedHashMap<String, String>): EpubStructure {
        val file = File.createTempFile("structure", ".epub")
        try {
            TestEpubs.write(file, entries)
            return ZipFile(file).use { EpubStructure.parse(it, newParser) }
        } finally {
            file.delete()
        }
    }

    @Test
    fun `EPUB2 解析 NCX 目录 嵌套拍平且丢弃 fragment`() {
        val structure = parse(TestEpubs.epub2())
        assertEquals("测试之书", structure.title)
        assertEquals("作者甲", structure.creator)
        assertEquals(
            listOf("OEBPS/text/ch1.xhtml", "OEBPS/text/ch2.xhtml"),
            structure.spine.map { it.file },
        )
        assertEquals(
            listOf(
                EpubStructure.TocEntry("第一章", "OEBPS/text/ch1.xhtml"),
                EpubStructure.TocEntry("第一章 第一节", "OEBPS/text/ch1.xhtml", fragment = "s1"),
                EpubStructure.TocEntry("第二章", "OEBPS/text/ch2.xhtml"),
            ),
            structure.toc,
        )
    }

    @Test
    fun `EPUB3 解析 NAV 目录`() {
        val structure = parse(TestEpubs.epub3())
        assertEquals("三版之书", structure.title)
        assertEquals("作者乙", structure.creator)
        assertEquals(
            listOf(
                EpubStructure.TocEntry("甲章", "OEBPS/text/ch1.xhtml"),
                EpubStructure.TocEntry("乙章", "OEBPS/text/ch2.xhtml"),
            ),
            structure.toc,
        )
    }

    @Test
    fun `无 NAV 无 NCX 时 toc 为 null`() {
        assertNull(parse(TestEpubs.noToc()).toc)
    }

    @Test
    fun `带 encryption_xml 判定为 DRM 抛错`() {
        assertThrows(DrmProtectedException::class.java) { parse(TestEpubs.drm()) }
    }

    @Test
    fun `encryption_xml 目标全是字体时仅字体混淆放行`() {
        val structure = parse(TestEpubs.fontObfuscation())
        assertEquals("字体混淆书", structure.title)
        assertEquals(listOf("OEBPS/text/ch1.xhtml"), structure.spine.map { it.file })
    }

    @Test
    fun `encryption_xml 含非字体目标时仍判定 DRM`() {
        assertThrows(DrmProtectedException::class.java) { parse(TestEpubs.fontObfuscationMixed()) }
    }

    @Test
    fun `encryption_xml 畸形时保守判定 DRM`() {
        assertThrows(DrmProtectedException::class.java) { parse(TestEpubs.malformedEncryption()) }
    }

    @Test
    fun `resolveHref 处理 fragment 相对路径与 URL 编码`() {
        assertEquals("OEBPS/text/ch1.xhtml", resolveHref("OEBPS", "text/ch1.xhtml#s1"))
        assertEquals("OPS/a/b.xhtml", resolveHref("OPS/x", "../a/./b.xhtml"))
        assertEquals("a b.xhtml", resolveHref("", "a%20b.xhtml"))
        assertEquals("root.xhtml", resolveHref("OEBPS", "/root.xhtml"))
    }

    @Test
    fun `全字段元数据 多 creator 带 role 与 file-as 加 calibre 丛书`() {
        val meta = parse(TestEpubs.fullMeta()).meta
        assertEquals("全字段之书", meta.title)
        assertEquals(
            listOf(
                EpubMeta.Creator("张三", "aut", "Zhang, San"),
                EpubMeta.Creator("李四", "trl", null),
                EpubMeta.Creator("王五", null, null),
            ),
            meta.creators,
        )
        assertEquals("zh-CN", meta.language)
        assertEquals("测试出版社", meta.publisher)
        assertEquals("2020-01-02", meta.date)
        assertEquals("这是一段简介。", meta.description)
        assertEquals(listOf("科幻", "短篇"), meta.subjects)
        assertEquals("isbn:9787020002207", meta.identifier)
        assertEquals("© 2020", meta.rights)
        assertEquals("银河纪元", meta.seriesName)
        assertEquals("3", meta.seriesIndex)
    }

    @Test
    fun `EPUB3 封面取 cover-image 属性`() {
        assertEquals("OEBPS/images/cover.jpg", parse(TestEpubs.fullMeta()).coverFile)
    }

    @Test
    fun `EPUB2 封面取 meta cover 指向的 manifest 项`() {
        assertEquals("OEBPS/images/cover.png", parse(TestEpubs.epub2MetaCover()).coverFile)
    }

    @Test
    fun `封面兜底 guide reference type cover`() {
        assertEquals("OEBPS/images/cover.png", parse(TestEpubs.guideCover()).coverFile)
    }

    @Test
    fun `无封面时 coverFile 为 null`() {
        assertNull(parse(TestEpubs.noToc()).coverFile)
        assertNull(parse(TestEpubs.epub2()).coverFile)
    }

    @Test
    fun `formatCreators 角色后缀映射`() {
        assertNull(formatCreators(emptyList()))
        assertEquals(
            "张三, 李四（译）, 王五（编）, 赵六（图）, 佚名（bkp）",
            formatCreators(
                listOf(
                    EpubMeta.Creator("张三", "aut", null),
                    EpubMeta.Creator("李四", "trl", null),
                    EpubMeta.Creator("王五", "edt", null),
                    EpubMeta.Creator("赵六", "ill", null),
                    EpubMeta.Creator("佚名", "bkp", null),
                ),
            ),
        )
        assertEquals("王五", formatCreators(listOf(EpubMeta.Creator("王五", null, null))))
    }
}

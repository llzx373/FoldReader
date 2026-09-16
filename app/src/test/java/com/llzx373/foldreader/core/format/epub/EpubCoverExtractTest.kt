package com.llzx373.foldreader.core.format.epub

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser
import org.xmlpull.v1.XmlPullParser

class EpubCoverExtractTest {

    private val newParser: () -> XmlPullParser = { KXmlParser() }

    private fun newParserFor(dir: File): EpubBookParser = EpubBookParser(
        convertedDir = dir,
        openFlattenedContent = { error("封面测试不压平") },
        openChannel = { error("封面测试只走 File 内部方法") },
        displayNameOf = { null },
        newParser = newParser,
    )

    private fun tempDir(): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "epub-cover-${System.nanoTime()}")
        dir.mkdirs()
        return dir
    }

    private fun tempEpub(entries: Map<String, ByteArray>): File {
        val file = File.createTempFile("epub-cover", ".epub")
        file.deleteOnExit()
        TestEpubs.writeRaw(file, entries)
        return file
    }

    @Test
    fun `EPUB2 meta 封面提取 PNG 字节与扩展名`() {
        val epub = tempEpub(
            TestEpubs.epub2MetaCover().mapValues { it.value.toByteArray(Charsets.UTF_8) } +
                ("OEBPS/images/cover.png" to TestEpubs.PNG_BYTES),
        )
        val cover = newParserFor(tempDir()).extractCoverFile(epub)
        assertEquals("png", cover?.extension)
        assertTrue(TestEpubs.PNG_BYTES.contentEquals(cover!!.bytes))
    }

    @Test
    fun `扩展名错误时按魔数判定`() {
        // fullMeta 封面条目名为 images/cover.jpg，内容换成 PNG 魔数 → 以魔数为准
        val epub = tempEpub(
            TestEpubs.fullMeta().mapValues { it.value.toByteArray(Charsets.UTF_8) } +
                ("OEBPS/images/cover.jpg" to TestEpubs.PNG_BYTES),
        )
        val cover = newParserFor(tempDir()).extractCoverFile(epub)
        assertEquals("png", cover?.extension)
    }

    @Test
    fun `封面条目非图片时跳过`() {
        // guide 指向 xhtml 包装页：内容不是图片魔数、扩展名也不是图片
        val entries = linkedMapOf(
            "META-INF/container.xml" to """
<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>""".trimIndent(),
            "OEBPS/content.opf" to """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="id">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:title>包装页封面书</dc:title>
    <meta name="cover" content="cov"/>
  </metadata>
  <manifest>
    <item id="ch1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>
    <item id="cov" href="cover.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine><itemref idref="ch1"/></spine>
</package>""",
            "OEBPS/cover.xhtml" to """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml"><body><p>封面页</p></body></html>""",
            "OEBPS/text/ch1.xhtml" to """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml"><body><p>正文</p></body></html>""",
        )
        val epub = tempEpub(entries.mapValues { it.value.toByteArray(Charsets.UTF_8) })
        assertNull(newParserFor(tempDir()).extractCoverFile(epub))
    }

    @Test
    fun `无封面返回 null`() {
        val epub = tempEpub(TestEpubs.noToc().mapValues { it.value.toByteArray(Charsets.UTF_8) })
        assertNull(newParserFor(tempDir()).extractCoverFile(epub))
    }

    @Test
    fun `sniffImageExtension 魔数优先扩展名兜底`() {
        assertEquals("jpg", sniffImageExtension("a.png", TestEpubs.JPEG_BYTES))
        assertEquals("png", sniffImageExtension("a.jpg", TestEpubs.PNG_BYTES))
        assertEquals("webp", sniffImageExtension(
            "a.bin",
            byteArrayOf('R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte(),
                0, 0, 0, 0, 'W'.code.toByte(), 'E'.code.toByte(), 'B'.code.toByte(), 'P'.code.toByte()),
        ))
        assertEquals("jpeg", sniffImageExtension("pic.jpeg", byteArrayOf(1, 2, 3)))
        assertNull(sniffImageExtension("page.xhtml", "<html>".toByteArray()))
        assertNull(sniffImageExtension("noext", byteArrayOf(0, 1, 2)))
    }
}

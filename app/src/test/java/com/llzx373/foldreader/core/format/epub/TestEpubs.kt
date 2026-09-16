package com.llzx373.foldreader.core.format.epub

import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 测试用最小 EPUB 构造：mimetype 为第一个条目且 stored（与真实 EPUB / FormatDetector 判定一致）。 */
internal object TestEpubs {

    const val CH1_TEXT = "第一章\n\n正文一。"
    const val CH2_TEXT = "正文二。"
    const val FULL_TEXT = "$CH1_TEXT\n\n$CH2_TEXT"

    fun write(file: File, entries: LinkedHashMap<String, String>) {
        writeRaw(file, entries.mapValues { it.value.toByteArray(Charsets.UTF_8) })
    }

    /** 二进制版：封面图片等字节条目用。 */
    fun writeRaw(file: File, entries: Map<String, ByteArray>) {
        ZipOutputStream(file.outputStream().buffered()).use { zos ->
            val mime = "application/epub+zip".toByteArray(Charsets.US_ASCII)
            val mimeEntry = ZipEntry("mimetype").apply {
                method = ZipEntry.STORED
                size = mime.size.toLong()
                compressedSize = mime.size.toLong()
                crc = CRC32().also { it.update(mime) }.value
            }
            zos.putNextEntry(mimeEntry)
            zos.write(mime)
            zos.closeEntry()
            for ((name, content) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(content)
                zos.closeEntry()
            }
        }
    }

    private const val CONTAINER = """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>"""

    private const val CH1_XHTML = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml"><head><title>ch1</title><style>p{}</style></head>
<body><h1>第一章</h1><p>正文一。</p></body></html>"""

    private const val CH2_XHTML = """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml"><head><title>ch2</title><script>var x=1;</script></head>
<body><p>正文二。</p></body></html>"""

    /** 带锚点元素的 ch1：块级 id="c1"、行内 <a name="mid"/>、嵌套块级 <div id="blk">。 */
    internal const val ANCHORED_CH1_XHTML = """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml"><body>
<h1 id="c1">甲章标题</h1>
<p>甲章正文<a name="mid"/>续文。</p>
<div id="blk"><p>嵌套块。</p></div>
</body></html>"""

    /** ANCHORED_CH1 压平结果（与压平规范 v1 对齐，测试据此断言锚点偏移）。 */
    const val ANCHORED_CH1_TEXT = "甲章标题\n\n甲章正文续文。\n\n嵌套块。"

    /** 带 fragment TOC + 锚点元素的书：缺失锚回退文件起点（同偏移去重保留先出现标题）。 */
    fun anchored(): LinkedHashMap<String, String> = linkedMapOf(
        "META-INF/container.xml" to CONTAINER,
        "OEBPS/content.opf" to """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:title>锚点之书</dc:title>
  </metadata>
  <manifest>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
    <item id="ch1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>
    <item id="ch2" href="text/ch2.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine>
    <itemref idref="ch1"/>
    <itemref idref="ch2"/>
  </spine>
</package>""",
        "OEBPS/nav.xhtml" to """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
<body><nav epub:type="toc"><ol>
<li><a href="text/ch1.xhtml">第一章</a></li>
<li><a href="text/ch1.xhtml#mid">续节</a></li>
<li><a href="text/ch1.xhtml#missing">缺失锚</a></li>
<li><a href="text/ch2.xhtml">第二章</a></li>
</ol></nav></body></html>""",
        "OEBPS/text/ch1.xhtml" to ANCHORED_CH1_XHTML,
        "OEBPS/text/ch2.xhtml" to CH2_XHTML,
    )

    /** EPUB3 landmarks：bodymatter 指向 ch1#mid。 */
    fun landmarks(): LinkedHashMap<String, String> = anchored().also {
        it["OEBPS/nav.xhtml"] = """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
<body>
<nav epub:type="toc"><ol><li><a href="text/ch1.xhtml">第一章</a></li></ol></nav>
<nav epub:type="landmarks"><ol>
<li><a epub:type="cover" href="cover.xhtml">封面</a></li>
<li><a epub:type="bodymatter" href="text/ch1.xhtml#mid">正文开始</a></li>
</ol></nav>
</body></html>"""
    }

    /** EPUB2 guide type="text" 指向 ch2 全文起点（无 landmarks）。 */
    fun guideText(): LinkedHashMap<String, String> = linkedMapOf(
        "META-INF/container.xml" to CONTAINER,
        "OEBPS/content.opf" to """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="id">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:title>guide 起点书</dc:title>
  </metadata>
  <manifest>
    <item id="ch1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>
    <item id="ch2" href="text/ch2.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine>
    <itemref idref="ch1"/>
    <itemref idref="ch2"/>
  </spine>
  <guide>
    <reference type="cover" href="cover.xhtml" title="封面"/>
    <reference type="text" href="text/ch2.xhtml" title="正文"/>
  </guide>
</package>""",
        "OEBPS/text/ch1.xhtml" to CH1_XHTML,
        "OEBPS/text/ch2.xhtml" to CH2_XHTML,
    )

    /** EPUB3 page-list：页 1→ch1 文件级，页 2→ch1#mid 锚点，页 3→ch2。 */
    fun pageList(): LinkedHashMap<String, String> = anchored().also {
        it["OEBPS/nav.xhtml"] = """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
<body>
<nav epub:type="toc"><ol><li><a href="text/ch1.xhtml">第一章</a></li></ol></nav>
<nav epub:type="page-list"><ol>
<li><a href="text/ch1.xhtml">1</a></li>
<li><a href="text/ch1.xhtml#mid">2</a></li>
<li><a href="text/ch2.xhtml">3</a></li>
</ol></nav>
</body></html>"""
    }

    /** EPUB2 NCX pageList。 */
    fun ncxPageList(): LinkedHashMap<String, String> = linkedMapOf(
        "META-INF/container.xml" to CONTAINER,
        "OEBPS/content.opf" to """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="id">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:title>NCX 页码书</dc:title>
  </metadata>
  <manifest>
    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
    <item id="ch1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>
    <item id="ch2" href="text/ch2.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine toc="ncx">
    <itemref idref="ch1"/>
    <itemref idref="ch2"/>
  </spine>
</package>""",
        "OEBPS/toc.ncx" to """<?xml version="1.0" encoding="UTF-8"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/">
  <navMap>
    <navPoint id="n1" playOrder="1">
      <navLabel><text>第一章</text></navLabel>
      <content src="text/ch1.xhtml"/>
    </navPoint>
  </navMap>
  <pageList>
    <pageTarget id="p1">
      <navLabel><text>10</text></navLabel>
      <content src="text/ch1.xhtml#mid"/>
    </pageTarget>
    <pageTarget id="p2">
      <navLabel><text>11</text></navLabel>
      <content src="text/ch2.xhtml"/>
    </pageTarget>
  </pageList>
</ncx>""",
        "OEBPS/text/ch1.xhtml" to ANCHORED_CH1_XHTML,
        "OEBPS/text/ch2.xhtml" to CH2_XHTML,
    )

    /** linear="no" 的 notes 不参与正文；TOC 指向它的条目应被丢弃。 */
    fun linearNo(): LinkedHashMap<String, String> = linkedMapOf(
        "META-INF/container.xml" to CONTAINER,
        "OEBPS/content.opf" to """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:title>非线性书</dc:title>
  </metadata>
  <manifest>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
    <item id="ch1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>
    <item id="notes" href="text/notes.xhtml" media-type="application/xhtml+xml"/>
    <item id="ch2" href="text/ch2.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine>
    <itemref idref="ch1"/>
    <itemref idref="notes" linear="no"/>
    <itemref idref="ch2"/>
  </spine>
</package>""",
        "OEBPS/nav.xhtml" to """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
<body><nav epub:type="toc"><ol>
<li><a href="text/ch1.xhtml">第一章</a></li>
<li><a href="text/notes.xhtml">注释</a></li>
<li><a href="text/ch2.xhtml">第二章</a></li>
</ol></nav></body></html>""",
        "OEBPS/text/ch1.xhtml" to CH1_XHTML,
        "OEBPS/text/notes.xhtml" to """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml"><body><p>注释内容不应出现。</p></body></html>""",
        "OEBPS/text/ch2.xhtml" to CH2_XHTML,
    )

    /** 全部 spine 项 linear="no" 的畸形书：回退全部压平。 */
    fun allLinearNo(): LinkedHashMap<String, String> = noToc().also {
        it["OEBPS/content.opf"] = """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:title>全非线性书</dc:title>
  </metadata>
  <manifest>
    <item id="ch1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>
    <item id="ch2" href="text/ch2.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine>
    <itemref idref="ch1" linear="no"/>
    <itemref idref="ch2" linear="no"/>
  </spine>
</package>"""
    }

    /** EPUB2：NCX 目录（含嵌套 navPoint 与 #fragment）。 */
    fun epub2(): LinkedHashMap<String, String> = linkedMapOf(
        "META-INF/container.xml" to CONTAINER,
        "OEBPS/content.opf" to """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="id">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:title>测试之书</dc:title>
    <dc:creator>作者甲</dc:creator>
  </metadata>
  <manifest>
    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
    <item id="ch1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>
    <item id="ch2" href="text/ch2.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine toc="ncx">
    <itemref idref="ch1"/>
    <itemref idref="ch2"/>
  </spine>
</package>""",
        "OEBPS/toc.ncx" to """<?xml version="1.0" encoding="UTF-8"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/">
  <navMap>
    <navPoint id="n1" playOrder="1">
      <navLabel><text>第一章</text></navLabel>
      <content src="text/ch1.xhtml"/>
      <navPoint id="n1a" playOrder="2">
        <navLabel><text>第一章 第一节</text></navLabel>
        <content src="text/ch1.xhtml#s1"/>
      </navPoint>
    </navPoint>
    <navPoint id="n2" playOrder="3">
      <navLabel><text>第二章</text></navLabel>
      <content src="text/ch2.xhtml"/>
    </navPoint>
  </navMap>
</ncx>""",
        "OEBPS/text/ch1.xhtml" to CH1_XHTML,
        "OEBPS/text/ch2.xhtml" to CH2_XHTML,
    )

    /** EPUB3：NAV 目录（嵌套 ol）。 */
    fun epub3(): LinkedHashMap<String, String> = linkedMapOf(
        "META-INF/container.xml" to CONTAINER,
        "OEBPS/content.opf" to """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:title>三版之书</dc:title>
    <dc:creator>作者乙</dc:creator>
  </metadata>
  <manifest>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
    <item id="ch1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>
    <item id="ch2" href="text/ch2.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine>
    <itemref idref="ch1"/>
    <itemref idref="ch2"/>
  </spine>
</package>""",
        "OEBPS/nav.xhtml" to """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
<body><nav epub:type="toc"><ol>
<li><a href="text/ch1.xhtml">甲章</a><ol><li><a href="text/ch2.xhtml">乙章</a></li></ol></li>
</ol></nav></body></html>""",
        "OEBPS/text/ch1.xhtml" to CH1_XHTML,
        "OEBPS/text/ch2.xhtml" to CH2_XHTML,
    )

    /** 无 TOC（无 NAV、无 NCX）：上层退化为按 spine 项分章。 */
    fun noToc(): LinkedHashMap<String, String> = linkedMapOf(
        "META-INF/container.xml" to CONTAINER,
        "OEBPS/content.opf" to """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:title>无目录书</dc:title>
  </metadata>
  <manifest>
    <item id="ch1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>
    <item id="ch2" href="text/ch2.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine>
    <itemref idref="ch1"/>
    <itemref idref="ch2"/>
  </spine>
</package>""",
        "OEBPS/text/ch1.xhtml" to CH1_XHTML,
        "OEBPS/text/ch2.xhtml" to CH2_XHTML,
    )

    /** 带 encryption.xml 的 DRM 书。 */
    fun drm(): LinkedHashMap<String, String> = epub2().also {
        it["META-INF/encryption.xml"] = """<?xml version="1.0" encoding="UTF-8"?>
<encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container"/>"""
    }

    /** 仅字体混淆：encryption.xml 目标全是字体（media-type 与扩展名各一）→ 放行。 */
    fun fontObfuscation(): LinkedHashMap<String, String> = linkedMapOf(
        "META-INF/container.xml" to CONTAINER,
        "OEBPS/content.opf" to """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:title>字体混淆书</dc:title>
  </metadata>
  <manifest>
    <item id="ch1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>
    <item id="f1" href="fonts/f1.otf" media-type="application/vnd.ms-opentype"/>
    <item id="f2" href="fonts/f2.woff" media-type="application/octet-stream"/>
  </manifest>
  <spine>
    <itemref idref="ch1"/>
  </spine>
</package>""",
        "META-INF/encryption.xml" to """<?xml version="1.0" encoding="UTF-8"?>
<encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container"
  xmlns:enc="http://www.w3.org/2001/04/xmlenc#">
  <enc:EncryptedData>
    <enc:CipherData><enc:CipherReference URI="OEBPS/fonts/f1.otf"/></enc:CipherData>
  </enc:EncryptedData>
  <enc:EncryptedData>
    <enc:CipherData><enc:CipherReference URI="OEBPS/fonts/f2.woff"/></enc:CipherData>
  </enc:EncryptedData>
</encryption>""",
        "OEBPS/text/ch1.xhtml" to CH1_XHTML,
        "OEBPS/fonts/f1.otf" to "fake-obfuscated-font-1",
        "OEBPS/fonts/f2.woff" to "fake-obfuscated-font-2",
    )

    /** 字体混淆 + 正文加密：任一目标不是字体 → DRM。 */
    fun fontObfuscationMixed(): LinkedHashMap<String, String> = fontObfuscation().also {
        it["META-INF/encryption.xml"] = it.getValue("META-INF/encryption.xml")
            .replace("OEBPS/fonts/f2.woff", "OEBPS/text/ch1.xhtml")
    }

    /** 畸形 encryption.xml：解析失败保守按 DRM。 */
    fun malformedEncryption(): LinkedHashMap<String, String> = fontObfuscation().also {
        it["META-INF/encryption.xml"] = "not xml <<<"
    }

    /** 最小合法 PNG 头 + 填充（仅供魔数嗅探，不可解码）。 */
    val PNG_BYTES = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        1, 2, 3, 4, 5, 6, 7, 8,
    )

    /**
     * 样式/链接/图片书（压平规范 v3）：内部链接（锚点命中/fragment 回退/断链丢弃）、
     * 外部链接、b/i 嵌套、img 占位块。含二进制图片条目，用 writeRaw 写入。
     *
     * 压平文本：ch1 = "前文内部链外部链断链后文\n\n粗体斜体叠加\n\n￼"，
     * ch2 = "目标节\n\n正文二。回退"（起点 25），全长 36。
     */
    fun styled(): Map<String, ByteArray> {
        val text = linkedMapOf(
            "META-INF/container.xml" to CONTAINER,
            "OEBPS/content.opf" to """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:title>样式之书</dc:title>
  </metadata>
  <manifest>
    <item id="ch1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>
    <item id="ch2" href="text/ch2.xhtml" media-type="application/xhtml+xml"/>
    <item id="pic" href="images/pic.png" media-type="image/png"/>
  </manifest>
  <spine>
    <itemref idref="ch1"/>
    <itemref idref="ch2"/>
  </spine>
</package>""",
            "OEBPS/text/ch1.xhtml" to """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml"><body>
<p>前文<a href="ch2.xhtml#target">内部链</a><a href="https://example.com">外部链</a><a href="nowhere.xhtml">断链</a>后文</p>
<p><b>粗体</b><i>斜体</i><b><i>叠加</i></b></p>
<img src="../images/pic.png" alt="插图"/>
</body></html>""",
            "OEBPS/text/ch2.xhtml" to """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml"><body><h2 id="target">目标节</h2><p>正文二。<a href="ch1.xhtml#nope">回退</a></p></body></html>""",
        )
        return text.mapValues { it.value.toByteArray(Charsets.UTF_8) } +
            ("OEBPS/images/pic.png" to PNG_BYTES)
    }

    val JPEG_BYTES = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3)

    /** EPUB3 全字段元数据 + 多 creator（role/file-as）+ calibre 丛书 + cover-image 封面。 */
    fun fullMeta(): LinkedHashMap<String, String> = linkedMapOf(
        "META-INF/container.xml" to CONTAINER,
        "OEBPS/content.opf" to """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" xmlns:opf="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:title>全字段之书</dc:title>
    <dc:creator opf:role="aut" opf:file-as="Zhang, San">张三</dc:creator>
    <dc:creator opf:role="trl">李四</dc:creator>
    <dc:creator>王五</dc:creator>
    <dc:language>zh-CN</dc:language>
    <dc:publisher>测试出版社</dc:publisher>
    <dc:date>2020-01-02</dc:date>
    <dc:description>这是一段简介。</dc:description>
    <dc:subject>科幻</dc:subject>
    <dc:subject>短篇</dc:subject>
    <dc:identifier>isbn:9787020002207</dc:identifier>
    <dc:rights>© 2020</dc:rights>
    <meta name="calibre:series" content="银河纪元"/>
    <meta name="calibre:series_index" content="3"/>
  </metadata>
  <manifest>
    <item id="ch1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>
    <item id="cov" href="images/cover.jpg" media-type="image/jpeg" properties="cover-image"/>
  </manifest>
  <spine>
    <itemref idref="ch1"/>
  </spine>
</package>""",
        "OEBPS/text/ch1.xhtml" to CH1_XHTML,
    )

    /** EPUB2：meta name="cover" 指向 manifest id。 */
    fun epub2MetaCover(): LinkedHashMap<String, String> = linkedMapOf(
        "META-INF/container.xml" to CONTAINER,
        "OEBPS/content.opf" to """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="id">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:title>二版封面书</dc:title>
    <dc:creator>作者甲</dc:creator>
    <meta name="cover" content="cov"/>
  </metadata>
  <manifest>
    <item id="ch1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>
    <item id="cov" href="images/cover.png" media-type="image/png"/>
  </manifest>
  <spine>
    <itemref idref="ch1"/>
  </spine>
</package>""",
        "OEBPS/text/ch1.xhtml" to CH1_XHTML,
    )

    /** 封面仅靠 guide reference type="cover" 兜底。 */
    fun guideCover(): LinkedHashMap<String, String> = linkedMapOf(
        "META-INF/container.xml" to CONTAINER,
        "OEBPS/content.opf" to """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="id">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:title>guide 封面书</dc:title>
  </metadata>
  <manifest>
    <item id="ch1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine>
    <itemref idref="ch1"/>
  </spine>
  <guide>
    <reference type="cover" href="images/cover.png" title="封面"/>
  </guide>
</package>""",
        "OEBPS/text/ch1.xhtml" to CH1_XHTML,
    )
}

package com.llzx373.foldreader.core.format.docx

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 测试用最小 DOCX 构造：首条目为 `[Content_Types].xml`（与 Word 产物 / FormatDetector 判定一致）。 */
internal object TestDocx {

    const val CH1_TITLE = "第一章"
    const val BODY1 = "正文一。"
    const val BOLD_TEXT = "加粗"
    const val CH2_TITLE = "第一节"
    const val BODY2 = "正文二。"

    /** minimal() 的压平结果（与压平规范对齐：块间一个空行，粗体不改变文本输出）。 */
    const val FULL_TEXT = "$CH1_TITLE\n\n$BODY1$BOLD_TEXT\n\n$CH2_TITLE\n\n$BODY2"

    const val META_TITLE = "测试文档"
    const val META_CREATOR = "作者甲"

    fun write(file: File, entries: LinkedHashMap<String, String>) {
        writeRaw(file, entries.mapValues { it.value.toByteArray(Charsets.UTF_8) })
    }

    /** 二进制版：图片/UTF-16 条目用。 */
    fun writeRaw(file: File, entries: Map<String, ByteArray>) {
        ZipOutputStream(file.outputStream().buffered()).use { zos ->
            for ((name, content) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(content)
                zos.closeEntry()
            }
        }
    }

    private val CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Default Extension="png" ContentType="image/png"/>
  <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
  <Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
  <Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>
</Types>"""

    private val ROOT_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
  <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/>
</Relationships>"""

    private val DOCUMENT_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>"""

    private val STYLES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
  <w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/></w:style>
  <w:style w:type="paragraph" w:styleId="Heading2"><w:name w:val="heading 2"/></w:style>
</w:styles>"""

    private val CORE_PROPS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties"
    xmlns:dc="http://purl.org/dc/elements/1.1/"
    xmlns:dcterms="http://purl.org/dc/terms/">
  <dc:title>$META_TITLE</dc:title>
  <dc:creator>$META_CREATOR</dc:creator>
  <dc:description>简介文本。</dc:description>
  <dc:language>zh-CN</dc:language>
  <dc:subject>科幻</dc:subject>
</cp:coreProperties>"""

    /** 带 Heading1/Heading2 样式段落、正文与加粗 run 的正文 XML。 */
    private val DOCUMENT = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
<w:body>
<w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>$CH1_TITLE</w:t></w:r></w:p>
<w:p><w:r><w:t>$BODY1</w:t></w:r><w:r><w:rPr><w:b/></w:rPr><w:t>$BOLD_TEXT</w:t></w:r></w:p>
<w:p><w:pPr><w:pStyle w:val="Heading2"/></w:pPr><w:r><w:t>$CH2_TITLE</w:t></w:r></w:p>
<w:p><w:r><w:t>$BODY2</w:t></w:r></w:p>
</w:body></w:document>"""

    /** 最小书：两级标题 + 加粗正文 + core.xml 元数据。 */
    fun minimal(): LinkedHashMap<String, String> = linkedMapOf(
        "[Content_Types].xml" to CONTENT_TYPES,
        "_rels/.rels" to ROOT_RELS,
        "docProps/core.xml" to CORE_PROPS,
        "word/_rels/document.xml.rels" to DOCUMENT_RELS,
        "word/document.xml" to DOCUMENT,
        "word/styles.xml" to STYLES,
    )

    /** 最小合法 PNG 头 + 填充（仅供魔数嗅探与字节落盘，不可解码——尺寸探测由测试注入假实现）。 */
    val PNG_BYTES = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        1, 2, 3, 4, 5, 6, 7, 8,
    )

    /** 带一张内嵌图片（drawingML blip 引用 media/image1.png）的书。 */
    fun withImage(): Map<String, ByteArray> {
        val document = DOCUMENT.replace(
            "</w:body>",
            """<w:p><w:r><w:drawing><wp:inline xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing">
<a:graphic xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">
<a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture">
<pic:pic xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture">
<pic:blipFill><a:blip xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" r:embed="rId5"/></pic:blipFill>
</pic:pic></a:graphicData></a:graphic>
</wp:inline></w:drawing></w:r></w:p></w:body>""",
        )
        val rels = DOCUMENT_RELS.replace(
            "</Relationships>",
            """  <Relationship Id="rId5" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="media/image1.png"/>
</Relationships>""",
        )
        return minimal()
            .mapValues { it.value.toByteArray(Charsets.UTF_8) }
            .toMutableMap()
            .apply {
                set("word/document.xml", document.toByteArray(Charsets.UTF_8))
                set("word/_rels/document.xml.rels", rels.toByteArray(Charsets.UTF_8))
                set("word/media/image1.png", PNG_BYTES)
            }
    }

    /** word/document.xml 含 DOCTYPE（实体注入）的恶意书。 */
    fun withDoctype(): Map<String, ByteArray> =
        minimal()
            .mapValues { it.value.toByteArray(Charsets.UTF_8) }
            .toMutableMap()
            .apply {
                set(
                    "word/document.xml",
                    """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE w:document [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
$DOCUMENT""".toByteArray(Charsets.UTF_8),
                )
            }

    /** UTF-16LE 编码的 .xml 条目里藏 DOCTYPE：扫描必须命中 UTF-16 形态的标记。 */
    fun withUtf16Doctype(): Map<String, ByteArray> =
        minimal()
            .mapValues { it.value.toByteArray(Charsets.UTF_8) }
            .toMutableMap()
            .apply {
                val utf16 = """<?xml version="1.0" encoding="UTF-16"?>
<!DOCTYPE w:document [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
<w:document/>""".toByteArray(Charsets.UTF_16LE)
                set("word/document.xml", utf16)
            }
}

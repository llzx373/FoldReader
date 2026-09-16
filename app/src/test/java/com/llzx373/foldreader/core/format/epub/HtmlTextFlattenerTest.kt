package com.llzx373.foldreader.core.format.epub

import java.io.StringWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser

class HtmlTextFlattenerTest {

    private fun flatten(xhtml: String): String = flattenWithSpans(xhtml).first

    private fun flattenWithSpans(
        xhtml: String,
        currentFile: String = "text/ch1.xhtml",
        onImage: ((String) -> IntArray?)? = null,
    ): Pair<String, List<com.llzx373.foldreader.core.format.TextSpan>> {
        val writer = StringWriter()
        val sink = FlattenSink(writer)
        HtmlTextFlattener { KXmlParser() }.flatten(
            xhtml.byteInputStream(Charsets.UTF_8), sink,
            currentFile = currentFile, onImage = onImage,
        )
        return writer.toString() to sink.recordedSpans()
    }

    private fun body(inner: String) =
        """<html xmlns="http://www.w3.org/1999/xhtml"><body>$inner</body></html>"""

    @Test
    fun `段落之间保留一个空行`() {
        assertEquals("a\n\nb", flatten(body("<p>a</p><p>b</p>")))
    }

    @Test
    fun `嵌套块与行内标签剥离仅留文本`() {
        assertEquals(
            "Hello world!",
            flatten(body("<div><p>Hello <b>world</b>!</p></div>")),
        )
    }

    @Test
    fun `标题与段落都是块边界`() {
        assertEquals("T\n\nx", flatten(body("<h1>T</h1><p>x</p>")))
    }

    @Test
    fun `ul 列表项带全角项目符号`() {
        assertEquals("● one\n\n● two", flatten(body("<ul><li>one</li><li>two</li></ul>")))
    }

    @Test
    fun `ol 列表项带序号且认 start 属性`() {
        assertEquals("1. 甲\n\n2. 乙", flatten(body("<ol><li>甲</li><li>乙</li></ol>")))
        assertEquals("3. 甲\n\n4. 乙", flatten(body("<ol start=\"3\"><li>甲</li><li>乙</li></ol>")))
    }

    @Test
    fun `嵌套列表缩进两个全角空格且 ol 计数恢复`() {
        assertEquals(
            "● 甲\n\n　　● 子\n\n● 乙",
            flatten(body("<ul><li>甲<ul><li>子</li></ul></li><li>乙</li></ul>")),
        )
        assertEquals(
            "1. 甲\n\n　　1. 子1\n\n　　2. 子2\n\n2. 乙",
            flatten(body("<ol><li>甲<ol><li>子1</li><li>子2</li></ol></li><li>乙</li></ol>")),
        )
    }

    @Test
    fun `li 内嵌套块级元素不在序号后产生额外空行`() {
        assertEquals("● 甲", flatten(body("<ul><li><p>甲</p></li></ul>")))
        assertEquals("1. 甲\n\n段二", flatten(body("<ol><li><p>甲</p><p>段二</p></li></ol>")))
    }

    @Test
    fun `孤立 li 退化为项目符号块`() {
        assertEquals("● 单", flatten(body("<li>单</li>")))
    }

    @Test
    fun `ruby 底文后跟全角括号注音`() {
        assertEquals("漢（かん）", flatten(body("<p><ruby><rb>漢</rb><rt>かん</rt></ruby></p>")))
        // 无 rb 的简写形式：文本节点即底文
        assertEquals("漢（かん）", flatten(body("<p><ruby>漢<rt>かん</rt></ruby></p>")))
        assertEquals("a漢（x）b", flatten(body("<p>a<ruby><rb>漢</rb><rt>x</rt></ruby>b</p>")))
    }

    @Test
    fun `ruby 中 rp 与 rtc 内容跳过`() {
        assertEquals(
            "漢（かん）",
            flatten(body("<p><ruby><rb>漢</rb><rp>(</rp><rt>かん</rt><rp>)</rp></ruby></p>")),
        )
        assertEquals(
            "漢（かん）",
            flatten(body("<p><ruby><rb>漢</rb><rt>かん</rt><rtc><rt>语义注音</rt></rtc></ruby></p>")),
        )
    }

    @Test
    fun `ruby 多个 rt 以空格分隔拼接`() {
        assertEquals(
            "漢（かん カン）",
            flatten(body("<p><ruby><rb>漢</rb><rt>かん</rt><rt>カン</rt></ruby></p>")),
        )
    }

    @Test
    fun `ruby 无 rt 退化为纯底文 嵌套 ruby 防御不崩`() {
        assertEquals("漢", flatten(body("<p><ruby><rb>漢</rb></ruby></p>")))
        assertEquals(
            "漢字（じ）（かん）",
            flatten(body("<p><ruby><rb>漢<ruby><rb>字</rb><rt>じ</rt></ruby></rb><rt>かん</rt></ruby></p>")),
        )
    }

    @Test
    fun `表格同行单元格以全角分隔符连接 行间为空行`() {
        assertEquals(
            "a ｜ b\n\nc ｜ d",
            flatten(body("<table><tr><td>a</td><td>b</td></tr><tr><td>c</td><td>d</td></tr></table>")),
        )
        assertEquals(
            "h1 ｜ h2\n\na ｜ b",
            flatten(body("<table><tr><th>h1</th><th>h2</th></tr><tr><td>a</td><td>b</td></tr></table>")),
        )
    }

    @Test
    fun `表格单元格文本各自 trim 且行首不留悬挂分隔符`() {
        assertEquals(
            "a ｜ b",
            flatten(body("<table><tbody><tr><td> a </td> <td> b </td></tr></tbody></table>")),
        )
        assertEquals("a", flatten(body("<table><tr><td>a</td></tr></table>")))
    }

    @Test
    fun `caption 作为块级标题段落`() {
        assertEquals(
            "表题\n\na",
            flatten(body("<table><caption>表题</caption><tr><td>a</td></tr></table>")),
        )
    }

    @Test
    fun `嵌套表格防御性降级继续输出文本`() {
        assertEquals(
            "x\n\ny\n\nz",
            flatten(body("<table><tr><td>x<table><tr><td>y</td></tr></table></td><td>z</td></tr></table>")),
        )
    }


    @Test
    fun `br 产生单个换行而非空行`() {
        assertEquals("a\nb\nc", flatten(body("<p>a<br/>b<br/>c</p>")))
    }

    @Test
    fun `script style head 内容整体跳过`() {
        assertEquals(
            "x",
            flatten(
                "<html><head><style>body{color:red}</style></head>" +
                    "<body><script>var a=1;</script><p>x</p></body></html>",
            ),
        )
    }

    @Test
    fun `实体由解析器处理 NBSP 原样保留`() {
        assertEquals("a & b c", flatten(body("<p>a &amp; b&nbsp;c</p>")))
        assertEquals("d e", flatten(body("<p>d&#160;e</p>")))
    }

    @Test
    fun `DOCTYPE 不影响压平`() {
        assertEquals("x", flatten("<!DOCTYPE html>\n" + body("<p>x</p>")))
    }

    @Test
    fun `连续空块折叠为最多一个空行`() {
        assertEquals("a\n\nb", flatten(body("<p>a</p><div><div></div></div><p></p><p>b</p>")))
    }

    @Test
    fun `块两端空白被 trim 且开头结尾不留换行`() {
        assertEquals("spaced", flatten("  ${body("<p>  spaced  </p>")}  \n"))
    }

    @Test
    fun `空文档输出空串`() {
        assertEquals("", flatten(body("")))
        assertEquals("", flatten(body("<p>   </p><div></div>")))
    }

    // ---- 压平规范 v3：样式/链接/图片 span ----

    @Test
    fun `粗斜上下标 span 区间与压平文本对齐 嵌套叠加`() {
        val (text, spans) = flattenWithSpans(body("<p>a<b>粗<i>叠</i>体</b>c<sup>2</sup>d<sub>n</sub></p>"))
        assertEquals("a粗叠体c2dn", text)
        fun rangeOf(type: com.llzx373.foldreader.core.format.TextSpanType) =
            spans.filter { it.type == type }.map { text.substring(it.start.toInt(), it.end.toInt()) }
        assertEquals(listOf("粗叠体"), rangeOf(com.llzx373.foldreader.core.format.TextSpanType.BOLD))
        assertEquals(listOf("叠"), rangeOf(com.llzx373.foldreader.core.format.TextSpanType.ITALIC))
        assertEquals(listOf("2"), rangeOf(com.llzx373.foldreader.core.format.TextSpanType.SUP))
        assertEquals(listOf("n"), rangeOf(com.llzx373.foldreader.core.format.TextSpanType.SUB))
    }

    @Test
    fun `相邻同类型 span 合并 strong em 等价 b i`() {
        val (text, spans) = flattenWithSpans(body("<p><strong>甲</strong><b>乙</b><em>丙</em></p>"))
        assertEquals("甲乙丙", text)
        val bold = spans.single { it.type == com.llzx373.foldreader.core.format.TextSpanType.BOLD }
        assertEquals("甲乙", text.substring(bold.start.toInt(), bold.end.toInt()))
        val italic = spans.single { it.type == com.llzx373.foldreader.core.format.TextSpanType.ITALIC }
        assertEquals("丙", text.substring(italic.start.toInt(), italic.end.toInt()))
    }

    @Test
    fun `链接 span 压平期记 zip 级目标 noteref 识别 外部 URL 原样 其他 scheme 不记`() {
        val (text, spans) = flattenWithSpans(
            body(
                "<p><a href=\"ch2.xhtml#s1\">内</a>" +
                    "<a epub:type=\"noteref\" href=\"notes.xhtml#n1\">注</a>" +
                    "<a href=\"https://example.com/x\">外</a>" +
                    "<a href=\"mailto:a@b.c\">邮</a>" +
                    "<a href=\"#frag\">本页</a></p>",
            ),
            currentFile = "text/ch1.xhtml",
        )
        assertEquals("内注外邮本页", text)
        val link = spans.single { it.type == com.llzx373.foldreader.core.format.TextSpanType.LINK && it.start == 0L }
        assertEquals("text/ch2.xhtml#s1", link.payload)
        val noteref = spans.single { it.type == com.llzx373.foldreader.core.format.TextSpanType.NOTEREF }
        assertEquals("text/notes.xhtml#n1", noteref.payload)
        assertEquals("注", text.substring(noteref.start.toInt(), noteref.end.toInt()))
        val external = spans.single { it.payload == "https://example.com/x" }
        assertEquals("外", text.substring(external.start.toInt(), external.end.toInt()))
        // mailto 不记 span；本页 fragment 解析到当前文件
        assertEquals(4, spans.count { it.type == com.llzx373.foldreader.core.format.TextSpanType.LINK || it.type == com.llzx373.foldreader.core.format.TextSpanType.NOTEREF })
        val local = spans.last { it.type == com.llzx373.foldreader.core.format.TextSpanType.LINK }
        assertEquals("text/ch1.xhtml#frag", local.payload)
    }

    @Test
    fun `img 产生独立占位块与 IMAGE span 无 src 或回调否决时跳过`() {
        val (text, spans) = flattenWithSpans(
            body("<p>前</p><img src=\"pic.png\" alt=\"插图\"/><p>后</p>"),
            onImage = { path -> if (path == "text/pic.png") intArrayOf(100, 50) else null },
        )
        assertEquals("前\n\n￼\n\n后", text)
        val img = spans.single { it.type == com.llzx373.foldreader.core.format.TextSpanType.IMAGE }
        assertEquals("￼", text.substring(img.start.toInt(), img.end.toInt()))
        assertEquals("text/pic.png", img.payload)
        assertEquals("插图", img.alt)
        assertEquals(100, img.width)
        assertEquals(50, img.height)

        // 无 src / 外部 URL / 回调否决：连占位符都不留
        assertEquals(
            "前\n\n后",
            flatten(body("<p>前</p><img/><p>后</p>")),
        )
        assertEquals(
            "前\n\n后",
            flatten(body("<p>前</p><img src=\"https://e.com/x.png\"/><p>后</p>")),
        )
        val (t2, s2) = flattenWithSpans(
            body("<p>前</p><img src=\"missing.png\"/><p>后</p>"),
            onImage = { null },
        )
        assertEquals("前\n\n后", t2)
        assertTrue(s2.none { it.type == com.llzx373.foldreader.core.format.TextSpanType.IMAGE })
    }

    @Test
    fun `figure 与 figcaption 均为块级`() {
        val (text, spans) = flattenWithSpans(
            body("<figure><img src=\"pic.png\"/><figcaption>说明</figcaption></figure><p>后</p>"),
            onImage = { intArrayOf(10, 10) },
        )
        assertEquals("￼\n\n说明\n\n后", text)
        assertEquals(1, spans.count { it.type == com.llzx373.foldreader.core.format.TextSpanType.IMAGE })
    }
}

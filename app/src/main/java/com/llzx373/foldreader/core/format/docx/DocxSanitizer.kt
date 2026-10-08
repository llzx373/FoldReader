package com.llzx373.foldreader.core.format.docx

import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * DOCX 转换产物的消毒（规则移植自 Fossify Documents 的 HtmlDocumentSanitizer）：
 * - 删 `script, iframe, frame, object, embed, applet, base, link, meta`；`form` unwrap；删表单控件；
 * - 删所有 `on*` 与 `srcdoc` 属性；
 * - `a[href]` 白名单：`#` 锚点、http/https/mailto，其余剥掉 href；
 * - `img[src]` 只保留 `data:image/` 与 mammoth 图片转换器注入的 [DocxBookParser.IMAGE_PATH_PREFIX] 路径。
 *
 * 消毒同时给每个非空 h1-h6 注入唯一锚点 id（`__docx-h-N`），压平期经 flattener 的
 * onAnchor 记录偏移，目录章节边界即由此而来。
 *
 * 输出按 XML 语法序列化：下游 [com.llzx373.foldreader.core.format.epub.HtmlTextFlattener]
 * 走 XmlPullParser，要求输入良构（空元素自闭合、实体只留预定义与数字引用）。
 */
internal object DocxSanitizer {

    data class Heading(val id: String, val level: Int, val text: String)

    data class Result(val html: String, val headings: List<Heading>)

    fun sanitize(html: String): Result {
        val document = Jsoup.parseBodyFragment(html)

        document.select("script, iframe, frame, object, embed, applet, base, link, meta").remove()
        document.select("form").unwrap()
        document.select("input, button, textarea, select, option").remove()

        for (element in document.allElements) {
            element.attributes().asList()
                .filter { attribute ->
                    attribute.key.startsWith("on", ignoreCase = true) ||
                        attribute.key.equals("srcdoc", ignoreCase = true)
                }
                .forEach { attribute -> element.removeAttr(attribute.key) }
        }

        document.select("a[href]").forEach { link ->
            if (!isAllowedLink(link.attr("href").trim())) link.removeAttr("href")
        }
        document.select("img[src]").forEach { image ->
            val src = image.attr("src").trim()
            val allowed = src.startsWith("data:image/", ignoreCase = true) ||
                src.startsWith(DocxBookParser.IMAGE_PATH_PREFIX)
            if (!allowed) image.removeAttr("src")
            image.removeAttr("srcset")
        }

        val headings = ArrayList<Heading>()
        document.select("h1, h2, h3, h4, h5, h6").forEach { element ->
            val text = element.text().trim()
            if (text.isEmpty()) return@forEach
            val id = "$HEADING_ID_PREFIX${headings.size}"
            element.attr("id", id)
            headings += Heading(id, level = element.tagName().substring(1).toInt(), text = text)
        }

        document.outputSettings()
            .prettyPrint(false)
            .syntax(Document.OutputSettings.Syntax.xml)

        return Result(document.outerHtml(), headings)
    }

    private fun isAllowedLink(href: String): Boolean =
        href.startsWith('#') ||
            href.startsWith("http://", ignoreCase = true) ||
            href.startsWith("https://", ignoreCase = true) ||
            href.startsWith("mailto:", ignoreCase = true)

    const val HEADING_ID_PREFIX = "__docx-h-"
}

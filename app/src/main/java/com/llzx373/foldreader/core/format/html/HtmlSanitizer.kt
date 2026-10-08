package com.llzx373.foldreader.core.format.html

import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * HTML 内容消毒，DOCX（mammoth 转换产物）与 HTML 书共用
 * （规则移植自 Fossify Documents 的 HtmlDocumentSanitizer）：
 * - 删 `script, iframe, frame, object, embed, applet, base, link, meta`；`form` unwrap；删表单控件；
 * - 删所有 `on*` 与 `srcdoc` 属性；
 * - `a[href]` 白名单：`#` 锚点、http/https/mailto，其余剥掉 href；
 * - `img[src]` 只保留 `data:image/` 与 [imagePathPrefix] 前缀（DOCX 的 mammoth 图片落地路径；
 *   HTML 书传 null = 外链图全部剥掉，v1 不抓网络图）。
 *
 * 消毒同时给每个非空 h1-h6 注入唯一锚点 id（`__h-N`），压平期经 flattener 的
 * onAnchor 记录偏移，目录章节边界即由此而来。
 *
 * 输出按 XML 语法序列化：下游 [com.llzx373.foldreader.core.format.epub.HtmlTextFlattener]
 * 走 XmlPullParser，要求输入良构（空元素自闭合、实体只留预定义与数字引用）。
 *
 * @param isFragment true = body 片段（mammoth 产物）；false = 整页 HTML（保留 head 结构，
 *   flattener 会跳过 head——若把整页当片段解析，head 内容会被 jsoup 挪进 body 漏进正文）。
 */
internal object HtmlSanitizer {

    data class Heading(val id: String, val level: Int, val text: String)

    data class Result(val html: String, val headings: List<Heading>)

    fun sanitize(html: String, isFragment: Boolean, imagePathPrefix: String? = null): Result {
        val document = if (isFragment) Jsoup.parseBodyFragment(html) else Jsoup.parse(html)

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
                (imagePathPrefix != null && src.startsWith(imagePathPrefix))
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

    const val HEADING_ID_PREFIX = "__h-"
}

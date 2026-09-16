package com.llzx373.foldreader.core.format.fb2

import com.llzx373.foldreader.core.format.Chapter
import com.llzx373.foldreader.core.format.EntityNormalizingReader
import com.llzx373.foldreader.core.format.epub.FlattenSink
import com.llzx373.foldreader.core.format.newPullParser
import java.io.InputStream
import java.io.Reader
import java.nio.charset.Charset
import org.xmlpull.v1.XmlPullParser

internal class Fb2Meta(val title: String?, val author: String?)

/**
 * FB2（FictionBook 2.x，单 XML 文件）压平器，输出观感与压平规范 v1 一致
 * （段落 `\n`、连续空行折叠、首尾无多余换行——均由 [FlattenSink] 保证）。
 *
 * - 正文取第一个无名 `<body>`（`name="notes"`/`comments` 等命名 body 整体跳过）。
 * - `<section>` 递归；首个子 `<title>`（`<p>` 文本拼接）成为章节标题并记入章节（拍平为平铺）；
 *   无 title 的 section 不产生章节；全文无章节时上层退化单章。
 * - `<p>`/`<subtitle>`/`<epigraph>`/`<cite>`/`<poem>`/`<stanza>`/`<text-author>`/`<table>`/`<tr>`
 *   为块边界；`<v>`（诗行）单换行；`<empty-line>` 产生空行；`<binary>`/`<image>` 跳过；
 *   其余标签（emphasis/strong/a 等）剥离仅留文本。
 * - 源编码取 XML 声明（可能 windows-1251 等，见 [detectXmlEncoding]）；输出恒 UTF-8。
 */
internal class Fb2Flattener(
    private val newParser: () -> XmlPullParser,
) {

    private class SectionCtx(
        val startOffset: Long,
        var titleEligible: Boolean = true,
        var title: String? = null,
    )

    fun flatten(input: InputStream, sink: FlattenSink): List<Chapter> {
        val parser = newPullParser(newParser)
        parser.setInput(openXmlReader(input))
        var skipDepth = 0
        var inBody = false
        var bodyDepth = 0
        val sections = ArrayDeque<SectionCtx>()
        var titleCapture: StringBuilder? = null
        var titleDepth = 0
        val chapterPoints = ArrayList<Pair<Long, String>>()

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val name = parser.name.lowercase()
                    if (titleCapture != null) titleDepth++
                    when {
                        skipDepth > 0 -> skipDepth++
                        name == "binary" -> skipDepth = 1
                        !inBody -> if (name == "body") {
                            if (parser.getAttributeValue(null, "name").isNullOrBlank()) {
                                inBody = true
                            } else {
                                skipDepth = 1
                            }
                        }
                        name == "section" -> {
                            sections.lastOrNull()?.titleEligible = false
                            sink.blockBoundary()
                            sections.addLast(SectionCtx(sink.nextTextOffset))
                        }
                        name == "body" -> bodyDepth++
                        name == "title" -> {
                            sink.blockBoundary()
                            val top = sections.lastOrNull()
                            if (top != null && top.title == null && top.titleEligible) {
                                titleCapture = StringBuilder()
                                titleDepth = 1
                            }
                            top?.titleEligible = false
                        }
                        name == "empty-line" -> sink.blockBoundary()
                        name == "image" -> Unit
                        name == "v" -> sink.lineBreak()
                        name in BLOCK_TAGS -> sink.blockBoundary()
                        else -> Unit
                    }
                    // title 须为 section 的首个子元素：section 自身（分支内已标记外层）与 title 之外的
                    // 任何子标签先出现，则该 section 失去标题资格
                    if (titleCapture == null && name != "title" && name != "section" &&
                        inBody && skipDepth == 0
                    ) {
                        sections.lastOrNull()?.titleEligible = false
                    }
                }
                XmlPullParser.END_TAG -> {
                    val name = parser.name.lowercase()
                    when {
                        skipDepth > 0 -> skipDepth--
                        titleCapture != null -> {
                            titleDepth--
                            if (titleDepth == 0) {
                                val title = titleCapture.toString().trim()
                                titleCapture = null
                                val top = sections.lastOrNull()
                                if (title.isNotEmpty() && top != null && top.title == null) {
                                    top.title = title
                                }
                                sink.blockBoundary()
                            } else if (name in BLOCK_TAGS || name == "empty-line") {
                                // 标题内多个 <p> 拼接时空格分隔
                                titleCapture?.let { buf ->
                                    if (buf.isNotEmpty() && buf.last() != ' ') buf.append(' ')
                                }
                                sink.blockBoundary()
                            }
                        }
                        !inBody -> Unit
                        name == "body" -> if (bodyDepth > 0) bodyDepth-- else inBody = false
                        name == "section" -> {
                            sections.removeLastOrNull()?.let { ctx ->
                                ctx.title?.let { chapterPoints += ctx.startOffset to it }
                            }
                            sink.blockBoundary()
                        }
                        name == "empty-line" || name == "v" || name == "image" -> Unit
                        name in BLOCK_TAGS -> sink.blockBoundary()
                        else -> Unit
                    }
                }
                XmlPullParser.TEXT -> if (skipDepth == 0 && inBody) {
                    titleCapture?.append(parser.text)
                    sink.text(parser.text)
                }
            }
            event = parser.next()
        }
        sink.finish()
        return buildChapters(chapterPoints, sink.charCount)
    }

    private fun buildChapters(points: List<Pair<Long, String>>, totalChars: Long): List<Chapter> {
        // 同一起点（外层无标题 section 与内层同时开始）保留先出现（文档序更靠外）的标题
        val byStart = LinkedHashMap<Long, String>()
        for ((start, title) in points.sortedBy { it.first }) byStart.putIfAbsent(start, title)
        val starts = byStart.keys.toList()
        val chapters = starts.mapIndexed { index, start ->
            Chapter(
                title = byStart.getValue(start),
                charStart = start,
                charEnd = if (index + 1 < starts.size) starts[index + 1] else totalChars,
            )
        }.filter { it.charEnd > it.charStart }
        if (chapters.isNotEmpty()) return chapters
        return listOf(Chapter("正文", 0L, totalChars))
    }

    /** 元数据：`description/title-info` 的 `book-title` 与 `author`（first/middle/last-name 拼接）。 */
    fun readMeta(input: InputStream): Fb2Meta {
        val parser = newPullParser(newParser)
        parser.setInput(openXmlReader(input))
        var inDescription = false
        var inTitleInfo = false
        var inAuthor = false
        var capture: StringBuilder? = null
        var title: String? = null
        val authorParts = ArrayList<String>()
        val authors = ArrayList<String>()

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name.lowercase()) {
                    "description" -> inDescription = true
                    "title-info" -> if (inDescription) inTitleInfo = true
                    "author" -> if (inTitleInfo && !inAuthor) {
                        inAuthor = true
                        authorParts.clear()
                    }
                    "book-title" -> if (inTitleInfo && !inAuthor && title == null) {
                        capture = StringBuilder()
                    }
                    "first-name", "middle-name", "last-name", "nickname" ->
                        if (inTitleInfo && inAuthor) capture = StringBuilder()
                }
                XmlPullParser.TEXT -> capture?.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.name.lowercase()) {
                    "book-title" -> capture?.let {
                        title = it.toString().trim().takeIf { t -> t.isNotEmpty() }
                        capture = null
                    }
                    "first-name", "middle-name", "last-name", "nickname" -> capture?.let {
                        it.toString().trim().takeIf { p -> p.isNotEmpty() }?.let(authorParts::add)
                        capture = null
                    }
                    "author" -> if (inAuthor) {
                        authorParts.joinToString(" ").takeIf { it.isNotBlank() }?.let(authors::add)
                        inAuthor = false
                    }
                    "title-info" -> inTitleInfo = false
                    "description" -> return Fb2Meta(
                        title = title,
                        author = authors.joinToString(", ").takeIf { it.isNotBlank() },
                    )
                }
            }
            event = parser.next()
        }
        return Fb2Meta(
            title = title,
            author = authors.joinToString(", ").takeIf { it.isNotBlank() },
        )
    }

    private companion object {
        val BLOCK_TAGS = setOf(
            "p", "subtitle", "epigraph", "cite", "poem", "stanza", "text-author",
            "date", "table", "tr",
        )
    }
}

/** 按 XML 声明/BOM 打开解码 Reader；命名 HTML 实体经 [EntityNormalizingReader] 归一化。 */
internal fun openXmlReader(input: InputStream): Reader {
    val buffered = input.buffered(HEAD_BYTES)
    buffered.mark(HEAD_BYTES)
    val head = ByteArray(HEAD_BYTES)
    var read = 0
    while (read < head.size) {
        val n = buffered.read(head, read, head.size - read)
        if (n < 0) break
        read += n
    }
    buffered.reset()
    val (charset, bomLength) = detectXmlEncoding(head.copyOf(read))
    buffered.skip(bomLength.toLong())
    return EntityNormalizingReader(buffered.reader(charset))
}

/** 从头部字节判定 XML 编码：BOM 优先，其次 `<?xml … encoding="…"?>` 声明，默认 UTF-8。 */
internal fun detectXmlEncoding(head: ByteArray): Pair<Charset, Int> {
    if (head.size >= 3 && head[0] == 0xEF.toByte() && head[1] == 0xBB.toByte() &&
        head[2] == 0xBF.toByte()
    ) {
        return Charsets.UTF_8 to 3
    }
    if (head.size >= 2 && head[0] == 0xFF.toByte() && head[1] == 0xFE.toByte()) {
        return Charsets.UTF_16LE to 2
    }
    if (head.size >= 2 && head[0] == 0xFE.toByte() && head[1] == 0xFF.toByte()) {
        return Charsets.UTF_16BE to 2
    }
    val text = String(head, Charsets.ISO_8859_1)
    val match = Regex("""<\?xml[^?]*encoding\s*=\s*["']([^"']+)["']""").find(text)
    if (match != null) {
        runCatching { Charset.forName(match.groupValues[1]) }.getOrNull()?.let { return it to 0 }
    }
    return Charsets.UTF_8 to 0
}

private const val HEAD_BYTES = 8 * 1024

package com.llzx373.foldreader.core.format.epub

import android.util.Xml
import com.llzx373.foldreader.core.format.EntityNormalizingReader
import com.llzx373.foldreader.core.format.TextSpan
import com.llzx373.foldreader.core.format.TextSpanType
import com.llzx373.foldreader.core.format.newPullParser
import java.io.InputStream
import java.io.Writer
import org.xmlpull.v1.XmlPullParser

/**
 * 压平规范 v3 —— 本规则是进度/书签/标注字符偏移锚定的基础，禁止随意改动；
 * 确需变更必须递增 [com.llzx373.foldreader.core.format.ConvertedBookStore.FLATTEN_VERSION]
 * 使旧压平缓存（converted/<hash>.txt）失效重压平。
 *
 * v3 相对 v2 的变更：
 * - 样式 span：`b`/`strong`→BOLD、`i`/`em`→ITALIC、`sup`→SUP、`sub`→SUB，嵌套叠加；
 *   span 只记录区间（压平流绝对偏移），不改变文本输出，经 [FlattenSink.recordedSpans] 取出。
 * - 链接 span：`a[href]` → LINK（`epub:type="noteref"` → NOTEREF）；payload 压平期先记
 *   zip 内目标（路径 或 路径#fragment）或 http(s) URL，由 EpubBookParser 在全书压平后
 *   经锚点表解析为目标 charOffset；其他 scheme 不记。
 * - 内嵌图片：`img`/`image` 产生独立块（块边界 + 一个 U+FFFC 占位字符 + 块边界）并记
 *   IMAGE span（payload=zip 路径、alt、原始尺寸）；onImage 回调否决（无 src/非 zip 内图片
 *   条目/外部 URL）时连占位符都不留。`figure`/`figcaption` 新增为块级。
 *
 * v2 引入的规则（不变）：
 * - ruby 注音：底文（文本节点或 `<rb>`）照常输出，`<rt>` 注音收集后紧跟底文以全角括号输出
 *   `底文（注音）`（多个 rt 以空格分隔拼接）；`<rp>`/`<rtc>` 内容跳过；无 rt 退化为纯底文。
 * - 表格：`td`/`th` 为单元格边界，同一 `tr` 内单元格间以全角分隔符 ` ｜ ` 连接；
 *   `caption` 为块级标题段落；`thead`/`tbody`/`tfoot` 透明透传。
 * - 列表：`ol` 输出 `1. ` 序号前缀（认 `start`，嵌套重新计数、结束恢复外层），
 *   `ul` 输出 `● ` 项目符号，每层嵌套缩进两个全角空格。
 *
 * 自 v1 起不变的基础规则：
 * - 块级标签（[HtmlTextFlattener.BLOCK_TAGS]）开始/结束各产生一个段落边界；
 *   `br`/`hr` 仅在开始标签处产生一个边界。
 * - 相邻边界折叠：段落之间最多保留一个空行（即最多连续两个 `\n`）。
 * - `script`/`style`/`head` 标签内容整体跳过。
 * - 其余标签剥离仅留文本；输入固定按 UTF-8 解码（EPUB 规范）；
 *   常见命名 HTML 实体（`&nbsp;` 等）先经 [EntityNormalizingReader] 改写为等价数字引用，
 *   数字引用与预定义实体由 XmlPullParser 解析。
 * - 每个块输出前 trim 两端空白（`Char.isWhitespace`；NBSP 不在其中，原样保留），块内部空白原样保留。
 * - 文档开头不产生前导换行，结尾不保留多余换行。
 */
class HtmlTextFlattener(
    private val newParser: () -> XmlPullParser = { Xml.newPullParser() },
) {

    /**
     * [onAnchor] 非空时，遇到带 `id` 属性的元素（或老式 `<a name>`）在开始标签处理完毕后回调，
     * 回调内可用 [FlattenSink.anchorOffset] 采样该元素首个文本的压平偏移；只读操作，不影响输出。
     * [currentFile] 为当前 XHTML 在 zip 内的路径（链接/图片目标解析基准）；
     * [onImage] 返回图片原始尺寸 [宽, 高] 表示确认是 zip 内图片条目（可同时抽取字节），返回 null 则跳过。
     */
    fun flatten(
        input: InputStream,
        sink: FlattenSink,
        onAnchor: ((id: String) -> Unit)? = null,
        currentFile: String = "",
        onImage: ((zipPath: String) -> IntArray?)? = null,
    ) {
        val parser = newPullParser(newParser)
        parser.setInput(EntityNormalizingReader(input.reader(Charsets.UTF_8)))
        var skipDepth = 0
        val rubyStack = ArrayDeque<RubyCtx>()
        val listStack = ArrayDeque<ListCtx>()
        val styleStack = ArrayDeque<TextSpanType>()
        // a 标签栈：null 占位表示无 href/不记 span 的 a，保持嵌套配对
        val linkStack = ArrayDeque<LinkCtx?>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val name = parser.name.lowercase()
                    when {
                        skipDepth > 0 -> skipDepth++
                        name in SKIP_TAGS -> skipDepth = 1
                        name == "ruby" -> rubyStack.addLast(RubyCtx())
                        name == "rt" -> rubyStack.lastOrNull()?.let { ctx ->
                            if (ctx.rtcDepth == 0) {
                                ctx.inRt = true
                                ctx.rtBuf.setLength(0)
                            }
                        }
                        name == "rp" -> rubyStack.lastOrNull()?.let { it.inRp = true }
                        name == "rtc" -> rubyStack.lastOrNull()?.let { it.rtcDepth++ }
                        name == "ol" -> {
                            sink.blockBoundary()
                            val start = parser.getAttributeValue(null, "start")?.toIntOrNull() ?: 1
                            listStack.addLast(ListCtx(ordered = true, next = start))
                        }
                        name == "ul" -> {
                            sink.blockBoundary()
                            listStack.addLast(ListCtx(ordered = false, next = 0))
                        }
                        name == "li" -> {
                            sink.blockBoundary()
                            val indent = LIST_INDENT.repeat(maxOf(listStack.size - 1, 0))
                            val marker = listStack.lastOrNull()?.let { ctx ->
                                if (ctx.ordered) "${ctx.next++}. " else UL_MARKER
                            } ?: UL_MARKER
                            sink.prefix(indent + marker)
                        }
                        name == "td" || name == "th" -> sink.cellBoundary()
                        STYLE_TAGS.containsKey(name) -> styleStack.addLast(STYLE_TAGS.getValue(name))
                        name == "a" -> linkStack.addLast(resolveLink(parser, currentFile))
                        name == "img" || name == "image" ->
                            emitImagePlaceholder(parser, sink, currentFile, onImage)
                        name in BLOCK_TAGS -> sink.blockBoundary()
                    }
                    if (onAnchor != null && skipDepth == 0) {
                        val id = parser.getAttributeValue(null, "id")
                            ?: if (name == "a") parser.getAttributeValue(null, "name") else null
                        if (!id.isNullOrEmpty()) onAnchor(id)
                    }
                }
                XmlPullParser.END_TAG -> {
                    val name = parser.name.lowercase()
                    when {
                        skipDepth > 0 -> skipDepth--
                        name == "ruby" -> rubyStack.removeLastOrNull()?.let { ctx ->
                            if (ctx.inRt) {
                                ctx.rtBuf.trim().toString().takeIf { it.isNotEmpty() }?.let { ctx.rtParts.add(it) }
                            }
                            if (ctx.rtParts.isNotEmpty()) {
                                sink.text("（" + ctx.rtParts.joinToString(" ") + "）")
                            }
                        }
                        name == "rt" -> rubyStack.lastOrNull()?.let { ctx ->
                            if (ctx.inRt) {
                                ctx.inRt = false
                                ctx.rtBuf.trim().toString().takeIf { it.isNotEmpty() }?.let { ctx.rtParts.add(it) }
                            }
                        }
                        name == "rp" -> rubyStack.lastOrNull()?.let { it.inRp = false }
                        name == "rtc" -> rubyStack.lastOrNull()?.let { if (it.rtcDepth > 0) it.rtcDepth-- }
                        name == "ol" || name == "ul" -> {
                            sink.blockBoundary()
                            listStack.removeLastOrNull()
                        }
                        name == "li" -> sink.blockBoundary()
                        name == "td" || name == "th" -> Unit
                        name == "a" -> linkStack.removeLastOrNull()
                        STYLE_TAGS.containsKey(name) -> {
                            // 配对失衡防御：只移除最近一层同类标记
                            val type = STYLE_TAGS.getValue(name)
                            val idx = styleStack.indexOfLast { it == type }
                            if (idx >= 0) styleStack.removeAt(idx)
                        }
                        name in EMPTY_BLOCK_TAGS -> Unit
                        name in BLOCK_TAGS -> sink.blockBoundary()
                    }
                }
                XmlPullParser.TEXT -> if (skipDepth == 0) {
                    val ctx = rubyStack.lastOrNull()
                    when {
                        ctx == null -> {
                            val (marks, payload) = currentMarks(styleStack, linkStack)
                            sink.text(parser.text, marks, payload)
                        }
                        ctx.rtcDepth > 0 || ctx.inRp -> Unit
                        ctx.inRt -> ctx.rtBuf.append(parser.text)
                        else -> {
                            val (marks, payload) = currentMarks(styleStack, linkStack)
                            sink.text(parser.text, marks, payload)
                        }
                    }
                }
            }
            event = parser.next()
        }
        sink.finish()
    }

    /** 当前样式标记集合 + 链接 payload（内层 a 优先）。 */
    private fun currentMarks(
        styleStack: ArrayDeque<TextSpanType>,
        linkStack: ArrayDeque<LinkCtx?>,
    ): Pair<Set<TextSpanType>, String?> {
        val link = linkStack.lastOrNull { it != null }
        if (styleStack.isEmpty() && link == null) return emptySet<TextSpanType>() to null
        val marks = styleStack.toMutableSet()
        if (link != null) marks += link.type
        return marks to link?.payload
    }

    /** a[href] → 链接上下文；无 href、非 http(s) 的 scheme（mailto 等）不记 span（null 占位保持配对）。 */
    private fun resolveLink(parser: XmlPullParser, currentFile: String): LinkCtx? {
        val href = parser.getAttributeValue(null, "href")?.takeIf { it.isNotBlank() } ?: return null
        // 显式 scheme（mailto: 等非 http(s)）不记 span；相对路径/纯 fragment 无 scheme
        val colon = href.indexOf(':')
        if (colon > 0) {
            val scheme = href.substring(0, colon)
            if (scheme.all { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' } &&
                !scheme.equals("http", ignoreCase = true) && !scheme.equals("https", ignoreCase = true)
            ) {
                return null
            }
        }
        val type = if (parser.getAttributeValue(null, "epub:type")?.split(' ')?.contains("noteref") == true) {
            TextSpanType.NOTEREF
        } else {
            TextSpanType.LINK
        }
        // payload 先记 zip 级目标（路径#fragment 或外部 URL），全书压平后由 EpubBookParser 解析为偏移
        val target = when {
            href.startsWith("http://") || href.startsWith("https://") -> href
            href.startsWith("#") -> currentFile + href
            else -> {
                val (file, fragment) = splitHref(currentFile.substringBeforeLast('/', ""), href)
                file + (fragment?.let { "#$it" } ?: "")
            }
        }
        return LinkCtx(type, target)
    }

    /** img/image：确认为 zip 内图片条目时输出独立占位块 + IMAGE span；否则整体跳过。 */
    private fun emitImagePlaceholder(
        parser: XmlPullParser,
        sink: FlattenSink,
        currentFile: String,
        onImage: ((zipPath: String) -> IntArray?)?,
    ) {
        val src = parser.getAttributeValue(null, "src")
            ?: parser.getAttributeValue(null, "xlink:href")
            ?: parser.getAttributeValue(null, "href")
        val path = src?.takeIf { it.isNotBlank() && !it.contains("://") }
            ?.let { splitHref(currentFile.substringBeforeLast('/', ""), it).first }
        val size = if (path != null && onImage != null) onImage(path) else null
        if (path != null && size != null) {
            val alt = parser.getAttributeValue(null, "alt")?.takeIf { it.isNotBlank() }
            sink.blockBoundary()
            sink.text(
                IMAGE_PLACEHOLDER.toString(),
                setOf(TextSpanType.IMAGE),
                payload = path,
                alt = alt,
                width = size[0],
                height = size[1],
            )
            sink.blockBoundary()
        }
    }

    /** ruby 注音上下文：rt 注音收集（支持多个），rp/rtc 内容跳过。 */
    private class RubyCtx {
        var inRt = false
        var inRp = false
        var rtcDepth = 0
        val rtBuf = StringBuilder()
        val rtParts = ArrayList<String>()
    }

    /** 列表上下文：ol 计数器（嵌套重新计数、结束恢复外层）。 */
    private class ListCtx(val ordered: Boolean, var next: Int)

    /** 链接上下文：类型 + 压平期 payload（zip 级目标或外部 URL）。 */
    private class LinkCtx(val type: TextSpanType, val payload: String)

    companion object {
        /** 图片占位字符（object replacement character）：独立成块，分页/渲染期识别。 */
        const val IMAGE_PLACEHOLDER = '￼'

        val BLOCK_TAGS = setOf(
            "p", "div", "h1", "h2", "h3", "h4", "h5", "h6",
            "blockquote", "br", "hr",
            "section", "article", "header", "footer", "table", "tr", "caption",
            "figure", "figcaption",
        )
        private val EMPTY_BLOCK_TAGS = setOf("br", "hr")
        private val SKIP_TAGS = setOf("script", "style", "head")
        private val STYLE_TAGS = mapOf(
            "b" to TextSpanType.BOLD,
            "strong" to TextSpanType.BOLD,
            "i" to TextSpanType.ITALIC,
            "em" to TextSpanType.ITALIC,
            "sup" to TextSpanType.SUP,
            "sub" to TextSpanType.SUB,
        )
        private const val UL_MARKER = "● "
        private const val LIST_INDENT = "　　"
    }
}

/**
 * 压平输出汇：流式写入，只缓冲当前块（超长块分片冲刷），
 * 换行延迟到确定有后续文本时才落盘（从而开头/结尾不留多余空行）。
 * 行首前缀（[prefix]）与单元格分隔符（[cellBoundary]）同样挂起到下一段文本输出前生效。
 * 样式标记（[text] 的 types/payload）随块缓冲记录，冲刷时换算为压平流绝对偏移。
 */
class FlattenSink(private val out: Writer) {

    private val buf = StringBuilder()
    private var pendingNewlines = 0
    private var pendingPrefix: String? = null
    private var pendingSeparator = false
    private var emittedAny = false
    private val markPieces = ArrayList<MarkPiece>()
    private val absPieces = ArrayList<AbsPiece>()

    /** 已写入的字符数（不含挂起换行/前缀/分隔符与块缓冲）。 */
    var charCount: Long = 0L
        private set

    /** 下一段文本在压平流中的起始偏移（含挂起换行；调用前须先 [blockBoundary] 清空块缓冲）。 */
    val nextTextOffset: Long get() = charCount + pendingNewlines

    /**
     * 锚点采样：以非丢弃模式定界当前块缓冲（同 FLUSH_THRESHOLD 中途冲刷，最终文本输出不变），
     * 返回下一段文本在压平流中的起始偏移（含挂起前缀/分隔符）。块级/行内元素锚点均安全。
     */
    fun anchorOffset(): Long {
        flushBuffer(discardTail = false)
        return charCount + pendingNewlines +
            (pendingPrefix?.length ?: 0) + (if (pendingSeparator) CELL_SEPARATOR.length else 0)
    }

    fun text(raw: String) = text(raw, emptySet())

    /** 带样式标记的文本：types 非空时随块缓冲记录 span 片段，冲刷时换算为绝对偏移。 */
    fun text(
        raw: String,
        types: Set<TextSpanType>,
        payload: String? = null,
        alt: String? = null,
        width: Int = 0,
        height: Int = 0,
    ) {
        if (raw.isEmpty()) return
        if (types.isNotEmpty()) {
            markPieces += MarkPiece(buf.length, buf.length + raw.length, types, payload, alt, width, height)
        }
        buf.append(raw)
        if (buf.length > FLUSH_THRESHOLD) flushBuffer(discardTail = false)
    }

    /** 行首前缀（列表序号/项目符号）：挂起到下一段文本输出前生效；空挂起（无后续文本）不输出。 */
    fun prefix(prefix: String) {
        pendingPrefix = prefix
    }

    /** 单元格边界：不产生换行；同行内（无挂起换行）且刚冲刷出文本时挂起一个全角分隔符。 */
    fun cellBoundary() {
        val emitted = flushBuffer(discardTail = true)
        if (emitted && pendingNewlines == 0) pendingSeparator = true
    }

    fun blockBoundary() {
        val emitted = flushBuffer(discardTail = true)
        if (!emittedAny) return
        pendingNewlines = if (emitted) 1 else minOf(pendingNewlines + 1, MAX_NEWLINES)
    }

    /** 单换行边界（FB2 诗行 `v` 等）：与已有空行挂起不叠加，行首不产生前导换行。 */
    fun lineBreak() {
        val emitted = flushBuffer(discardTail = true)
        if (emittedAny && emitted && pendingNewlines == 0) pendingNewlines = 1
    }

    /** 文档/条目结束：冲刷残余缓冲并丢弃挂起前缀/分隔符；本身不产生换行。 */
    fun finish() {
        flushBuffer(discardTail = true)
        pendingPrefix = null
        pendingSeparator = false
    }

    /**
     * 已记录的 span 列表：相邻同构片段合并后按类型展开，同类型相邻区间再合并。
     * 范围均为压平流绝对 char 偏移 [start, end)。
     */
    fun recordedSpans(): List<TextSpan> {
        if (absPieces.isEmpty()) return emptyList()
        val merged = ArrayList<AbsPiece>(absPieces.size)
        for (piece in absPieces) {
            val last = merged.lastOrNull()
            if (last != null && last.end == piece.start && last.types == piece.types &&
                last.payload == piece.payload && last.alt == piece.alt &&
                last.width == piece.width && last.height == piece.height
            ) {
                last.end = piece.end
            } else {
                merged += piece.copy()
            }
        }
        val expanded = ArrayList<TextSpan>()
        for (piece in merged) {
            for (type in piece.types) {
                expanded += TextSpan(type, piece.start, piece.end, piece.payload, piece.alt, piece.width, piece.height)
            }
        }
        expanded.sortWith(compareBy({ it.type.ordinal }, { it.payload }, { it.alt }, { it.start }))
        val result = ArrayList<TextSpan>(expanded.size)
        for (span in expanded) {
            val last = result.lastOrNull()
            if (last != null && last.type == span.type && last.payload == span.payload &&
                last.alt == span.alt && last.end == span.start
            ) {
                result[result.lastIndex] = last.copy(end = span.end)
            } else {
                result += span
            }
        }
        result.sortBy { it.start }
        return result
    }

    /** 冲刷块缓冲；返回是否真正输出了文本。非丢弃模式保留尾部空白挂起到下次。 */
    private fun flushBuffer(discardTail: Boolean): Boolean {
        if (buf.isEmpty()) return false
        var start = 0
        if (!emittedAny || pendingNewlines > 0 || pendingSeparator) {
            while (start < buf.length && buf[start].isWhitespace()) start++
        }
        var end = buf.length
        while (end > start && buf[end - 1].isWhitespace()) end--
        val emitted = end > start
        if (emitted) {
            var absBase = charCount
            if (emittedAny && pendingNewlines > 0) {
                repeat(pendingNewlines) { out.write('\n'.code) }
                charCount += pendingNewlines
                absBase += pendingNewlines
                pendingSeparator = false
            }
            if (pendingSeparator) {
                out.write(CELL_SEPARATOR)
                charCount += CELL_SEPARATOR.length
                absBase += CELL_SEPARATOR.length
                pendingSeparator = false
            }
            pendingPrefix?.let {
                out.append(it)
                charCount += it.length
                absBase += it.length
                pendingPrefix = null
            }
            if (markPieces.isNotEmpty()) {
                for (piece in markPieces) {
                    val s = maxOf(piece.bufStart, start)
                    val e = minOf(piece.bufEnd, end)
                    if (s < e) {
                        absPieces += AbsPiece(
                            absBase + (s - start), absBase + (e - start),
                            piece.types, piece.payload, piece.alt, piece.width, piece.height,
                        )
                    }
                }
            }
            out.append(buf, start, end)
            charCount += (end - start).toLong()
            emittedAny = true
            pendingNewlines = 0
        }
        // 缓冲收缩（[0, end) 被移除）后同步移动/丢弃标记片段
        if (markPieces.isNotEmpty()) {
            if (discardTail) {
                markPieces.clear()
            } else {
                val it = markPieces.iterator()
                while (it.hasNext()) {
                    val piece = it.next()
                    if (piece.bufEnd <= end) {
                        it.remove()
                    } else {
                        piece.bufStart = maxOf(piece.bufStart - end, 0)
                        piece.bufEnd -= end
                    }
                }
            }
        }
        if (discardTail) buf.setLength(0) else buf.delete(0, end)
        return emitted
    }

    /** 块缓冲内的标记片段（冲刷时换算绝对偏移）。 */
    private class MarkPiece(
        var bufStart: Int,
        var bufEnd: Int,
        val types: Set<TextSpanType>,
        val payload: String?,
        val alt: String?,
        val width: Int,
        val height: Int,
    )

    /** 压平流绝对偏移的标记片段（未按类型展开）。 */
    private data class AbsPiece(
        val start: Long,
        var end: Long,
        val types: Set<TextSpanType>,
        val payload: String?,
        val alt: String?,
        val width: Int,
        val height: Int,
    )

    private companion object {
        const val MAX_NEWLINES = 2
        const val FLUSH_THRESHOLD = 64 * 1024
        const val CELL_SEPARATOR = " ｜ "
    }
}

package com.llzx373.foldreader.core.format.markdown

import com.llzx373.foldreader.core.format.Chapter
import org.commonmark.Extension
import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.node.Code
import org.commonmark.node.Heading
import org.commonmark.node.Node
import org.commonmark.node.Text
import org.commonmark.parser.IncludeSourceSpans
import org.commonmark.parser.Parser

/**
 * commonmark AST 共用件：MarkdownBookParser（章节/元数据）与 MarkdownReaderScreen
 * （块级渲染 + 块→偏移换算）都基于同一套解析配置，免得两处扩展清单漂移。
 *
 * 解析输入始终是「已解码且剥掉 BOM」的字符串（见 MarkdownBookParser.decode），
 * 所以 source span 的 inputIndex 直接就是文本字符偏移，与进度/书签锚点同坐标系。
 */

/** GFM 四件套：自动链接、删除线、表格、任务列表（与 Fossify 的 Markdown 预览同款）。 */
internal val gfmExtensions: List<Extension> = listOf(
    AutolinkExtension.create(),
    StrikethroughExtension.create(),
    TablesExtension.create(),
    TaskListItemsExtension.create(),
)

/** 块级 source span 必须打开，章节的 charStart 与渲染层的块→偏移换算全靠它。 */
fun newMarkdownParser(): Parser = Parser.builder()
    .extensions(gfmExtensions)
    .includeSourceSpans(IncludeSourceSpans.BLOCKS)
    .build()

fun Node.childrenNodes(): List<Node> = generateSequence(firstChild) { it.next }.toList()

fun Node.descendantNodes(): List<Node> =
    childrenNodes().flatMap { child -> listOf(child) + child.descendantNodes() }

/** 行内纯文本（标题/目录提取用）：Text 与行内 Code 取字面量，其余节点递归下钻。 */
fun Node.plainText(): String = buildString {
    fun walk(node: Node) {
        when (node) {
            is Text -> append(node.literal)
            is Code -> append(node.literal)
            else -> node.childrenNodes().forEach(::walk)
        }
    }
    walk(this@plainText)
}

/** 块在源文本中的起始字符偏移；解析器未给 span 时返回 null（理论上开了 BLOCKS 不会缺）。 */
fun Node.blockStartOffset(): Long? =
    sourceSpans.minOfOrNull { it.inputIndex }?.toLong()

/**
 * 目录：收齐全文所有标题（h1-h6，含嵌套在引用块里的），按文档顺序铺开。
 * charStart = 标题在源文本中的字符偏移，charEnd = 下一个标题的起点（末章到文末），
 * depth = 标题级别 - 1（h1 为顶层，封顶 5）。无标题退化为单个「正文」章（同 TXT 兜底）。
 */
fun chaptersOf(text: String, document: Node): List<Chapter> {
    val headings = document.descendantNodes().filterIsInstance<Heading>()
    if (headings.isEmpty()) {
        return listOf(Chapter(title = "正文", charStart = 0, charEnd = text.length.toLong()))
    }
    val starts = headings.map { it.blockStartOffset() ?: 0L }
    return headings.mapIndexed { index, heading ->
        Chapter(
            title = heading.plainText().ifBlank { "无标题" },
            charStart = starts[index],
            charEnd = starts.getOrNull(index + 1) ?: text.length.toLong(),
            depth = (heading.level - 1).coerceIn(0, 5),
        )
    }
}

fun chaptersOf(text: String, parser: Parser = newMarkdownParser()): List<Chapter> =
    chaptersOf(text, parser.parse(text))

/** 书名：第一个标题的纯文本；没有标题返回 null（调用方回退文件名）。 */
fun titleOf(document: Node): String? =
    document.descendantNodes().filterIsInstance<Heading>().firstOrNull()
        ?.plainText()?.trim()?.takeIf { it.isNotEmpty() }

/** 顶层渲染块清单（LazyColumn 的 item 序列，与 [blockStartOffsets] 下标对齐）。 */
fun topLevelBlocks(document: Node): List<Node> = document.childrenNodes()

/**
 * 每个顶层块的起始字符偏移（升序）。个别块缺 span 时沿用前一块的偏移，
 * 保证序列单调——二分查找（[blockIndexAtOffset]）依赖这一点。
 */
fun blockStartOffsets(blocks: List<Node>, textLength: Int): LongArray {
    val starts = LongArray(blocks.size)
    var last = 0L
    blocks.forEachIndexed { index, node ->
        val start = (node.blockStartOffset() ?: last).coerceIn(0L, textLength.toLong())
        starts[index] = start
        last = start
    }
    return starts
}

/** 偏移 → 块下标：最后一个 start <= offset 的块（与 chapterIndexAt 同口径）。 */
fun blockIndexAtOffset(starts: LongArray, offset: Long): Int {
    if (starts.isEmpty()) return 0
    var lo = 0
    var hi = starts.size - 1
    var found = 0
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (starts[mid] <= offset) {
            found = mid
            lo = mid + 1
        } else {
            hi = mid - 1
        }
    }
    return found
}

package com.llzx373.foldreader.feature.reader.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.llzx373.foldreader.core.format.markdown.childrenNodes
import com.llzx373.foldreader.core.format.markdown.descendantNodes
import com.llzx373.foldreader.core.format.markdown.plainText
import com.llzx373.foldreader.feature.reader.ReaderColors
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableBody
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.task.list.items.TaskListItemMarker
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.LinkReferenceDefinition
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.ThematicBreak
import org.commonmark.node.Text as MarkdownText

/**
 * Markdown 块级渲染：一个顶层 AST 块 = LazyColumn 的一个 item。
 *
 * 主题跟随阅读设置（ReaderColors/字号/行距），不引入第三套配色；
 * 结构参照 Fossify Documents 的 Markdown 预览（同一份 commonmark AST 的遍历方式）。
 * 图片 v1 只渲染 alt 文本（Markdown 多为外链图，本地图随导入副本的机制都没有）。
 */

/** 渲染样式快照：跟随全局阅读设置（字号/行距/主题色）。 */
@Immutable
class MarkdownStyles(
    val colors: ReaderColors,
    val fontSizeSp: Float,
    val lineSpacingMultiplier: Float,
) {
    val bodyFontSize get() = fontSizeSp.sp
    val bodyLineHeight get() = (fontSizeSp * lineSpacingMultiplier).sp
    val inlineCodeBackground get() = colors.text.copy(alpha = 0.08f)
    val codeBlockBackground get() = colors.text.copy(alpha = 0.06f)
    val dividerColor get() = colors.text.copy(alpha = 0.2f)
}

/** 行内渲染：Bold/Italic/Strikethrough/Code/Link/Image（alt）/软硬换行/内联 HTML。 */
internal fun Node.inlineContent(styles: MarkdownStyles): AnnotatedString = buildAnnotatedString {
    fun appendNode(node: Node) {
        when (node) {
            is MarkdownText -> append(node.literal)
            is Code -> withStyle(
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    background = styles.inlineCodeBackground,
                )
            ) { append(node.literal) }

            is Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                node.childrenNodes().forEach(::appendNode)
            }

            is StrongEmphasis -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                node.childrenNodes().forEach(::appendNode)
            }

            is Strikethrough -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                node.childrenNodes().forEach(::appendNode)
            }

            is Link -> withLink(
                LinkAnnotation.Url(
                    url = node.destination,
                    styles = TextLinkStyles(
                        style = SpanStyle(
                            color = styles.colors.accent,
                            textDecoration = TextDecoration.Underline,
                        ),
                    ),
                ),
            ) { node.childrenNodes().forEach(::appendNode) }

            // 图片 v1 不加载：渲染 alt 文本（无 alt 给个占位），斜体弱化存在感
            is Image -> withStyle(
                SpanStyle(fontStyle = FontStyle.Italic, color = styles.colors.accent)
            ) {
                append(node.plainText().ifBlank { "[图片]" })
            }

            is SoftLineBreak -> append(" ")
            is HardLineBreak -> append("\n")
            is HtmlInline -> append(node.literal)
            // 任务列表的勾选框由列表项的 marker 渲染，行内不再出现
            is TaskListItemMarker -> Unit
            else -> node.childrenNodes().forEach(::appendNode)
        }
    }
    childrenNodes().forEach(::appendNode)
}

/** 单个顶层（或嵌套）块的渲染入口。 */
@Composable
internal fun MarkdownBlockView(node: Node, styles: MarkdownStyles) {
    when (node) {
        is Heading -> MarkdownHeading(node, styles)
        is Paragraph -> MarkdownParagraph(node, styles)
        is BlockQuote -> MarkdownBlockQuote(node, styles)
        is FencedCodeBlock -> MarkdownCodeBlock(node.literal, styles)
        is IndentedCodeBlock -> MarkdownCodeBlock(node.literal, styles)
        is HtmlBlock ->
            // 原始 HTML 块按代码块渲染（同 Fossify）：不解析不执行，原样展示
            MarkdownCodeBlock(node.literal, styles)
        is BulletList -> MarkdownListView(node, styles, ordered = false)
        is OrderedList -> MarkdownListView(node, styles, ordered = true)
        is TableBlock -> MarkdownTable(node, styles)
        is ThematicBreak -> HorizontalDivider(
            color = styles.dividerColor,
            modifier = Modifier.padding(vertical = 10.dp),
        )
        // 链接引用定义不产出可见内容
        is LinkReferenceDefinition -> Unit
        // 兜底：有行内子节点的块按段落渲染
        else -> if (node.firstChild != null) MarkdownParagraph(node, styles) else Unit
    }
}

/** 标题分级：字号相对正文缩放（h1 最大），全部加粗，上方留白随级别递减。 */
@Composable
private fun MarkdownHeading(node: Heading, styles: MarkdownStyles) {
    val scale = when (node.level) {
        1 -> 1.6f
        2 -> 1.4f
        3 -> 1.25f
        4 -> 1.12f
        5 -> 1.0f
        else -> 0.92f
    }
    Text(
        text = node.inlineContent(styles),
        color = styles.colors.text,
        fontSize = (styles.fontSizeSp * scale).sp,
        lineHeight = (styles.fontSizeSp * scale * styles.lineSpacingMultiplier).sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = if (node.level <= 2) 18.dp else 12.dp, bottom = 6.dp),
    )
}

@Composable
private fun MarkdownParagraph(node: Node, styles: MarkdownStyles) {
    Text(
        text = node.inlineContent(styles),
        color = styles.colors.text,
        fontSize = styles.bodyFontSize,
        lineHeight = styles.bodyLineHeight,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

/** 引用块：左侧 3dp 竖条 + 缩进，子块递归渲染。 */
@Composable
private fun MarkdownBlockQuote(node: BlockQuote, styles: MarkdownStyles) {
    Row(modifier = Modifier.height(IntrinsicSize.Min).padding(vertical = 4.dp)) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(styles.colors.accent.copy(alpha = 0.5f)),
        )
        Column(modifier = Modifier.padding(start = 10.dp)) {
            node.childrenNodes().forEach { MarkdownBlockView(it, styles) }
        }
    }
}

/** 代码块：等宽字体 + 底色圆角块 + 横向滚动（长行不折行）。 */
@Composable
private fun MarkdownCodeBlock(literal: String, styles: MarkdownStyles) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(styles.codeBlockBackground, RoundedCornerShape(6.dp))
            .horizontalScroll(rememberScrollState())
            .padding(10.dp),
    ) {
        Text(
            text = literal.trimEnd('\n'),
            color = styles.colors.text,
            fontSize = (styles.fontSizeSp * 0.88f).sp,
            lineHeight = (styles.fontSizeSp * 0.88f * styles.lineSpacingMultiplier).sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/**
 * 列表：有序取 markerStartNumber 起编号，无序用「•」，任务列表项换成 ☐/☑。
 * 嵌套列表经子块递归自然缩进。
 */
@Composable
private fun MarkdownListView(node: Node, styles: MarkdownStyles, ordered: Boolean) {
    val items = node.childrenNodes().filterIsInstance<ListItem>()
    val startNumber = (node as? OrderedList)?.markerStartNumber ?: 1
    Column(modifier = Modifier.padding(vertical = 2.dp)) {
        items.forEachIndexed { index, item ->
            val taskMarker = item.descendantNodes().filterIsInstance<TaskListItemMarker>().firstOrNull()
            val marker = when {
                taskMarker != null -> if (taskMarker.isChecked) "☑" else "☐"
                ordered -> "${startNumber + index}."
                else -> "•"
            }
            Row(modifier = Modifier.padding(vertical = 1.dp)) {
                Text(
                    text = "$marker ",
                    color = if (taskMarker != null) styles.colors.accent else styles.colors.text,
                    fontSize = styles.bodyFontSize,
                    lineHeight = styles.bodyLineHeight,
                    modifier = Modifier.padding(start = 8.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    item.childrenNodes().forEach { MarkdownBlockView(it, styles) }
                }
            }
        }
    }
}

/** 表格：横向滚动，表头加粗，行间细分隔线。 */
@Composable
private fun MarkdownTable(node: TableBlock, styles: MarkdownStyles) {
    val headRows = node.childrenNodes().filterIsInstance<TableHead>()
        .flatMap { it.childrenNodes().filterIsInstance<TableRow>() }
    val bodyRows = node.childrenNodes().filterIsInstance<TableBody>()
        .flatMap { it.childrenNodes().filterIsInstance<TableRow>() }
    Column(
        modifier = Modifier
            .padding(vertical = 4.dp)
            .horizontalScroll(rememberScrollState()),
    ) {
        HorizontalDivider(color = styles.dividerColor)
        headRows.forEach { row ->
            MarkdownTableRow(row, styles, header = true)
            HorizontalDivider(color = styles.dividerColor)
        }
        bodyRows.forEach { row ->
            MarkdownTableRow(row, styles, header = false)
            HorizontalDivider(color = styles.dividerColor)
        }
    }
}

@Composable
private fun MarkdownTableRow(row: TableRow, styles: MarkdownStyles, header: Boolean) {
    Row {
        row.childrenNodes().filterIsInstance<TableCell>().forEach { cell ->
            Text(
                text = cell.inlineContent(styles),
                color = styles.colors.text,
                fontSize = styles.bodyFontSize,
                lineHeight = styles.bodyLineHeight,
                fontWeight = if (header || cell.isHeader) FontWeight.Bold else null,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

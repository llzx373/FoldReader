package com.llzx373.foldreader.feature.bookshelf

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.llzx373.foldreader.core.data.db.AnnotationEntity
import com.llzx373.foldreader.core.data.db.BookWithProgress
import com.llzx373.foldreader.core.data.db.BookmarkEntity
import com.llzx373.foldreader.feature.reader.sortBookmarksByRecency
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 全书签/标注总览：按书聚合，点击跳转阅读器对应锚点。 */
@Composable
fun BookmarkOverviewDialog(
    books: List<BookWithProgress>,
    bookmarks: List<BookmarkEntity>,
    annotations: List<AnnotationEntity>,
    onJump: (bookId: Long, anchor: Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val bookmarksByBook = remember(bookmarks) { bookmarks.groupBy { it.bookId } }
    val annotationsByBook = remember(annotations) { annotations.groupBy { it.bookId } }
    val groups = remember(books, bookmarksByBook, annotationsByBook) {
        books.mapNotNull { item ->
            val bookBookmarks = bookmarksByBook[item.book.id].orEmpty()
                .let { sortBookmarksByRecency(it) }
            val bookAnnotations = annotationsByBook[item.book.id].orEmpty()
                .sortedBy { it.startCharOffset }
            if (bookBookmarks.isEmpty() && bookAnnotations.isEmpty()) {
                null
            } else {
                OverviewGroup(item.book.id, item.book.title, bookBookmarks, bookAnnotations)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
        title = { Text("书签与标注") },
        text = {
            if (groups.isEmpty()) {
                Text(
                    text = "还没有任何书签或标注，阅读时点顶栏缎带或长按划线试试",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.height(420.dp)) {
                    groups.forEach { group ->
                        item(key = "header-${group.bookId}") {
                            Text(
                                text = group.title,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                            )
                        }
                        group.bookmarks.forEach { bookmark ->
                            item(key = "bm-${bookmark.id}") {
                                OverviewRow(
                                    primary = bookmark.label.ifEmpty { bookmark.snapshotText },
                                    secondary = "书签 · ${formatTime(bookmark.createdAt)}",
                                    onClick = { onJump(group.bookId, bookmark.charOffset) },
                                )
                            }
                        }
                        group.annotations.forEach { annotation ->
                            item(key = "ann-${annotation.id}") {
                                OverviewRow(
                                    primary = annotation.selectedText,
                                    secondary = listOfNotNull(
                                        "划线",
                                        annotation.note?.takeIf { it.isNotEmpty() },
                                    ).joinToString(" · "),
                                    markerColor = Color(annotation.color.toInt()),
                                    onClick = { onJump(group.bookId, annotation.startCharOffset) },
                                )
                            }
                        }
                        item(key = "divider-${group.bookId}") {
                            HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                }
            }
        },
    )
}

private data class OverviewGroup(
    val bookId: Long,
    val title: String,
    val bookmarks: List<BookmarkEntity>,
    val annotations: List<AnnotationEntity>,
)

@Composable
private fun OverviewRow(
    primary: String,
    secondary: String,
    onClick: () -> Unit,
    markerColor: Color? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
    ) {
        if (markerColor != null) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(markerColor, MaterialTheme.shapes.small),
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Column {
            Text(
                text = primary,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = secondary,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 书架顶栏「书签与标注」入口图标：缎带 + 列表线。 */
@Composable
fun BookmarkOverviewIcon(contentDescription: String) {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(
        modifier = Modifier
            .size(24.dp)
            .semantics { this.contentDescription = contentDescription },
    ) {
        val w = size.width
        val h = size.height
        val stroke = 2.dp.toPx()
        val path = Path().apply {
            moveTo(w * 0.10f, h * 0.10f)
            lineTo(w * 0.42f, h * 0.10f)
            lineTo(w * 0.42f, h * 0.72f)
            lineTo(w * 0.26f, h * 0.56f)
            lineTo(w * 0.10f, h * 0.72f)
            close()
        }
        drawPath(path, color = tint, style = Stroke(width = stroke))
        drawLine(tint, Offset(w * 0.56f, h * 0.26f), Offset(w * 0.92f, h * 0.26f), strokeWidth = stroke)
        drawLine(tint, Offset(w * 0.56f, h * 0.52f), Offset(w * 0.92f, h * 0.52f), strokeWidth = stroke)
    }
}

private fun formatTime(timestamp: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))

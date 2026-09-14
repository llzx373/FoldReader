package com.llzx373.foldreader.feature.reader

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.llzx373.foldreader.core.data.db.BookmarkEntity
import com.llzx373.foldreader.core.format.Chapter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 书签列表：按书内位置（charOffset）排序；点击跳转，长按重命名/删除。 */
@Composable
fun BookmarkListDialog(
    bookmarks: List<BookmarkEntity>,
    chapters: List<Chapter>,
    colors: ReaderColors,
    onJump: (BookmarkEntity) -> Unit,
    onRename: (BookmarkEntity, String) -> Unit,
    onDelete: (BookmarkEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    var editing by remember { mutableStateOf<BookmarkEntity?>(null) }
    val sorted = remember(bookmarks) { bookmarks.sortedBy { it.charOffset } }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
        title = { Text("书签") },
        text = {
            if (sorted.isEmpty()) {
                Text(
                    text = "还没有书签，点顶栏缎带图标给当前页加书签",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.height(360.dp)) {
                    items(sorted, key = { it.id }) { bookmark ->
                        BookmarkRow(
                            bookmark = bookmark,
                            chapterTitle = chapters.getOrNull(bookmark.chapterIndex)?.title,
                            colors = colors,
                            onClick = { onJump(bookmark) },
                            onLongClick = { editing = bookmark },
                        )
                    }
                }
            }
        },
    )

    editing?.let { target ->
        BookmarkEditDialog(
            bookmark = target,
            onSave = { label ->
                onRename(target, label)
                editing = null
            },
            onDelete = {
                onDelete(target)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookmarkRow(
    bookmark: BookmarkEntity,
    chapterTitle: String?,
    colors: ReaderColors,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = 8.dp),
    ) {
        Text(
            text = bookmark.label.ifEmpty { bookmark.snapshotText },
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (bookmark.label.isNotEmpty() && bookmark.snapshotText.isNotEmpty()) {
            Text(
                text = bookmark.snapshotText,
                style = MaterialTheme.typography.bodySmall,
                color = Color.Unspecified,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        val time = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(bookmark.createdAt))
        Text(
            text = listOfNotNull(chapterTitle?.takeIf { it.isNotEmpty() }, time).joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = colors.accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun BookmarkEditDialog(
    bookmark: BookmarkEntity,
    onSave: (String) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var label by remember(bookmark.id) { mutableStateOf(bookmark.label) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("书签备注") },
        text = {
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                placeholder = { Text(bookmark.snapshotText) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(label) }) { Text("保存") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDelete) { Text("删除") }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}


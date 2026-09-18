package com.llzx373.foldreader.feature.comic

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.llzx373.foldreader.core.comic.ComicSeriesCandidate
import com.llzx373.foldreader.feature.reader.ReaderColors

/**
 * 「同系列」列表：同目录 / 同分组里能认出来的前后卷。
 *
 * 未入库的那几卷也列出来（标「未导入」），点一下按需导入再打开——只导入了第 3 卷时，
 * 只要列出已导入的书，这个功能就等于没用。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ComicSeriesDialog(
    entries: List<ComicSeriesCandidate>,
    currentBookId: Long,
    colors: ReaderColors,
    onOpenBook: (Long) -> Unit,
    onImport: (ComicSeriesCandidate) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
        title = { Text("同系列") },
        text = {
            if (entries.isEmpty()) {
                Text(
                    text = "没有找到同一系列的其它卷。\n同系列是按文件名判断的：需要「主干相同、只有卷号不同」，" +
                        "例如 Foo 第01卷 / Foo 第02卷。授权失效或文件不在同一目录时也认不出来。",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.height(360.dp)) {
                    items(entries, key = { it.uri }) { entry ->
                        val isCurrent = entry.bookId == currentBookId
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !isCurrent) {
                                    entry.bookId?.let(onOpenBook) ?: onImport(entry)
                                }
                                .padding(vertical = 10.dp),
                        ) {
                            Row {
                                Text(
                                    text = entry.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                if (entry.bookId == null) {
                                    Text(
                                        text = "未导入",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = colors.accent,
                                        modifier = Modifier.padding(start = 8.dp),
                                    )
                                }
                            }
                            if (isCurrent) {
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "正在阅读",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.accent,
                                )
                            }
                        }
                    }
                }
            }
        },
    )
}

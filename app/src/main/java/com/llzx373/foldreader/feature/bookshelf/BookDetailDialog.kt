package com.llzx373.foldreader.feature.bookshelf

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.llzx373.foldreader.core.data.db.BookEntity
import com.llzx373.foldreader.core.data.db.ReadingProgressEntity
import com.llzx373.foldreader.core.reader.averageCharsPerMinute
import com.llzx373.foldreader.core.reader.formatDurationZh
import com.llzx373.foldreader.core.reader.readingDaysSpan
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 书籍详情：元信息 + 每书阅读统计（时长/进度/天数/平均速度）。 */
@Composable
fun BookDetailDialog(
    bookId: Long,
    viewModel: BookshelfViewModel,
    onDismiss: () -> Unit,
) {
    val detail by produceState<Pair<BookEntity?, ReadingProgressEntity?>?>(initialValue = null, bookId) {
        value = viewModel.bookDetail(bookId)
    }
    val book = detail?.first
    val progress = detail?.second
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
        title = { Text(book?.title ?: "书籍详情") },
        text = {
            if (book == null) {
                Text("加载中…")
            } else {
                val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                val percent = if (book.totalChars > 0 && progress != null) {
                    "${(progress.charOffset * 100 / book.totalChars).coerceIn(0, 100)}%"
                } else {
                    "未开始"
                }
                val totalMillis = progress?.totalReadingMillis ?: 0L
                val zone = java.time.ZoneId.systemDefault()
                Column(modifier = Modifier.fillMaxWidth()) {
                    DetailRow("作者", book.author ?: "未知作者")
                    DetailRow("总字数", "%,d 字".format(book.totalChars))
                    DetailRow("阅读进度", percent)
                    DetailRow("累计时长", formatDurationZh(totalMillis))
                    DetailRow(
                        "首次阅读",
                        progress?.firstReadAt?.takeIf { it > 0 }
                            ?.let { dateFormat.format(Date(it)) } ?: "—",
                    )
                    DetailRow(
                        "最近阅读",
                        book.lastReadAt?.let { dateFormat.format(Date(it)) } ?: "—",
                    )
                    DetailRow(
                        "阅读天数",
                        readingDaysSpan(
                            progress?.firstReadAt ?: 0L,
                            book.lastReadAt ?: 0L,
                            zone,
                        ).let { if (it > 0) "$it 天" else "—" },
                    )
                    DetailRow(
                        "平均速度",
                        averageCharsPerMinute(progress?.charOffset ?: 0L, totalMillis)
                            .let { if (it > 0) "$it 字/分钟" else "—" },
                    )
                }
            }
        },
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.35f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(0.65f),
        )
    }
}

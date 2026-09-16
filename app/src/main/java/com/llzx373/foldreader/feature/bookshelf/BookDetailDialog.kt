package com.llzx373.foldreader.feature.bookshelf

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.llzx373.foldreader.core.data.db.BookEntity
import com.llzx373.foldreader.core.data.db.BookFormat
import com.llzx373.foldreader.core.data.db.ReadingProgressEntity
import com.llzx373.foldreader.core.reader.averageCharsPerMinute
import com.llzx373.foldreader.core.reader.formatDurationZh
import com.llzx373.foldreader.ui.EncodingPickerDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 书籍详情：元信息 + 每书阅读统计（时长/进度/天数/平均速度）。 */
@Composable
fun BookDetailDialog(
    bookId: Long,
    viewModel: BookshelfViewModel,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refreshTick by remember { mutableIntStateOf(0) }
    var showEncodingPicker by remember { mutableStateOf(false) }
    val detail by produceState<Triple<BookEntity?, ReadingProgressEntity?, Int>?>(
        initialValue = null,
        bookId,
        refreshTick,
    ) {
        value = viewModel.bookDetail(bookId)
    }
    val book = detail?.first
    val progress = detail?.second
    val readingDays = detail?.third ?: 0
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
                Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    DetailRow("作者", book.author ?: "未知作者")
                    book.seriesName?.let { series ->
                        DetailRow(
                            "丛书",
                            series + (book.seriesIndex?.takeIf { it.isNotBlank() }
                                ?.let { " #$it" } ?: ""),
                        )
                    }
                    book.publisher?.let { DetailRow("出版社", it) }
                    book.pubDate?.let { DetailRow("出版日期", it) }
                    book.language?.let { DetailRow("语言", it) }
                    book.identifier?.let { DetailRow("ISBN / 标识", it) }
                    book.subjects?.let { subjects ->
                        DetailRow("标签", subjects.split("\n").joinToString("、"))
                    }
                    book.description?.let { DetailRow("简介", it) }
                    DetailRow("分组", book.groupName ?: "未分组")
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
                        if (readingDays > 0) "$readingDays 天" else "—",
                    )
                    DetailRow(
                        "平均速度",
                        averageCharsPerMinute(progress?.charsReadTotal ?: 0L, totalMillis)
                            .let { if (it > 0) "$it 字/分钟" else "—" },
                    )
                    DetailRow(
                        label = "编码",
                        value = book.encoding.ifBlank { "自动检测" },
                        // 非 TXT 格式固定 UTF-8（压平产物），不提供编码切换
                        onClick = if (book.format == BookFormat.TXT) {
                            { showEncodingPicker = true }
                        } else {
                            null
                        },
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(
                        onClick = {
                            viewModel.rebuildChapters(bookId)
                            Toast.makeText(context, "正在按最新规则重建目录", Toast.LENGTH_SHORT).show()
                        },
                    ) {
                        Text("重建目录")
                    }
                }
            }
        },
    )
    if (showEncodingPicker && book != null) {
        EncodingPickerDialog(
            currentEncoding = book.encoding,
            onSelect = { name ->
                showEncodingPicker = false
                viewModel.setEncoding(bookId, name)
                scope.launch {
                    delay(150)
                    refreshTick++
                }
            },
            onDismiss = { showEncodingPicker = false },
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String, onClick: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(vertical = 3.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.35f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = if (onClick != null) MaterialTheme.colorScheme.primary else Color.Unspecified,
            modifier = Modifier.weight(0.65f),
        )
    }
}

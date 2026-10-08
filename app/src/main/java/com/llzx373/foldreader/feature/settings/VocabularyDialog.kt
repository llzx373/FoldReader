package com.llzx373.foldreader.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 生词本对话框（M28）：查词卡片「收藏」的词条列表——按时间 / 按书两种分组，
 * 每条含释义、上下文例句、来源书与时间，可逐条删除。
 */
@Composable
fun VocabularyDialog(viewModel: SettingsViewModel, onDismiss: () -> Unit) {
    val items by viewModel.vocabulary.collectAsState()
    var groupByBook by remember { mutableStateOf(false) }
    val timeFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("生词本") },
        text = {
            Column {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    listOf("按时间", "按书").forEachIndexed { index, label ->
                        SegmentedButton(
                            selected = groupByBook == (index == 1),
                            onClick = { groupByBook = index == 1 },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = 2),
                        ) { Text(label) }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                if (items.isEmpty()) {
                    Text(
                        text = "还没有生词。阅读时长按选中 → 查词 → 收藏，词条会带上下文例句与来源书出现在这里。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp),
                    ) {
                        if (!groupByBook) {
                            items(items, key = { it.entry.id }) { item ->
                                VocabularyRow(
                                    item = item,
                                    timeText = timeFormat.format(Date(item.entry.createdAt)),
                                    onDelete = { viewModel.deleteWordEntry(item.entry.id) },
                                )
                            }
                        } else {
                            val grouped = items.groupBy { it.bookTitle }
                            grouped.forEach { (bookTitle, group) ->
                                item(key = "book:$bookTitle") {
                                    Text(
                                        text = "$bookTitle（${group.size}）",
                                        style = MaterialTheme.typography.titleSmall,
                                        modifier = Modifier.padding(vertical = 6.dp),
                                    )
                                }
                                items(group, key = { it.entry.id }) { item ->
                                    VocabularyRow(
                                        item = item,
                                        timeText = timeFormat.format(Date(item.entry.createdAt)),
                                        onDelete = { viewModel.deleteWordEntry(item.entry.id) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun VocabularyRow(
    item: SettingsViewModel.VocabularyItem,
    timeText: String,
    onDelete: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.entry.word,
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = item.entry.definition,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.entry.contextSentence.isNotBlank()) {
                Text(
                    text = item.entry.contextSentence,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = "${item.bookTitle} · ${item.entry.source} · $timeText",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = "删除")
        }
    }
    HorizontalDivider()
}

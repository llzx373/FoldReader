package com.llzx373.foldreader.feature.comic

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.llzx373.foldreader.feature.reader.ReaderColors
import com.llzx373.foldreader.feature.reader.pageLabelOf

/**
 * 页式文档的全文搜索（文本型 PDF）。
 *
 * 命中只给到「页 + 该页命中数 + 附近文字」：页内精确高亮需要页内命中坐标，
 * 而搜索这条路的用户动作是「找到那一页」，所以点一条直接跳页就够。
 */
@Composable
fun ComicSearchDialog(
    state: ComicSearchState,
    colors: ReaderColors,
    textSearchable: Boolean,
    onSearch: (String) -> Unit,
    onJump: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf(state.query) }
    val trimmed = remember(query) { query.trim() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("搜索") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("要查找的文字") },
                    )
                    TextButton(onClick = { onSearch(trimmed) }, enabled = trimmed.isNotEmpty()) {
                        Text("查找")
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                when {
                    !textSearchable -> Text(
                        text = "这本没有文字层，无法搜索",
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    state.running -> CircularProgressIndicator(modifier = Modifier.padding(16.dp))

                    state.query.isEmpty() -> Text(
                        text = "输入关键词后点「查找」，结果按页列出",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    state.hits.isEmpty() -> Text(
                        text = "没有找到「${state.query}」",
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    else -> {
                        Text(
                            text = "共 ${state.hits.size} 页命中",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.accent,
                        )
                        LazyColumn(modifier = Modifier.height(360.dp)) {
                            items(state.hits, key = { it.page }) { hit ->
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            onJump(hit.page)
                                            onDismiss()
                                        }
                                        .padding(vertical = 10.dp),
                                ) {
                                    Text(
                                        text = listOfNotNull(
                                            pageLabelOf(hit.page),
                                            "${hit.count} 处".takeIf { hit.count > 1 },
                                        ).joinToString(" · "),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = colors.accent,
                                    )
                                    if (hit.snippet.isNotEmpty()) {
                                        Text(
                                            text = hit.snippet,
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

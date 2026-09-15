package com.llzx373.foldreader.feature.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.llzx373.foldreader.core.format.Chapter
import com.llzx373.foldreader.core.format.SearchHit
import kotlinx.coroutines.delay

/** 书内搜索面板：输入防抖自动搜索，波浪进度，结果按章节分组流式渲染，关键词高亮。 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ReaderSearchDialog(
    state: ReaderViewModel.SearchState,
    chapters: List<Chapter>,
    colors: ReaderColors,
    onSearch: (String) -> Unit,
    onJump: (SearchHit) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf(state.query) }
    LaunchedEffect(query) {
        if (query.isBlank()) return@LaunchedEffect
        delay(400)
        onSearch(query)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
        title = { Text("书内搜索") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("输入关键词") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (state.running) {
                    val total = state.totalChars.coerceAtLeast(1)
                    LinearWavyProgressIndicator(
                        progress = {
                            (state.scannedChars.toFloat() / total).coerceIn(0f, 1f)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                    )
                    Text(
                        text = "已扫描 ${(state.scannedChars * 100 / total).coerceIn(0, 100)}%",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                when {
                    query.isBlank() -> Text(
                        text = "输入后自动搜索",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                    !state.running && state.hits.isEmpty() -> Text(
                        text = "没有找到「$query」",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                    else -> {
                        val groups = remember(state.hits, chapters) {
                            groupSearchHits(state.hits, chapters)
                        }
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(360.dp)
                                .padding(top = 8.dp),
                        ) {
                            groups.forEach { group ->
                                item(key = "header-${group.chapterIndex}") {
                                    Text(
                                        text = group.title,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = colors.accent,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                                    )
                                }
                                items(group.hits, key = { it.offset }) { hit ->
                                    SearchHitRow(
                                        hit = hit,
                                        colors = colors,
                                        onClick = { onJump(hit) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun SearchHitRow(
    hit: SearchHit,
    colors: ReaderColors,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
    ) {
        val context = buildAnnotatedString {
            val s = hit.matchStartInContext.coerceIn(0, hit.context.length)
            val e = (s + hit.matchLength).coerceIn(s, hit.context.length)
            append(hit.context.substring(0, s))
            withStyle(SpanStyle(background = colors.accent.copy(alpha = 0.45f), fontWeight = FontWeight.Bold)) {
                append(hit.context.substring(s, e))
            }
            append(hit.context.substring(e))
        }
        Text(
            text = context,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

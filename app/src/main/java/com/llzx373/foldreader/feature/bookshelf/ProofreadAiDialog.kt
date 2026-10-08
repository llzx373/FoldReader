package com.llzx373.foldreader.feature.bookshelf

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.llzx373.foldreader.FoldReaderApplication
import com.llzx373.foldreader.core.format.clean.CleanProfile

/**
 * M30「AI 校对」对话框：确认（首次）→ 按单位送校（进度）→ 结果列表 → 逐条勾选 → 生成清洗配方。
 *
 * 结果同时落成阅读器批注（只标不改）：关掉对话框也能在书里逐条看到。
 * 「生成清洗配方」只把勾选的确认项转成替换规则交回（[onApply]），由「智能整理」
 * 既有预览/确认链路执行——AI 不直接改正文。
 * ViewModel 按 bookId 键控，对话框中途关掉再开会回到当前状态。
 */
@Composable
fun ProofreadAiDialog(
    bookId: Long,
    onApply: (CleanProfile) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val container = remember(context) {
        (context.applicationContext as? FoldReaderApplication)?.container
    } ?: return
    val viewModel: ProofreadAiViewModel = viewModel(
        key = "proofread-ai-$bookId",
        factory = ProofreadAiViewModel.factory(container),
    )
    val state by viewModel.state.collectAsState()
    LaunchedEffect(bookId) { viewModel.start(bookId) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("AI 校对") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                when (val s = state) {
                    ProofreadAiViewModel.UiState.Idle,
                    ProofreadAiViewModel.UiState.Preparing,
                    -> ProofreadProgressLine("正在加载正文并切分校对单位…")

                    is ProofreadAiViewModel.UiState.AwaitConfirmation -> Column {
                        Text(
                            text = "首次使用 AI 校对前请确认数据外发：",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "· 数据去向：当前配置的服务商（${s.baseUrl}）",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = "· 数据范围：该书正文按章/块分批外发（约 ${s.totalChars} 字，" +
                                "估算 ≈${s.estimatedTokens} token）",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = "· 结果只写成批注（标出位置与建议），正文不会被改动。",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "确认后不再提示；每次外发都可在 设置 → AI 服务 → 外发历史 中审计。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    is ProofreadAiViewModel.UiState.Running ->
                        ProofreadProgressLine(
                            "正在校对 ${s.doneUnits}/${s.totalUnits}，已发现 ${s.found} 处…",
                        )

                    is ProofreadAiViewModel.UiState.Done -> Column {
                        Text(
                            text = buildString {
                                if (s.issues.isEmpty()) {
                                    append("未发现明确问题。")
                                } else {
                                    append("发现 ${s.issues.size} 处，已标为批注（正文未改动）。")
                                }
                                if (s.failedUnits > 0) append(" ${s.failedUnits} 个单位校对失败，可重试。")
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (s.confirmations.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "勾选确认的修改，生成清洗配方后在「智能整理」里预览并执行：",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                                items(s.confirmations.size) { index ->
                                    val c = s.confirmations[index]
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { viewModel.toggleConfirmation(index) },
                                    ) {
                                        Checkbox(
                                            checked = c.checked,
                                            onCheckedChange = { viewModel.toggleConfirmation(index) },
                                        )
                                        Text(
                                            text = "[${c.type.label}]「${c.original}」→「${c.suggestion}」" +
                                                if (c.occurrences > 1) "（${c.occurrences} 处）" else "",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    is ProofreadAiViewModel.UiState.Failure -> Text(
                        text = s.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            when (val s = state) {
                is ProofreadAiViewModel.UiState.AwaitConfirmation ->
                    TextButton(onClick = { viewModel.confirmAndRun() }) { Text("同意外发并开始") }

                is ProofreadAiViewModel.UiState.Done -> {
                    if (s.confirmations.any { it.checked }) {
                        TextButton(
                            onClick = { viewModel.buildRecipeProfile()?.let(onApply) },
                        ) { Text("生成清洗配方") }
                    }
                }

                else -> {}
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

/**
 * 「AI 校对」入口（M30）：书籍详情按钮。仅当书籍为 TXT 且 AI 已配置时渲染
 * （组件内部自查，未配置不显示而非置灰——未配置 AI 零入口）。
 */
@Composable
fun ProofreadAiEntry(bookId: Long, onApply: (CleanProfile) -> Unit) {
    val context = LocalContext.current
    val container = remember(context) {
        (context.applicationContext as? FoldReaderApplication)?.container
    } ?: return
    val visible by produceState(initialValue = false, bookId) {
        val book = container.bookshelfRepository.getBook(bookId)
        value = book != null &&
            book.format == com.llzx373.foldreader.core.data.db.BookFormat.TXT &&
            container.aiConfigured()
    }
    if (!visible) return
    var showDialog by remember { mutableStateOf(false) }
    TextButton(onClick = { showDialog = true }) { Text("AI 校对") }
    if (showDialog) {
        ProofreadAiDialog(
            bookId = bookId,
            onApply = { profile ->
                showDialog = false
                onApply(profile)
            },
            onDismiss = { showDialog = false },
        )
    }
}

@Composable
private fun ProofreadProgressLine(label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        Spacer(modifier = Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

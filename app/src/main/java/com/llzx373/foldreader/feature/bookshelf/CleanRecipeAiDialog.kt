package com.llzx373.foldreader.feature.bookshelf

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.llzx373.foldreader.FoldReaderApplication
import com.llzx373.foldreader.core.format.clean.CleanProfile

/**
 * M16「AI 清洗配方推荐」对话框：确认（首次）→ 生成 → 配方预览 → 「应用推荐」。
 *
 * AI 只推荐不执行：「应用推荐」把配方经 [onApply] 交回「智能整理」对话框，
 * 由**既有**的预览报告与确认按钮完成物化，执行路径与手动选档位完全相同。
 * ViewModel 按 bookId 键控，对话框中途关掉再开会回到当前状态（生成不被打断）。
 */
@Composable
fun CleanRecipeAiDialog(
    bookId: Long,
    onApply: (CleanProfile) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val container = remember(context) {
        (context.applicationContext as? FoldReaderApplication)?.container
    } ?: return
    val viewModel: CleanRecipeAiViewModel = viewModel(
        key = "clean-recipe-ai-$bookId",
        factory = CleanRecipeAiViewModel.factory(container),
    )
    val state by viewModel.state.collectAsState()
    LaunchedEffect(bookId) { viewModel.start(bookId) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("AI 推荐配方") },
        text = {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                when (val s = state) {
                    CleanRecipeAiViewModel.UiState.Idle,
                    CleanRecipeAiViewModel.UiState.Preparing,
                    -> CleanRecipeProgressLine("正在加载正文并采样头/中/尾片段…")

                    is CleanRecipeAiViewModel.UiState.AwaitConfirmation -> Column {
                        Text(
                            text = "首次使用 AI 推荐配方前请确认数据外发：",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "· 数据去向：当前配置的服务商（${s.baseUrl}）",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = "· 数据范围：该书开头 / 中段 / 结尾的采样片段（约 ${s.sampleChars} 字），" +
                                "不包含正文全文",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "确认后不再提示；每次外发都可在 设置 → AI 服务 → 外发历史 中审计。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    CleanRecipeAiViewModel.UiState.Generating ->
                        CleanRecipeProgressLine("AI 正在分析样本并给出配方建议…")

                    is CleanRecipeAiViewModel.UiState.Ready -> Column {
                        if (s.explanation.isNotBlank()) {
                            Text(
                                text = s.explanation,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        if (s.summaryLines.isEmpty()) {
                            Text(
                                text = "建议：标准档即可，无需额外开关。",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        } else {
                            Text(
                                text = "建议（以标准档为基线调整）：",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            s.summaryLines.forEach { line ->
                                Text(
                                    text = "· $line",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "应用后回到「智能整理」，先看预览报告再确认执行；AI 不会直接改动书籍。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    is CleanRecipeAiViewModel.UiState.Failure -> Text(
                        text = s.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            when (val s = state) {
                is CleanRecipeAiViewModel.UiState.AwaitConfirmation ->
                    TextButton(onClick = { viewModel.confirmAndGenerate() }) { Text("同意并生成") }

                is CleanRecipeAiViewModel.UiState.Failure ->
                    TextButton(onClick = { viewModel.retry() }) { Text("重试") }

                is CleanRecipeAiViewModel.UiState.Ready ->
                    TextButton(onClick = { onApply(s.profile) }) { Text("应用推荐") }

                else -> {}
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun CleanRecipeProgressLine(label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        Spacer(modifier = Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

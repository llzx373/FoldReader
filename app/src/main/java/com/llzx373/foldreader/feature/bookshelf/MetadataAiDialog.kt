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
import com.llzx373.foldreader.core.data.db.BookFormat
import com.llzx373.foldreader.core.metadata.BookMetaSources

/**
 * 「AI 补全信息」入口按钮（M17）：仅当书籍为 TXT 且 AI 服务已配置时渲染，
 * 其余情况完全占位为零（合规：未配置完成时全书 AI 入口不显示，不是置灰）。
 *
 * 只支持 TXT：EPUB/FB2 的元数据来自容器自带字段，PDF 有预热回填，
 * 只有 TXT 需要从开头文本里归纳。
 */
@Composable
fun MetadataAiEntry(bookId: Long, onApplied: () -> Unit = {}) {
    val context = LocalContext.current
    val container = remember(context) {
        (context.applicationContext as? FoldReaderApplication)?.container
    } ?: return
    // 一次性检查（produceState 缓存），不在重组路径上反复做 suspend 判据
    val visible by produceState(initialValue = false, bookId) {
        val book = container.bookshelfRepository.getBook(bookId)
        value = book?.format == BookFormat.TXT && container.aiConfigured()
    }
    if (!visible) return
    var showDialog by remember { mutableStateOf(false) }
    TextButton(onClick = { showDialog = true }) { Text("AI 补全信息") }
    if (showDialog) {
        MetadataAiDialog(
            bookId = bookId,
            onApplied = onApplied,
            onDismiss = { showDialog = false },
        )
    }
}

/**
 * M17 单书「AI 补全信息」对话框：确认（首次）→ 生成 → 完成汇报。
 *
 * 只填空字段、不覆盖已有内容；写上的字段在详情页标「AI 生成」。
 * ViewModel 按 bookId 键控，对话框中途关掉再开会回到当前状态（生成不被打断）。
 */
@Composable
fun MetadataAiDialog(bookId: Long, onApplied: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val container = remember(context) {
        (context.applicationContext as? FoldReaderApplication)?.container
    } ?: return
    val viewModel: MetadataAiViewModel = viewModel(
        key = "metadata-ai-$bookId",
        factory = MetadataAiViewModel.factory(container),
    )
    val state by viewModel.state.collectAsState()
    LaunchedEffect(bookId) { viewModel.start(bookId) }
    // 补全成功过就通知详情页刷新一次（Done 后用户还可能留着对话框看结果，先刷不亏）
    var applied by remember { mutableStateOf(false) }
    LaunchedEffect(state) {
        if (!applied && state is MetadataAiViewModel.UiState.Done &&
            (state as MetadataAiViewModel.UiState.Done).filledFields.isNotEmpty()
        ) {
            applied = true
            onApplied()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("AI 补全信息") },
        text = {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                when (val s = state) {
                    MetadataAiViewModel.UiState.Idle,
                    MetadataAiViewModel.UiState.Preparing,
                    -> MetadataProgressLine("正在采样开头文本…")

                    is MetadataAiViewModel.UiState.AwaitConfirmation ->
                        MetadataConfirmationText(s.baseUrl, s.scopeText)

                    MetadataAiViewModel.UiState.Generating ->
                        MetadataProgressLine("AI 正在归纳作者、简介与题材…")

                    is MetadataAiViewModel.UiState.Done -> Text(
                        text = if (s.filledFields.isEmpty()) {
                            "没有可补充的字段：已有内容不会被覆盖，你编辑过的字段也不会被改写。"
                        } else {
                            "已补全：${s.filledFields.joinToString("、") { metadataFieldLabel(it) }}。" +
                                "仅填写了空缺字段，详情页会标注「AI 生成」；你可以随时手动改正，" +
                                "改正后该字段不再被 AI 改写。"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    is MetadataAiViewModel.UiState.Failure -> Text(
                        text = s.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )

                    else -> Unit // 批量状态不会出现在单书对话框
                }
            }
        },
        confirmButton = {
            when (state) {
                is MetadataAiViewModel.UiState.AwaitConfirmation ->
                    TextButton(onClick = { viewModel.confirmAndGenerate() }) { Text("同意并生成") }

                is MetadataAiViewModel.UiState.Failure ->
                    TextButton(onClick = { viewModel.retry() }) { Text("重试") }

                is MetadataAiViewModel.UiState.Done ->
                    TextButton(onClick = onDismiss) { Text("完成") }

                else -> {}
            }
        },
        dismissButton = {
            if (state !is MetadataAiViewModel.UiState.Done) {
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

/**
 * M17 书架批量「AI 补全信息」对话框：确认（首次）→ 串行逐本补全（N/M + 当前书名）
 * → 汇总。进行中可取消；单本失败/跳过不中断队列。
 */
@Composable
fun BatchMetadataAiDialog(bookIds: List<Long>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val container = remember(context) {
        (context.applicationContext as? FoldReaderApplication)?.container
    } ?: return
    val viewModel: MetadataAiViewModel = viewModel(
        key = "metadata-ai-batch",
        factory = MetadataAiViewModel.factory(container),
    )
    val state by viewModel.state.collectAsState()
    LaunchedEffect(bookIds) { viewModel.startBatch(bookIds) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("AI 补全信息（${bookIds.size} 本）") },
        text = {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                when (val s = state) {
                    is MetadataAiViewModel.UiState.AwaitConfirmation ->
                        MetadataConfirmationText(s.baseUrl, s.scopeText)

                    is MetadataAiViewModel.UiState.BatchRunning -> Column {
                        MetadataProgressLine("正在补全 ${s.done}/${s.total}：${s.currentTitle}")
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "逐本进行，可随时取消；失败或无可补字段的书会跳过。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    is MetadataAiViewModel.UiState.BatchDone -> Text(
                        text = (if (s.cancelled) "已取消。" else "完成。") +
                            "已处理 ${s.processed} 本：补全 ${s.filled} 本，" +
                            "跳过 ${s.skipped} 本（无可补字段或非 TXT），失败 ${s.failed} 本。",
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    is MetadataAiViewModel.UiState.Failure -> Text(
                        text = s.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )

                    else -> MetadataProgressLine("正在准备…")
                }
            }
        },
        confirmButton = {
            when (state) {
                is MetadataAiViewModel.UiState.AwaitConfirmation ->
                    TextButton(onClick = { viewModel.confirmAndGenerate() }) { Text("同意并开始") }

                is MetadataAiViewModel.UiState.BatchDone,
                is MetadataAiViewModel.UiState.Failure,
                -> TextButton(onClick = onDismiss) { Text("关闭") }

                else -> {}
            }
        },
        dismissButton = {
            if (state is MetadataAiViewModel.UiState.BatchRunning) {
                TextButton(onClick = { viewModel.cancelBatch() }) { Text("取消") }
            }
        },
    )
}

/** 首次外发确认文案：数据去向 + 数据范围 + 审计指引（与 M15/M16 同款）。 */
@Composable
private fun MetadataConfirmationText(baseUrl: String, scopeText: String) {
    Column {
        Text(
            text = "首次使用 AI 补全信息前请确认数据外发：",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "· 数据去向：当前配置的服务商（$baseUrl）",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = "· 数据范围：$scopeText",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "确认后不再提示；每次外发都可在 设置 → AI 服务 → 外发历史 中审计。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MetadataProgressLine(label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        Spacer(modifier = Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

/** 字段 key → 展示名（Done 汇报用）。 */
internal fun metadataFieldLabel(field: String): String = when (field) {
    BookMetaSources.FIELD_AUTHOR -> "作者"
    BookMetaSources.FIELD_SYNOPSIS -> "简介"
    BookMetaSources.FIELD_GENRE -> "题材"
    else -> field
}

package com.llzx373.foldreader.feature.bookshelf

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.llzx373.foldreader.FoldReaderApplication
import com.llzx373.foldreader.core.data.db.BookFormat

/**
 * 「AI 识别章节」入口按钮（M15）：仅当书籍为 TXT 且 AI 服务已配置时渲染，
 * 其余情况完全占位为零（合规：未配置完成时全书 AI 入口不显示，不是置灰）。
 *
 * 只支持 TXT：按书自定义章节规则只在 TXT 解析管线合并（EPUB/FB2 的目录来自
 * 容器内目录文件，PDF 是页序号锚点），给它们生成规则没有生效路径。
 */
@Composable
fun ChapterRuleAiEntry(bookId: Long) {
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
    TextButton(onClick = { showDialog = true }) { Text("AI 识别章节") }
    if (showDialog) {
        ChapterRuleAiDialog(bookId = bookId, onDismiss = { showDialog = false })
    }
}

/** 阅读器侧入口：从容器取当前打开的书，其余与 [ChapterRuleAiEntry] 相同。 */
@Composable
fun ReaderChapterRuleAiEntry() {
    val context = LocalContext.current
    val container = remember(context) {
        (context.applicationContext as? FoldReaderApplication)?.container
    } ?: return
    val activeBookId by container.activeReaderBookId.collectAsState()
    activeBookId?.let { ChapterRuleAiEntry(it) }
}

/**
 * M15「AI 章节规则生成」对话框：确认（首次）→ 生成 → 候选预览 → 选定保存重建。
 *
 * 书架详情页与阅读器目录对话框共用；ViewModel 按 bookId 键控，
 * 对话框中途关掉再开会回到当前状态（生成不被打断）。
 */
@Composable
fun ChapterRuleAiDialog(bookId: Long, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val container = remember(context) {
        (context.applicationContext as? FoldReaderApplication)?.container
    } ?: return
    val viewModel: ChapterRuleAiViewModel = viewModel(
        key = "chapter-rule-ai-$bookId",
        factory = ChapterRuleAiViewModel.factory(container),
    )
    val state by viewModel.state.collectAsState()
    LaunchedEffect(bookId) { viewModel.start(bookId) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("AI 识别章节") },
        text = {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                when (val s = state) {
                    ChapterRuleAiViewModel.UiState.Idle,
                    ChapterRuleAiViewModel.UiState.Preparing,
                    -> ProgressLine("正在加载正文并采样疑似标题行…")

                    is ChapterRuleAiViewModel.UiState.AwaitConfirmation -> Column {
                        Text(
                            text = "首次使用 AI 识别章节前请确认数据外发：",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "· 数据去向：当前配置的服务商（${s.baseUrl}）",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = "· 数据范围：该书采样出的疑似章节标题行（约 ${s.sampleLines} 行），" +
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

                    ChapterRuleAiViewModel.UiState.Generating ->
                        ProgressLine("AI 正在归纳章节规则…")

                    is ChapterRuleAiViewModel.UiState.Preview -> Column {
                        Text(
                            text = "选定一条规则后会存为本书的自定义章节规则并重建目录：",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        s.candidates.forEachIndexed { index, candidate ->
                            if (index > 0) {
                                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                            }
                            CandidateBlock(
                                candidate = candidate,
                                onSelect = { viewModel.select(candidate) },
                            )
                        }
                    }

                    ChapterRuleAiViewModel.UiState.Saving ->
                        ProgressLine("正在保存规则并重建目录…")

                    is ChapterRuleAiViewModel.UiState.Done -> Text(
                        text = "已保存为本书章节规则并重建目录，共 ${s.chapterCount} 章。\n" +
                            "已打开的书籍重新打开后生效。",
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    is ChapterRuleAiViewModel.UiState.Failure -> Text(
                        text = s.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            when (state) {
                is ChapterRuleAiViewModel.UiState.AwaitConfirmation ->
                    TextButton(onClick = { viewModel.confirmAndGenerate() }) { Text("同意并生成") }

                is ChapterRuleAiViewModel.UiState.Failure ->
                    TextButton(onClick = { viewModel.retry() }) { Text("重试") }

                is ChapterRuleAiViewModel.UiState.Done ->
                    TextButton(onClick = onDismiss) { Text("完成") }

                else -> {}
            }
        },
        dismissButton = {
            if (state !is ChapterRuleAiViewModel.UiState.Done) {
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

@Composable
private fun CandidateBlock(
    candidate: ChapterRuleAiViewModel.CandidateUi,
    onSelect: () -> Unit,
) {
    val preview = candidate.preview
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(
            text = candidate.regex,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
        if (candidate.explanation.isNotBlank()) {
            Text(
                text = candidate.explanation,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = "试切 ${preview.chapterCount} 章",
            style = MaterialTheme.typography.labelSmall,
        )
        preview.anomalies.forEach { anomaly ->
            Text(
                text = "⚠ $anomaly",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        preview.titles.take(TITLE_PREVIEW_ROWS).forEach { title ->
            Text(
                text = "· $title",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        if (preview.titles.size > TITLE_PREVIEW_ROWS) {
            Text(
                text = "· ……",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onSelect, modifier = Modifier.align(Alignment.End)) {
            Text("采用此规则")
        }
    }
}

@Composable
private fun ProgressLine(label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        Spacer(modifier = Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

/** 每条候选在对话框里直接展示的标题行数（完整前 20 条在试切报告里，这里只给观感）。 */
private const val TITLE_PREVIEW_ROWS = 6

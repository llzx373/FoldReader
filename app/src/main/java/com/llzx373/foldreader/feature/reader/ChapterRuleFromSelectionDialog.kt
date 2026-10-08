package com.llzx373.foldreader.feature.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.llzx373.foldreader.FoldReaderApplication
import com.llzx373.foldreader.feature.bookshelf.CandidateBlock
import com.llzx373.foldreader.feature.bookshelf.ProgressLine

/**
 * 「选中行生成章节规则」对话框（阅读器选区操作条入口）：
 * 吸附选中点所在原始行 → 本地合成候选正则并逐条试切预览 → 选定保存重建。
 * 候选预览 UI 与 M15「AI 识别章节」共用（CandidateBlock / ProgressLine）。
 */
@Composable
fun ChapterRuleFromSelectionDialog(
    bookId: Long,
    anchorOffset: Long,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val container = remember(context) {
        (context.applicationContext as? FoldReaderApplication)?.container
    } ?: return
    val viewModel: ChapterRuleFromSelectionViewModel = viewModel(
        key = "chapter-rule-selection-$bookId-$anchorOffset",
        factory = ChapterRuleFromSelectionViewModel.factory(container),
    )
    val state by viewModel.state.collectAsState()
    LaunchedEffect(bookId, anchorOffset) { viewModel.start(bookId, anchorOffset) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("从选中行生成章节规则") },
        text = {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                when (val s = state) {
                    ChapterRuleFromSelectionViewModel.UiState.Idle,
                    ChapterRuleFromSelectionViewModel.UiState.Preparing,
                    -> ProgressLine("正在分析选中行并试切候选规则…")

                    is ChapterRuleFromSelectionViewModel.UiState.Preview -> Column {
                        Text(
                            text = "基于行：「${s.sourceLine}」",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "选定一条规则后会存为本书的自定义章节规则并重建目录：",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        s.candidates.forEachIndexed { index, candidate ->
                            if (index > 0) {
                                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                            }
                            CandidateBlock(
                                regex = candidate.regex,
                                explanation = candidate.explanation,
                                preview = candidate.preview,
                                onSelect = { viewModel.select(candidate) },
                            )
                        }
                    }

                    ChapterRuleFromSelectionViewModel.UiState.Saving ->
                        ProgressLine("正在保存规则并重建目录…")

                    is ChapterRuleFromSelectionViewModel.UiState.Done -> Text(
                        text = "已保存为本书章节规则并重建目录，共 ${s.chapterCount} 章。",
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    is ChapterRuleFromSelectionViewModel.UiState.Failure -> Text(
                        text = s.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            if (state is ChapterRuleFromSelectionViewModel.UiState.Done) {
                TextButton(onClick = onDismiss) { Text("完成") }
            }
        },
        dismissButton = {
            if (state !is ChapterRuleFromSelectionViewModel.UiState.Done) {
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

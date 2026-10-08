package com.llzx373.foldreader.feature.summary

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.llzx373.foldreader.core.data.db.BookOutlineEntity
import com.llzx373.foldreader.core.data.db.ChapterSummaryEntity

/**
 * 单章摘要查看（M29）：目录面板章行「摘要」入口点开。展示该章覆盖到的全部
 * done 摘要单位（大章可能切多块），按单位号升序。
 */
@Composable
fun ChapterSummaryDialog(
    chapterTitle: String,
    summaries: List<ChapterSummaryEntity>,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("摘要 · $chapterTitle") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                summaries.forEachIndexed { index, row ->
                    if (index > 0) Spacer(modifier = Modifier.height(12.dp))
                    if (summaries.size > 1) {
                        Text(
                            text = row.unitTitle,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    Text(text = row.summary, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

/**
 * 全书大纲视图（M29）：目录面板顶部「大纲」入口。展示已生成大纲；
 * 没有或摘要链变长（新 done 摘要未纳入）时给「生成 / 重新生成」。
 */
@Composable
fun BookOutlineDialog(
    outline: BookOutlineEntity?,
    doneSummaryCount: Int,
    running: Boolean,
    onGenerate: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("全书大纲") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                when {
                    outline != null -> {
                        Text(text = outline.outline, style = MaterialTheme.typography.bodyMedium)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "由 ${outline.summaryCount} 条章节摘要聚合生成",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (doneSummaryCount > outline.summaryCount) {
                            Text(
                                text = "现有 $doneSummaryCount 条摘要，可重新生成纳入新增部分",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                    doneSummaryCount > 0 -> Text(
                        text = "还没有大纲。现有 $doneSummaryCount 条章节摘要，可聚合生成全书大纲。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    else -> Text(
                        text = "还没有章节摘要。先在书籍详情页「预生成摘要」，或在目录章行逐章生成。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            if (doneSummaryCount > 0) {
                TextButton(onClick = onGenerate, enabled = !running) {
                    Text(
                        when {
                            running -> "生成中…"
                            outline != null -> "重新生成"
                            else -> "生成大纲"
                        },
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

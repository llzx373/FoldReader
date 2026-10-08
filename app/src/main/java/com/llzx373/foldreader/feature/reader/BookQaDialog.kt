package com.llzx373.foldreader.feature.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 问书对话页（M29，阅读菜单「问书」入口）。
 *
 * 提问页常驻明示外发范围（当前章 + 前序章节摘要链，发问给所配置的服务商）；
 * 首次外发走一次性确认卡片（AwaitConfirmation），确认后不再弹。
 * 回答流式展示，模型按提示词约定以《章节标题》标注来源章节；
 * 范围说明（scopeText）跟随每次回答展示，与台账口径一致。
 */
@Composable
fun BookQaDialog(
    state: ReaderViewModel.BookQaUi?,
    onAsk: (String) -> Unit,
    onConfirm: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    var question by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("问书") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = "外发范围：当前章正文 + 已生成的前序章节摘要（已读范围之外不外发），" +
                        "发给你配置的服务商回答。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = question,
                    onValueChange = { question = it },
                    label = { Text("就这本书提问") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                when (state) {
                    null -> Unit
                    is ReaderViewModel.BookQaUi.AwaitConfirmation -> {
                        Text(
                            text = "本次将外发：${state.scopeText}\n服务商：${state.baseUrl}\n" +
                                "首次提问确认后不再提示。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(
                            onClick = onConfirm,
                            modifier = Modifier.padding(top = 4.dp),
                        ) { Text("同意外发并提问") }
                    }
                    is ReaderViewModel.BookQaUi.Streaming -> {
                        Text(
                            text = "问：${state.question}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = state.answer.ifEmpty { "思考中…" },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "依据范围：${state.scopeText}" + if (state.running) "（回答中…）" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    is ReaderViewModel.BookQaUi.Error -> {
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        TextButton(onClick = onRetry) { Text("重试") }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onAsk(question)
                    question = ""
                },
                enabled = question.isNotBlank() &&
                    (state as? ReaderViewModel.BookQaUi.Streaming)?.running != true,
            ) { Text("提问") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

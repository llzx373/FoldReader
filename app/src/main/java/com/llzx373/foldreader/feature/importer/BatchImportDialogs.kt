package com.llzx373.foldreader.feature.importer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** 批量导入确认：可编辑分组名，展示发现数量与截断提示。 */
@Composable
fun BatchImportConfirmDialog(
    defaultGroupName: String,
    foundCount: Int,
    truncated: Boolean,
    onConfirm: (groupName: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var groupName by rememberSaveable { mutableStateOf(defaultGroupName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导入目录为分组") },
        text = {
            Column {
                Text(
                    text = if (foundCount > 0) {
                        "发现 $foundCount 本可导入书籍" +
                            if (truncated) "（已达上限，仅导入前 ${BatchImportUseCase.BATCH_IMPORT_LIMIT} 本）" else ""
                    } else {
                        "该目录下没有发现可导入的书籍"
                    },
                )
                if (foundCount > 0) {
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = groupName,
                        onValueChange = { groupName = it },
                        label = { Text("分组名（留空则不分组）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = foundCount > 0,
                onClick = { onConfirm(groupName) },
            ) {
                Text("开始导入")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

/** 批量导入全屏进度遮罩：N/M + 当前书名 + 取消。置于 Box 内使用。 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BatchImportProgressOverlay(
    done: Int,
    total: Int,
    currentName: String,
    onCancel: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            LoadingIndicator()
            Text(
                text = "正在导入 $done/$total",
                modifier = Modifier.padding(top = 16.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
            if (currentName.isNotEmpty()) {
                Text(
                    text = currentName,
                    modifier = Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(
                onClick = onCancel,
                modifier = Modifier.padding(top = 16.dp),
            ) {
                Text("取消")
            }
        }
    }
}

/** 批量导入结果汇总：成功/重复/失败及原因。 */
@Composable
fun BatchImportSummaryDialog(
    result: BatchImportUseCase.BatchResult,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (result.cancelled) "导入已取消" else "导入完成") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text("成功导入 ${result.imported.size} 本")
                result.groupName?.let { Text("已加入分组「$it」") }
                if (result.duplicates.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("重复跳过 ${result.duplicates.size} 本（保持原分组不变）：")
                    result.duplicates.take(3).forEach {
                        Text(
                            text = "· ${it.name}：${it.reason}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (result.duplicates.size > 3) {
                        Text(
                            text = "· 等 ${result.duplicates.size} 本",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (result.failures.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("失败 ${result.failures.size} 本：")
                    result.failures.take(3).forEach {
                        Text(
                            text = "· ${it.name}：${it.reason}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (result.failures.size > 3) {
                        Text(
                            text = "· 等 ${result.failures.size} 本",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("确定")
            }
        },
    )
}

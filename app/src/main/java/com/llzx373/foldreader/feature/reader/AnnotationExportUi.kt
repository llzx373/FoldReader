package com.llzx373.foldreader.feature.reader

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.llzx373.foldreader.core.export.AnnotationExport
import com.llzx373.foldreader.core.export.AnnotationExportOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 批注导出（M27）的选项对话框：含 / 不含笔记、含 / 不含颜色标记。
 * 书架批量导出与单书导出共用同一组选项。
 */
@Composable
fun AnnotationExportOptionsDialog(
    onConfirm: (AnnotationExportOptions) -> Unit,
    onDismiss: () -> Unit,
) {
    var includeNotes by remember { mutableStateOf(true) }
    var includeColors by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导出批注") },
        text = {
            Column {
                Text(
                    text = "导出为 Markdown，按章节分组（漫画 / PDF 按页）。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { includeNotes = !includeNotes },
                ) {
                    Checkbox(checked = includeNotes, onCheckedChange = { includeNotes = it })
                    Text("包含笔记")
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { includeColors = !includeColors },
                ) {
                    Checkbox(checked = includeColors, onCheckedChange = { includeColors = it })
                    Text("包含颜色标记")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        AnnotationExportOptions(
                            includeNotes = includeNotes,
                            includeColors = includeColors,
                        ),
                    )
                },
            ) { Text("导出") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 批注导出入口句柄：[open] 打开选项对话框。 */
class AnnotationExportHandle(val open: () -> Unit)

/**
 * 单书批注导出宿主（M27）：选项对话框 → 渲染 → 系统「另存为」写入。
 *
 * [render] 返回 null 表示这本书没有批注——提示而不落一个空文件。
 * 文本与漫画 / PDF 两个阅读器、书籍详情页三处入口共用。
 */
@Composable
fun rememberAnnotationExport(
    render: suspend (AnnotationExportOptions) -> AnnotationExport.Rendered?,
): AnnotationExportHandle {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var optionsVisible by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<AnnotationExport.Rendered?>(null) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/markdown"),
    ) { target ->
        val export = pending
        pending = null
        if (target != null && export != null) {
            scope.launch {
                val message = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(target)
                            ?.bufferedWriter(Charsets.UTF_8)?.use { it.write(export.markdown) }
                            ?: error("无法写入所选位置")
                    }.fold(
                        onSuccess = { "已导出 ${export.count} 条批注" },
                        onFailure = { "导出失败：${it.message ?: "未知错误"}" },
                    )
                }
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        }
    }
    if (optionsVisible) {
        AnnotationExportOptionsDialog(
            onConfirm = { options ->
                optionsVisible = false
                scope.launch {
                    val export = render(options)
                    if (export == null) {
                        Toast.makeText(context, "本书还没有批注可导出", Toast.LENGTH_SHORT).show()
                    } else {
                        pending = export
                        launcher.launch(export.fileName)
                    }
                }
            },
            onDismiss = { optionsVisible = false },
        )
    }
    return remember { AnnotationExportHandle { optionsVisible = true } }
}

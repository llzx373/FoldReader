package com.llzx373.foldreader.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.llzx373.foldreader.core.ocr.OcrModelSpec

/**
 * 「模型管理」对话框（M21，R7/R8/R9）：
 * 清单逐项展示 用途/文件名/大小/就绪状态，操作只有 导入（SAF）与 删除；
 * 下载地址只读可复制——应用不联网下载模型（v2.6 网络政策），用户自行下载后导入，
 * 导入时按清单校验文件名与全量 SHA-256，错误文件明确报错。
 */
@Composable
fun ModelManagerDialog(
    status: List<Pair<OcrModelSpec, Boolean>>,
    onImport: (OcrModelSpec) -> Unit,
    onDelete: (OcrModelSpec) -> Unit,
    onCopyUrl: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("模型管理") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "模型不进安装包：请先从下方地址自行下载（文件名需保持一致），再点「导入」；" +
                        "导入时校验 SHA-256，错误文件会被拒绝。OCR 全程离线运行。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(modifier = Modifier.height(8.dp))
                status.forEach { (spec, ready) ->
                    HorizontalDivider()
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Text(spec.purpose, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${spec.fileName}（${"%.1f".format(spec.sizeBytes / 1024f / 1024f)} MB）",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                        Text(
                            if (ready) "已导入" else "未导入",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (ready) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        Text(
                            "下载：${spec.officialUrl}" + (spec.mirrorUrl?.let { "\n镜像：$it" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { onCopyUrl(spec.officialUrl) },
                        )
                        Row(modifier = Modifier.align(Alignment.End)) {
                            if (ready) {
                                TextButton(onClick = { onDelete(spec) }) { Text("删除") }
                            }
                            TextButton(onClick = { onImport(spec) }) {
                                Text(if (ready) "重新导入" else "导入")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

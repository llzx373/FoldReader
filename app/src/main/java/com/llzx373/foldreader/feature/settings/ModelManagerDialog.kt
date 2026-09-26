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
import com.llzx373.foldreader.BuildConfig
import com.llzx373.foldreader.core.ai.android.ModelManager.ModelSlot
import com.llzx373.foldreader.core.ocr.OcrModelSpec

/**
 * 「模型管理」对话框（M21/M24，R7/R8/R9）：
 * 清单逐项展示 用途/文件名/大小/就绪状态。官方模型：full 版安装包自带（首启自动铺底），
 * lite 版需自行下载 → SAF 导入 → 校验 SHA-256；应用不联网下载模型（v2.6 网络政策）。
 * 自定义模型（M24）：私有微调/其他来源的 .onnx 可导入任意槽位，不做清单校验、
 * 优先生效——rec 槽位注意识别词典仍是内置的，自定义 rec 改了字符集输出即乱码。
 */
@Composable
fun ModelManagerDialog(
    slots: List<ModelSlot>,
    onImport: (OcrModelSpec) -> Unit,
    onImportCustom: (OcrModelSpec) -> Unit,
    onDelete: (OcrModelSpec) -> Unit,
    onDeleteCustom: (OcrModelSpec) -> Unit,
    onCopyUrl: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("模型管理") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    if (BuildConfig.BUNDLED_MODELS) {
                        "本版本安装包已自带全部官方模型，首次启动自动就绪，无需下载。" +
                            "也可以导入自己的 .onnx 覆盖任意槽位（自定义模型不做校验、优先生效）。"
                    } else {
                        "模型不进安装包：请先从下方地址自行下载（文件名需保持一致），再点「导入」；" +
                            "导入时校验 SHA-256，错误文件会被拒绝。也可以导入自己的 .onnx 覆盖任意槽位" +
                            "（自定义模型不做校验、优先生效）。OCR 全程离线运行。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(modifier = Modifier.height(8.dp))
                slots.forEach { slot ->
                    val spec = slot.spec
                    HorizontalDivider()
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Text(spec.purpose, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${spec.fileName}（${"%.1f".format(spec.sizeBytes / 1024f / 1024f)} MB）",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                        Text(
                            when {
                                slot.custom && slot.official -> "已导入 + 自定义（自定义优先生效）"
                                slot.custom -> "自定义模型（未校验，生效中）"
                                slot.official -> "已导入"
                                else -> "未导入"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (slot.ready) {
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
                            if (slot.custom) {
                                TextButton(onClick = { onDeleteCustom(spec) }) { Text("删自定义") }
                            }
                            if (slot.official) {
                                TextButton(onClick = { onDelete(spec) }) { Text("删除") }
                            }
                            TextButton(onClick = { onImportCustom(spec) }) { Text("导入自定义") }
                            TextButton(onClick = { onImport(spec) }) {
                                Text(if (slot.official) "重新导入" else "导入")
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

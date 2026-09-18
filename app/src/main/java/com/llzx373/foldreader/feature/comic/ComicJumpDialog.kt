package com.llzx373.foldreader.feature.comic

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/**
 * 跳到指定页。
 *
 * 页数多的文档拖进度条很难点准（几百页时 1% 就是好几页），所以给一个直接输页码的入口。
 * 输入按 1 基显示（用户心里的「第 3 页」），越界时夹到有效范围而不是报错。
 */
@Composable
fun ComicJumpDialog(
    pageCount: Int,
    currentPage: Int,
    onJump: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable(currentPage) { mutableStateOf((currentPage + 1).toString()) }
    val parsed = remember(text) { text.trim().toIntOrNull() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("跳到指定页") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter { ch -> ch.isDigit() }.take(7) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Text(
                    text = "共 $pageCount 页",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = parsed != null,
                onClick = {
                    parsed?.let { onJump((it - 1).coerceIn(0, (pageCount - 1).coerceAtLeast(0))) }
                    onDismiss()
                },
            ) { Text("跳转") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

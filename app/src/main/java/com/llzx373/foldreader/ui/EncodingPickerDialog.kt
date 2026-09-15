package com.llzx373.foldreader.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

val ENCODING_PRESETS: List<String> = listOf("UTF-8", "UTF-16LE", "UTF-16BE", "GBK", "GB18030", "Big5")

/** [currentEncoding] 空串表示自动检测；[onSelect] 传 null 表示选择自动检测。 */
@Composable
fun EncodingPickerDialog(
    currentEncoding: String,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
        title = { Text("选择编码") },
        text = {
            Column {
                (listOf<String?>(null) + ENCODING_PRESETS).forEach { name ->
                    val selected = if (name == null) currentEncoding.isBlank() else currentEncoding == name
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(name) }
                            .padding(vertical = 4.dp),
                    ) {
                        RadioButton(selected = selected, onClick = { onSelect(name) })
                        Text(
                            text = name ?: "自动检测",
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        },
    )
}

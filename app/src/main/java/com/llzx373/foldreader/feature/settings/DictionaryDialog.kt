package com.llzx373.foldreader.feature.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * 词典管理对话框（M28）：已导入词典列表（书名/词条数/删除）+ SAF 目录导入。
 * 词典是三件套（同名 .ifo + .idx + .dict/.dict.dz），所以导入走「选目录」而不是单文件。
 */
@Composable
fun DictionaryDialog(viewModel: SettingsViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val dictionaries by viewModel.dictionaries.collectAsState()
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            viewModel.importDictionaries(uri) { message ->
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("词典管理") },
        text = {
            Column {
                Text(
                    text = "支持 StarDict 格式：把同名的 .ifo + .idx + .dict（或 .dict.dz）" +
                        "三件套放在同一目录，导入时选这个目录。查词全程离线；MDict 暂不支持。",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (dictionaries.isEmpty()) {
                    Text(
                        text = "还没有导入词典。未导入词典时，阅读器「查词」回落到 AI 解释（需已配置）。",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp),
                    ) {
                        items(dictionaries, key = { it.id }) { dict ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(dict.bookName, style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        "${dict.wordCount} 词条",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                IconButton(onClick = { viewModel.deleteDictionary(dict.id) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "删除")
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { importLauncher.launch(null) }) { Text("导入词典目录") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

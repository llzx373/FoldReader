package com.llzx373.foldreader.feature.bookshelf

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
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
import com.llzx373.foldreader.core.data.db.BookEntity
import com.llzx373.foldreader.core.metadata.GenreTags

/**
 * M17「编辑信息」对话框：手动改正作者 / 简介 / 题材标签。
 *
 * 保存时**值发生变化的字段**会被打上用户标记（`BookMetaSources.planUserEdit`），
 * 此后 AI 补全不再改写该字段（含主动清空——清掉的字段 AI 也不许回填）。
 */
@Composable
fun EditBookMetadataDialog(
    book: BookEntity,
    onSave: (author: String?, synopsis: String?, genreTag: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var author by remember { mutableStateOf(book.author.orEmpty()) }
    var synopsis by remember { mutableStateOf(book.description.orEmpty()) }
    var genreTag by remember { mutableStateOf(book.genreTag.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑信息") },
        text = {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = author,
                    onValueChange = { author = it },
                    label = { Text("作者") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = synopsis,
                    onValueChange = { synopsis = it },
                    label = { Text("简介") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = genreTag,
                    onValueChange = { genreTag = it },
                    label = { Text("题材标签") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    GenreTags.ALL.forEach { tag ->
                        FilterChip(
                            selected = genreTag == tag,
                            onClick = { genreTag = if (genreTag == tag) "" else tag },
                            label = { Text(tag) },
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "改动过的字段将标记为你所编辑，AI 补全不会再改写它们。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(author, synopsis, genreTag) }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

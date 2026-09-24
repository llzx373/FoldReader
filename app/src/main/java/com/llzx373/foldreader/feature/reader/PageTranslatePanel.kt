package com.llzx373.foldreader.feature.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.llzx373.foldreader.core.ai.AiTargetLang
import com.llzx373.foldreader.core.ai.prompt.SelectionTranslatePrompt
import com.llzx373.foldreader.core.ai.prompt.UnitTranslatePrompt

/** 内置系统提示词按 [lang] 实例化后的当次默认值（确认页输入框的初始内容）。 */
private fun defaultPagePrompt(lang: AiTargetLang): String =
    UnitTranslatePrompt.SYSTEM_PROMPT.replace(
        UnitTranslatePrompt.TARGET_LANG_PLACEHOLDER,
        SelectionTranslatePrompt.displayName(lang),
    )

/**
 * M19「翻译本页」确认页：范围与估算、目标语言、当次临时提示词（R11 不写回内置模板）。
 *
 * 提示词未手动改过时跟随语言切换重新实例化；一旦手动编辑即锁定。
 * 与内置模板相同（去首尾空白比较）时回调传 null，走默认注入路径。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageTranslateConfirmDialog(
    charCount: Int,
    initialLang: AiTargetLang,
    colors: ReaderColors,
    onStart: (AiTargetLang, String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var lang by remember { mutableStateOf(initialLang) }
    var prompt by remember { mutableStateOf(defaultPagePrompt(initialLang)) }
    var promptDirty by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.background,
        title = { Text("翻译本页") },
        text = {
            Column {
                Text(
                    text = "范围：当前页约 $charCount 字，估算 ≈${charCount / 2} token",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(10.dp))
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    AiTargetLang.entries.forEachIndexed { index, option ->
                        SegmentedButton(
                            selected = lang == option,
                            onClick = {
                                lang = option
                                if (!promptDirty) prompt = defaultPagePrompt(option)
                            },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = AiTargetLang.entries.size,
                            ),
                        ) {
                            Text(
                                text = translateLangLabel(option),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = prompt,
                    onValueChange = {
                        prompt = it
                        promptDirty = true
                    },
                    label = { Text("当次提示词（仅本次生效）") },
                    textStyle = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 160.dp),
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "本页译文仅本次展示，不写回书本；整章翻译请用「翻译本章」。",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.text.copy(alpha = 0.6f),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val default = defaultPagePrompt(lang)
                onStart(lang, if (prompt.trim() == default.trim()) null else prompt)
            }) { Text("开始翻译") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/**
 * M19「翻译本页」对照面板：原文/译文逐段对照，流式追加（不落盘）。
 *
 * [state] 为 confirming=false 的页翻译状态；段对按已闭合译文流式增长，
 * 尚未译出的段落只显示原文占位。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PageTranslatePanel(
    state: ReaderViewModel.PageTranslateState,
    colors: ReaderColors,
    onRetry: () -> Unit,
    onClose: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = colors.background,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "翻译本页 · ${translateLangLabel(state.lang)}",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onClose) { Text("关闭") }
            }
        },
        text = {
            Column {
                if (state.running) {
                    LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(6.dp))
                }
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    items(state.sourceParagraphs.size) { index ->
                        Text(
                            text = state.sourceParagraphs[index],
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.text.copy(alpha = 0.6f),
                        )
                        state.translated.getOrNull(index)?.let { translated ->
                            Text(
                                text = translated,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(colors.text.copy(alpha = 0.05f))
                                    .padding(6.dp),
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                    }
                }
                state.error?.let { error ->
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            if (state.error != null) {
                TextButton(onClick = onRetry) { Text("重试") }
            }
        },
    )
}

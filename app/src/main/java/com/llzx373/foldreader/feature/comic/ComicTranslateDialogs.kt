package com.llzx373.foldreader.feature.comic

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
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
import com.llzx373.foldreader.core.ai.prompt.ComicTranslatePrompt
import com.llzx373.foldreader.core.ai.prompt.SelectionTranslatePrompt
import com.llzx373.foldreader.core.ocr.OcrBubble
import com.llzx373.foldreader.feature.reader.ReaderColors
import com.llzx373.foldreader.feature.reader.translateLangLabel

/** 内置系统提示词按 [lang] 实例化后的当次默认值（确认页输入框的初始内容）。 */
private fun defaultComicPrompt(lang: AiTargetLang): String =
    ComicTranslatePrompt.SYSTEM_PROMPT.replace(
        ComicTranslatePrompt.TARGET_LANG_PLACEHOLDER,
        SelectionTranslatePrompt.displayName(lang),
    )

/**
 * M22 漫画翻译「首次外发确认」（一次性）：说清发什么、发给谁，确认后置偏好位不再弹。
 * 与章节规则/清洗配方等 AI 功能同一口径（v2.6 合规清单）。
 */
@Composable
fun ComicTranslateFirstSendDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("漫画翻译") },
        text = {
            Text(
                "漫画翻译会把页面里识别出的对白文字发送给你配置的 AI 服务进行翻译，" +
                    "译文只保存在本机。气泡识别（OCR）完全在本地离线进行，不会上传图片。\n\n" +
                    "此确认只出现一次。",
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("知道了，开始翻译") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/**
 * M22「翻译本页」确认页：范围与估算、目标语言、当次临时提示词（R11 不写回内置模板）。
 * 照文本阅读器 PageTranslateConfirmDialog 的同一模式：提示词未手动改过时跟随语言切换，
 * 与默认相同（去首尾空白比较）时回调传 null。
 *
 * [bubbleInfo] = (气泡数, 原文字数)，null = 尚未识别过（首次翻译会先离线跑气泡识别）；
 * [infoLoaded] 气泡信息是否已读取完（读取中禁用开始）。 [translating] 进行中转圈；
 * [error] 非空显示失败与重试。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ComicPageTranslateConfirmDialog(
    bubbleInfo: Pair<Int, Int>?,
    infoLoaded: Boolean,
    initialLang: AiTargetLang,
    translating: Boolean,
    error: String?,
    colors: ReaderColors,
    onStart: (AiTargetLang, String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var lang by remember { mutableStateOf(initialLang) }
    var prompt by remember { mutableStateOf(defaultComicPrompt(initialLang)) }
    var promptDirty by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!translating) onDismiss() },
        containerColor = colors.background,
        title = { Text("翻译本页") },
        text = {
            Column {
                Text(
                    text = when {
                        !infoLoaded -> "正在读取本页气泡信息…"
                        bubbleInfo == null -> "范围：当前页（首次翻译会先离线识别气泡，识别不上传图片）"
                        bubbleInfo.first == 0 -> "本页未识别到文字气泡"
                        else -> "范围：当前页 ${bubbleInfo.first} 个气泡约 ${bubbleInfo.second} 字，" +
                            "估算 ≈${bubbleInfo.second / 2} token"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (translating) {
                    Spacer(modifier = Modifier.height(10.dp))
                    LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Spacer(modifier = Modifier.height(10.dp))
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    AiTargetLang.entries.forEachIndexed { index, option ->
                        SegmentedButton(
                            selected = lang == option,
                            onClick = {
                                lang = option
                                if (!promptDirty) prompt = defaultComicPrompt(option)
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
                error?.let {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val default = defaultComicPrompt(lang)
                    onStart(lang, if (prompt.trim() == default.trim()) null else prompt)
                },
                enabled = infoLoaded && !translating && (bubbleInfo == null || bubbleInfo.first > 0),
            ) { Text(if (error != null) "重试" else "开始翻译") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !translating) { Text("取消") }
        },
    )
}

/**
 * M22「翻译整卷」确认页：范围（总页数/断点续译跳过数）、目标语言与成本口径说明。
 * 与 M20 全书翻译同一合规要求：批量翻译逐书确认；token 无法预估的如实说明
 * （气泡文字要逐页离线识别后才知道总量），不编数字。
 */
@Composable
fun ComicVolumeTranslateConfirmDialog(
    totalPages: Int,
    donePages: Int,
    lang: AiTargetLang,
    colors: ReaderColors,
    onStart: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.background,
        title = { Text("翻译整卷") },
        text = {
            Column {
                Text(
                    text = buildString {
                        append("范围：全书 $totalPages 页")
                        if (donePages > 0) append("（已译 $donePages 页将自动跳过，断点续译）")
                        append("，目标语言：${translateLangLabel(lang)}")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "气泡识别（OCR）在本地离线逐页进行，不上传图片；" +
                        "仅识别出的对白文字外发给所配置的 AI 服务翻译，按字符计费" +
                        "（估算口径 ≈字数/2 token，总量随识别结果确定）。" +
                        "翻译在后台队列逐页进行，可随时在通知里暂停；失败的页会记为失败可续译。",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.text.copy(alpha = 0.6f),
                )
            }
        },
        confirmButton = { TextButton(onClick = onStart) { Text("开始翻译") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/**
 * M22 气泡对照面板（视角③）：当前页气泡的原文/译文逐条对照；
 * 点一条高亮页面上对应的气泡（覆盖层画边框），关闭时清除高亮。
 * [pairs] null = 加载中；空列表 = 该页无气泡。
 */
@Composable
fun ComicBubbleComparePanel(
    pairs: List<Pair<OcrBubble, String?>>?,
    lang: AiTargetLang,
    colors: ReaderColors,
    onHighlight: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.background,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "气泡对照 · ${translateLangLabel(lang)}",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        },
        text = {
            when {
                pairs == null -> Text("加载中…", style = MaterialTheme.typography.bodyMedium)
                pairs.isEmpty() -> Text(
                    "本页没有识别到气泡。先在菜单里「翻译本页」，识别与翻译完成后这里会列出对照。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                else -> LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    items(pairs.size) { index ->
                        val (bubble, translated) = pairs[index]
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onHighlight(bubble.index) },
                        ) {
                            Text(
                                text = "${index + 1}. ${bubble.text}",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.text.copy(alpha = 0.6f),
                            )
                            Text(
                                text = translated ?: "（未译出）",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (translated != null) {
                                    colors.text
                                } else {
                                    colors.text.copy(alpha = 0.4f)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(colors.text.copy(alpha = 0.05f))
                                    .padding(6.dp),
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                    }
                }
            }
        },
        confirmButton = {},
    )
}

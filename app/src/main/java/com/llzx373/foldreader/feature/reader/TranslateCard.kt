package com.llzx373.foldreader.feature.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.llzx373.foldreader.core.ai.AiTargetLang
import com.llzx373.foldreader.core.ai.prompt.SelectionTranslatePrompt

/**
 * M18 选中即译卡片状态；null 表示卡片关闭（状态流挂在 ReaderViewModel 上）。
 *
 * 确认（首次，[AwaitConfirmation]）→ 请求中（[Loading]）→ 流式译文（[Streaming]）
 * → 失败（[Error]，可重试）。语言临时切换走 [Streaming]/[Error] 上的重译。
 */
sealed interface TranslateCardUi {
    val lang: AiTargetLang

    /** 首次外发前的一次性确认：明示数据去向（服务商地址）与数据范围（选中文字）。 */
    data class AwaitConfirmation(
        val baseUrl: String,
        override val lang: AiTargetLang,
    ) : TranslateCardUi

    /** 请求已发出，等待首个增量。 */
    data class Loading(override val lang: AiTargetLang) : TranslateCardUi

    /** 流式译文展示；[running] = true 时仍在追加。 */
    data class Streaming(
        val source: String,
        val translatedSoFar: String,
        override val lang: AiTargetLang,
        val running: Boolean,
    ) : TranslateCardUi

    data class Error(val message: String, override val lang: AiTargetLang) : TranslateCardUi
}

/**
 * 选中即译底部卡片：源文摘抄 + 目标语言临时切换（不写回设置）+ 流式译文 + 存为批注。
 * 与 ReaderMenuPanel 同构的底部 Surface，由 ReaderScreen 以 AnimatedVisibility 挂载。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TranslateCard(
    state: TranslateCardUi,
    colors: ReaderColors,
    onSelectLang: (AiTargetLang) -> Unit,
    onConfirm: () -> Unit,
    onSaveAsNote: () -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = colors.background,
        contentColor = colors.text,
        tonalElevation = 4.dp,
        shadowElevation = 8.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "选中即译",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onClose) { Text("关闭") }
            }
            (state as? TranslateCardUi.Streaming)?.let { streaming ->
                Text(
                    text = streaming.source,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.text.copy(alpha = 0.6f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(6.dp))
            }
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                AiTargetLang.entries.forEachIndexed { index, lang ->
                    SegmentedButton(
                        selected = state.lang == lang,
                        onClick = { onSelectLang(lang) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = AiTargetLang.entries.size,
                        ),
                    ) {
                        Text(
                            text = translateLangLabel(lang),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            when (state) {
                is TranslateCardUi.AwaitConfirmation -> Column {
                    Text(
                        text = "首次使用 AI 翻译前请确认数据外发：",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "· 数据去向：当前配置的服务商（${state.baseUrl}）",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "· 数据范围：当前选中的文字，不包含整本书内容",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "确认后不再提示；每次外发都可在 设置 → AI 服务 → 外发历史 中审计。",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.text.copy(alpha = 0.6f),
                    )
                }

                is TranslateCardUi.Loading -> Column {
                    LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        text = "正在请求翻译…",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }

                is TranslateCardUi.Streaming -> {
                    if (state.running) {
                        LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                    val scrollState = rememberScrollState()
                    // 流式增量到达时跟到底部，用户上滑回看期间不强拽（maxValue 变化才触发）
                    LaunchedEffect(state.translatedSoFar.length) {
                        scrollState.scrollTo(scrollState.maxValue)
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp, max = 220.dp)
                            .verticalScroll(scrollState),
                    ) {
                        Text(
                            text = state.translatedSoFar,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                is TranslateCardUi.Error -> Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }
            Row(modifier = Modifier.align(Alignment.End)) {
                when (state) {
                    is TranslateCardUi.AwaitConfirmation ->
                        TextButton(onClick = onConfirm) { Text("同意并翻译") }
                    is TranslateCardUi.Streaming ->
                        TextButton(
                            onClick = onSaveAsNote,
                            enabled = state.translatedSoFar.isNotBlank(),
                        ) { Text("存为批注") }
                    is TranslateCardUi.Error ->
                        TextButton(onClick = onRetry) { Text("重试") }
                    is TranslateCardUi.Loading -> {}
                }
            }
        }
    }
}

/** 分段按钮上的短标签；提示词与批注里用 [SelectionTranslatePrompt.displayName] 全称。 */
internal fun translateLangLabel(lang: AiTargetLang): String = when (lang) {
    AiTargetLang.ZH_HANS -> "简中"
    AiTargetLang.ZH_HANT -> "繁中"
    AiTargetLang.EN -> "EN"
    AiTargetLang.JA -> "日"
}

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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.llzx373.foldreader.core.ai.AiTargetLang

/**
 * M28 查词卡片状态；null 表示卡片关闭（状态流挂在 ReaderViewModel 上）。
 *
 * 本地词典命中（[LocalHit]，零网络零确认）→ 直接展示；未命中（[Miss]）且已配置 AI
 * 时可点「AI 解释」走一次性确认（[AwaitConfirmation]）→ 流式解释（[AiStreaming]）。
 */
sealed interface DictCardUi {
    val word: String

    /** 本地词典命中；[dictName] 为命中的词典名。 */
    data class LocalHit(
        override val word: String,
        val definition: String,
        val dictName: String,
    ) : DictCardUi

    /** 本地未收录；[aiAvailable] = true 时提供「AI 解释」回落入口。 */
    data class Miss(override val word: String, val aiAvailable: Boolean) : DictCardUi

    /** AI 解释首次外发前的一次性确认：明示数据去向与范围。 */
    data class AwaitConfirmation(
        override val word: String,
        val baseUrl: String,
        val lang: AiTargetLang,
    ) : DictCardUi

    /** AI 解释请求已发出，等待首个增量。 */
    data class Loading(override val word: String, val lang: AiTargetLang) : DictCardUi

    /** AI 解释流式展示；[running] = true 时仍在追加。 */
    data class AiStreaming(
        override val word: String,
        val text: String,
        val lang: AiTargetLang,
        val running: Boolean,
    ) : DictCardUi

    data class Error(val message: String, override val word: String) : DictCardUi
}

/**
 * 查词底部卡片：词条 + 本地释义（来源词典）/ 未收录提示 + AI 回落 / 流式解释。
 * 与 TranslateCard 同构的底部 Surface，由 ReaderScreen 以 AnimatedVisibility 挂载。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DictCard(
    state: DictCardUi,
    colors: ReaderColors,
    onAiExplain: () -> Unit,
    onConfirm: () -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit,
    /** 「收藏」入生词本；null = 不出现（生词本未挂载时）。 */
    onSaveWord: (() -> Unit)? = null,
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
                    text = "查词",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onClose) { Text("关闭") }
            }
            Text(text = state.word, style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(6.dp))
            when (state) {
                is DictCardUi.LocalHit -> {
                    Text(
                        text = state.dictName,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.text.copy(alpha = 0.6f),
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 32.dp, max = 220.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Text(text = state.definition, style = MaterialTheme.typography.bodyMedium)
                    }
                }

                is DictCardUi.Miss -> Text(
                    text = if (state.aiAvailable) {
                        "本地词典未收录该词"
                    } else {
                        "本地词典未收录该词；配置 AI 服务或导入词典后可查"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 12.dp),
                )

                is DictCardUi.AwaitConfirmation -> Column {
                    Text(
                        text = "首次使用 AI 划词解释前请确认数据外发：",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "· 数据去向：当前配置的服务商（${state.baseUrl}）",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "· 数据范围：当前选中的词，不包含整本书内容",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "确认后不再提示；每次外发都可在 设置 → AI 服务 → 外发历史 中审计。",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.text.copy(alpha = 0.6f),
                    )
                }

                is DictCardUi.Loading -> Column {
                    LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        text = "正在请求 AI 解释…",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }

                is DictCardUi.AiStreaming -> {
                    if (state.running) {
                        LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                    val scrollState = rememberScrollState()
                    LaunchedEffect(state.text.length) {
                        scrollState.scrollTo(scrollState.maxValue)
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp, max = 220.dp)
                            .verticalScroll(scrollState),
                    ) {
                        Text(text = state.text, style = MaterialTheme.typography.bodyMedium)
                    }
                }

                is DictCardUi.Error -> Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }
            Row(modifier = Modifier.align(Alignment.End)) {
                if (onSaveWord != null &&
                    (state is DictCardUi.LocalHit || state is DictCardUi.AiStreaming)
                ) {
                    val savable = when (state) {
                        is DictCardUi.LocalHit -> state.definition.isNotBlank()
                        is DictCardUi.AiStreaming -> state.text.isNotBlank()
                        else -> false
                    }
                    TextButton(onClick = onSaveWord, enabled = savable) { Text("收藏") }
                }
                when (state) {
                    is DictCardUi.Miss -> if (state.aiAvailable) {
                        TextButton(onClick = onAiExplain) { Text("AI 解释") }
                    }
                    is DictCardUi.AwaitConfirmation ->
                        TextButton(onClick = onConfirm) { Text("同意并解释") }
                    is DictCardUi.Error -> TextButton(onClick = onRetry) { Text("重试") }
                    else -> {}
                }
            }
        }
    }
}

package com.llzx373.foldreader.feature.translate

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.llzx373.foldreader.FoldReaderApplication
import com.llzx373.foldreader.core.ai.AiTargetLang
import com.llzx373.foldreader.core.data.db.TranslationEntity
import com.llzx373.foldreader.feature.bookshelf.isPagedFormat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 「翻译全书」入口（M20）：详情页按钮。仅当书籍为文本格式（非漫画/分页格式）
 * 且 AI 已配置时渲染（组件内部自查，未配置不显示而非置灰）。
 */
@Composable
fun BookTranslateEntry(bookId: Long) {
    val context = LocalContext.current
    val container = remember(context) {
        (context.applicationContext as? FoldReaderApplication)?.container
    } ?: return
    val visible by produceState(initialValue = false, bookId) {
        val book = container.bookshelfRepository.getBook(bookId)
        value = book != null && !isPagedFormat(book.format) && container.aiConfigured()
    }
    if (!visible) return
    var showDialog by remember { mutableStateOf(false) }
    TextButton(onClick = { showDialog = true }) { Text("翻译全书") }
    if (showDialog) {
        BookTranslateConfirmDialog(bookId = bookId, onDismiss = { showDialog = false })
    }
}

/**
 * 全书翻译确认页（M20）：目标语言 + 成本预估（字符数 → ≈token → 金额），逐书确认。
 *
 * 预估口径与引擎台账一致：token ≈ 字符数 / 2；单价默认取设置里的 aiPricePerMillion，
 * 在这里改价确认时会持久化回设置。已有 done 单位时提示断点续译。
 * 确认动作：记一条「全书翻译」台账 + 入队（队列断点续译，done 单位跳过）。
 */
@Composable
fun BookTranslateConfirmDialog(bookId: Long, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val container = remember(context) {
        (context.applicationContext as? FoldReaderApplication)?.container
    } ?: return
    val scope = rememberCoroutineScope()

    val initial by produceState<Pair<String, Double>?>(initialValue = null) {
        val prefs = container.settingsRepository.preferences.first()
        value = prefs.aiTargetLang.name to prefs.aiPricePerMillion
    }
    var lang by remember { mutableStateOf(AiTargetLang.ZH_HANS) }
    var priceText by remember { mutableStateOf("") }
    var priceInitialized by remember { mutableStateOf(false) }
    initial?.let { (langName, price) ->
        if (!priceInitialized) {
            lang = runCatching { AiTargetLang.valueOf(langName) }.getOrDefault(AiTargetLang.ZH_HANS)
            priceText = price.toString()
            priceInitialized = true
        }
    }

    val book by produceState<com.llzx373.foldreader.core.data.db.BookEntity?>(
        initialValue = null, bookId,
    ) {
        value = container.bookshelfRepository.getBook(bookId)
    }
    val translatedCount by produceState(initialValue = 0, bookId, lang) {
        value = container.database.translationDao()
            .countByStatus(bookId, lang.name, TranslationEntity.STATUS_DONE)
    }

    val totalChars = book?.totalChars ?: 0L
    val estimatedTokens = totalChars / 2
    val price = priceText.toDoubleOrNull()
    val estimatedCost = price?.let { estimatedTokens / 1_000_000.0 * it }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("翻译全书") },
        text = {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                Text(
                    text = "将整本书分批发往你配置的服务商翻译；正文会外发，请自行评估服务商隐私政策。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text("目标语言", style = MaterialTheme.typography.labelMedium)
                Spacer(modifier = Modifier.height(4.dp))
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    LANG_OPTIONS.forEachIndexed { index, (option, label) ->
                        SegmentedButton(
                            selected = lang == option,
                            onClick = { lang = option },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = LANG_OPTIONS.size,
                            ),
                        ) { Text(label) }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "全书约 %,d 字，预估约 %,d token".format(totalChars, estimatedTokens),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = priceText,
                    onValueChange = { priceText = it },
                    label = { Text("单价（元 / 百万 token）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (estimatedCost != null) {
                    Text(
                        text = "费用预估：约 ¥%.2f（按 token ≈ 字符数/2 粗估，实际以服务商账单为准）"
                            .format(Locale.US, estimatedCost),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                if (translatedCount > 0) {
                    Text(
                        text = "已译 $translatedCount 个单位，将从断点继续（已译单位不重复外发）。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val currentBook = book ?: return@TextButton
                    scope.launch {
                        // 单价持久化（合法值才写回，非法输入保留旧价）
                        price?.takeIf { it >= 0 }?.let {
                            container.settingsRepository.setAiPricePerMillion(it)
                        }
                        container.aiContentGate.record(
                            FEATURE_BOOK_TRANSLATION,
                            currentBook.title,
                            estimatedTokens.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                        )
                        container.enqueueBookTranslation(bookId, lang)
                        onDismiss()
                    }
                },
            ) { Text("确认并开始") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

private val LANG_OPTIONS = listOf(
    AiTargetLang.ZH_HANS to "简中",
    AiTargetLang.ZH_HANT to "繁中",
    AiTargetLang.EN to "EN",
    AiTargetLang.JA to "日",
)

/** 台账 feature 名（确认页整书记一次；引擎仍按单位记「章节翻译」）。 */
private const val FEATURE_BOOK_TRANSLATION = "全书翻译"

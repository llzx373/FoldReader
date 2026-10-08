package com.llzx373.foldreader.feature.summary

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import com.llzx373.foldreader.core.data.db.ChapterSummaryEntity
import com.llzx373.foldreader.feature.bookshelf.isPagedFormat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 「预生成摘要」入口（M29）：详情页按钮。仅当书籍为文本格式（非漫画/分页格式）
 * 且 AI 已配置时渲染（组件内部自查，未配置不显示而非置灰）。
 */
@Composable
fun BookSummaryEntry(bookId: Long) {
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
    TextButton(onClick = { showDialog = true }) { Text("预生成摘要") }
    if (showDialog) {
        BookSummaryConfirmDialog(bookId = bookId, onDismiss = { showDialog = false })
    }
}

/**
 * 全书摘要预生成确认页（M29）：明示外发范围 + 成本预估（口径同「翻译全书」确认页），
 * 首次外发的一次性确认也在这里完成（未确认时确认按钮文案变为「同意外发并开始」，
 * 确认即落 aiSummaryConfirmed，之后不再提示）。
 *
 * 摘要语言跟随设置里的目标语言；已有 done 摘要时提示断点续做。
 * 确认动作：记一条「全书摘要」台账 + 入队（队列断点续做，done 单位跳过）。
 */
@Composable
fun BookSummaryConfirmDialog(bookId: Long, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val container = remember(context) {
        (context.applicationContext as? FoldReaderApplication)?.container
    } ?: return
    val scope = rememberCoroutineScope()

    val book by produceState<com.llzx373.foldreader.core.data.db.BookEntity?>(
        initialValue = null, bookId,
    ) {
        value = container.bookshelfRepository.getBook(bookId)
    }
    val prefs by produceState<com.llzx373.foldreader.core.data.settings.ReadingPreferences?>(
        initialValue = null,
    ) {
        value = container.settingsRepository.preferences.first()
    }
    val lang = prefs?.aiTargetLang
    val summarizedCount by produceState(initialValue = 0, bookId, lang) {
        val langKey = lang ?: return@produceState
        value = container.database.chapterSummaryDao()
            .getForBook(bookId, langKey.name)
            .count { it.status == ChapterSummaryEntity.STATUS_DONE }
    }

    val totalChars = book?.totalChars ?: 0L
    val estimatedTokens = totalChars / 2
    val price = prefs?.aiPricePerMillion
    val estimatedCost = price?.let { estimatedTokens / 1_000_000.0 * it }
    val confirmed = prefs?.aiSummaryConfirmed == true

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("预生成章节摘要") },
        text = {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                Text(
                    text = "将整本书分批（按章/块）发往你配置的服务商生成摘要；正文会外发，" +
                        "请自行评估服务商隐私政策。摘要语言跟随目标语言设置。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "全书约 %,d 字，预估约 %,d token".format(totalChars, estimatedTokens),
                    style = MaterialTheme.typography.bodyMedium,
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
                if (summarizedCount > 0) {
                    Text(
                        text = "已摘要 $summarizedCount 个单位，将从断点继续（已摘要单位不重复外发）。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                if (!confirmed) {
                    Text(
                        text = "首次使用：点击「同意外发并开始」即表示你了解正文将外发至所配置的服务商，" +
                            "之后摘要不再重复提示。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val currentBook = book ?: return@TextButton
                    val currentLang = lang ?: return@TextButton
                    scope.launch {
                        if (!confirmed) {
                            container.settingsRepository.setAiSummaryConfirmed(true)
                        }
                        container.aiContentGate.record(
                            FEATURE_BOOK_SUMMARY,
                            currentBook.title,
                            estimatedTokens.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                        )
                        container.enqueueBookSummary(bookId, currentLang)
                        onDismiss()
                    }
                },
            ) { Text(if (confirmed) "确认并开始" else "同意外发并开始") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 台账 feature 名（确认页整书记一次；引擎仍按单位记「章节摘要」）。 */
private const val FEATURE_BOOK_SUMMARY = "全书摘要"

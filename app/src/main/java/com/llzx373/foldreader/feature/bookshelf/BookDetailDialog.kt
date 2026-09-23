package com.llzx373.foldreader.feature.bookshelf

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.llzx373.foldreader.core.data.db.BookEntity
import com.llzx373.foldreader.core.data.db.BookFormat
import com.llzx373.foldreader.core.data.db.ReadingProgressEntity
import com.llzx373.foldreader.core.format.clean.CleanLevel
import com.llzx373.foldreader.core.reader.averageCharsPerMinute
import com.llzx373.foldreader.core.reader.formatDurationZh
import com.llzx373.foldreader.ui.EncodingPickerDialog
import com.llzx373.foldreader.ui.rememberLocale
import java.text.SimpleDateFormat
import java.util.Date
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 书籍详情：元信息 + 每书阅读统计（时长/进度/天数/平均速度）。 */
@Composable
fun BookDetailDialog(
    bookId: Long,
    viewModel: BookshelfViewModel,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refreshTick by remember { mutableIntStateOf(0) }
    var showEncodingPicker by remember { mutableStateOf(false) }
    // 导出走系统的「另存为」：默认文件名按内容标记，方便两份并排比
    var exportCleanedCopy by remember { mutableStateOf(true) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { target ->
        if (target != null) {
            viewModel.exportText(bookId, cleanedCopy = exportCleanedCopy, target = target) { message ->
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        }
    }
    val detail by produceState<Triple<BookEntity?, ReadingProgressEntity?, Int>?>(
        initialValue = null,
        bookId,
        refreshTick,
    ) {
        value = viewModel.bookDetail(bookId)
    }
    val book = detail?.first
    val progress = detail?.second
    val readingDays = detail?.third ?: 0
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
        title = { Text(book?.title ?: "书籍详情") },
        text = {
            if (book == null) {
                Text("加载中…")
            } else {
                val dateFormat = SimpleDateFormat("yyyy-MM-dd", rememberLocale())
                val isComic = isPagedFormat(book.format)
                val percent = when {
                    isComic -> formatComicProgress(progress?.comicPage, book.comicPageCount)
                    book.totalChars > 0 && progress != null ->
                        "${(progress.charOffset * 100 / book.totalChars).coerceIn(0, 100)}%"
                    else -> "未开始"
                }
                val totalMillis = progress?.totalReadingMillis ?: 0L
                Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    if (isComic) {
                        DetailRow(
                            "类型",
                            if (book.format == BookFormat.PDF) "PDF 文档"
                            else "漫画 · ${comicContainerLabel(book.comicContainer)}",
                        )
                        DetailRow(
                            "页数",
                            book.comicPageCount?.let { "$it 页" } ?: "待解析",
                        )
                        DetailRow(
                            "存储",
                            if (book.comicLocalPath != null) "已复制到本地" else "引用外部文件",
                        )
                    } else {
                        DetailRow("作者", book.author ?: "未知作者")
                    }
                    book.seriesName?.let { series ->
                        DetailRow(
                            "丛书",
                            series + (book.seriesIndex?.takeIf { it.isNotBlank() }
                                ?.let { " #$it" } ?: ""),
                        )
                    }
                    book.publisher?.let { DetailRow("出版社", it) }
                    book.pubDate?.let { DetailRow("出版日期", it) }
                    book.language?.let { DetailRow("语言", it) }
                    book.identifier?.let { DetailRow("ISBN / 标识", it) }
                    book.subjects?.let { subjects ->
                        DetailRow("标签", subjects.split("\n").joinToString("、"))
                    }
                    book.description?.let { DetailRow("简介", it) }
                    DetailRow("分组", book.groupName ?: "未分组")
                    if (!isComic) {
                        DetailRow("总字数", "%,d 字".format(book.totalChars))
                        if (book.format == BookFormat.TXT) {
                            // 同一个源文件允许「原版」与「清洗版」并存，这里说清这一本读的是哪一份
                            DetailRow(
                                "正文",
                                if (book.cleanedFilePath != null) "清洗副本" else "原文件",
                            )
                        }
                    }
                    DetailRow("阅读进度", percent)
                    DetailRow("累计时长", formatDurationZh(totalMillis))
                    DetailRow(
                        "首次阅读",
                        progress?.firstReadAt?.takeIf { it > 0 }
                            ?.let { dateFormat.format(Date(it)) } ?: "—",
                    )
                    DetailRow(
                        "最近阅读",
                        book.lastReadAt?.let { dateFormat.format(Date(it)) } ?: "—",
                    )
                    DetailRow(
                        "阅读天数",
                        if (readingDays > 0) "$readingDays 天" else "—",
                    )
                    if (!isComic) {
                        DetailRow(
                            "平均速度",
                            averageCharsPerMinute(progress?.charsReadTotal ?: 0L, totalMillis)
                                .let { if (it > 0) "$it 字/分钟" else "—" },
                        )
                        DetailRow(
                            label = "编码",
                            value = book.encoding.ifBlank { "自动检测" },
                            // 非 TXT 格式固定 UTF-8（压平产物），不提供编码切换
                            onClick = if (book.format == BookFormat.TXT) {
                                { showEncodingPicker = true }
                            } else {
                                null
                            },
                        )
                    }
                    if (isComic) {
                        Spacer(modifier = Modifier.height(8.dp))
                        // 「复制到本地」让漫画脱离 SAF 授权：源被移动/删除也还能读
                        if (book.comicLocalPath == null) {
                            TextButton(
                                onClick = {
                                    viewModel.copyComicLocal(bookId) { message ->
                                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                                        refreshTick++
                                    }
                                },
                            ) {
                                Text("复制到本地")
                            }
                        } else {
                            TextButton(
                                onClick = {
                                    viewModel.removeComicLocal(bookId) { message ->
                                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                                        refreshTick++
                                    }
                                },
                            ) {
                                Text("删除本地副本")
                            }
                        }
                    }
                    if (!isComic) {
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(
                            onClick = {
                                viewModel.rebuildChapters(bookId)
                                Toast.makeText(context, "正在按最新规则重建目录", Toast.LENGTH_SHORT).show()
                            },
                        ) {
                            Text("重建目录")
                        }
                        // M15：仅 TXT + AI 已配置时渲染（组件内部自查）
                        ChapterRuleAiEntry(bookId)
                        if (book.format == BookFormat.TXT) {
                            val cleanDefaults by viewModel.cleanDefaults.collectAsState()
                            val cleanPreview by viewModel.cleanPreview.collectAsState()
                            var showReclean by remember { mutableStateOf(false) }
                            TextButton(onClick = { showReclean = true }) {
                                Text("智能整理")
                            }
                            if (showReclean) {
                                RecleanConfirmDialog(
                                    alreadyCleaned = book.cleanedFilePath != null,
                                    defaultLevel = cleanDefaults.level,
                                    defaultConvertTraditional = cleanDefaults.convertTraditional,
                                    preview = cleanPreview,
                                    onPreview = { level, convert ->
                                        viewModel.previewReclean(bookId, level, convert)
                                    },
                                    onConfirm = { level, convert ->
                                        showReclean = false
                                        viewModel.consumeCleanPreview()
                                        viewModel.recleanBook(bookId, level, convert) { message ->
                                            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                                            refreshTick++
                                        }
                                    },
                                    onDismiss = {
                                        showReclean = false
                                        viewModel.consumeCleanPreview()
                                    },
                                )
                            }
                            // 清洗到底生效没有，看阅读页的排版很容易看走眼：把两份文本各导一份出来
                            // 直接比（电脑上 diff 也行）。有副本才谈得上「对照」。
                            val hasCleanedCopy = book.cleanedFilePath != null
                            TextButton(
                                onClick = {
                                    exportCleanedCopy = hasCleanedCopy
                                    exportLauncher.launch(exportFileName(book.title, hasCleanedCopy))
                                },
                            ) {
                                Text(if (hasCleanedCopy) "导出清洗后文本" else "导出正文")
                            }
                            if (hasCleanedCopy) {
                                TextButton(
                                    onClick = {
                                        exportCleanedCopy = false
                                        exportLauncher.launch(exportFileName(book.title, cleaned = false))
                                    },
                                ) {
                                    Text("导出原文")
                                }
                            }
                        }
                    }
                }
            }
        },
    )
    if (showEncodingPicker && book != null) {
        EncodingPickerDialog(
            currentEncoding = book.encoding,
            onSelect = { name ->
                showEncodingPicker = false
                viewModel.setEncoding(bookId, name)
                scope.launch {
                    delay(150)
                    refreshTick++
                }
            },
            onDismiss = { showEncodingPicker = false },
        )
    }
}

/**
 * 「智能整理」确认框。
 *
 * 档位在这里**当场可选**（不必先去设置页改全局），且每次改动都会重新采样预览——
 * 重洗每次都是从原始源文件跑，所以反复换档位是安全的，结果只取决于「原文 + 本次配方」。
 * 「不清理」这一项就是**撤销清理**：删掉副本、恢复原始编码，回到直接读原文件。
 */
@Composable
private fun RecleanConfirmDialog(
    alreadyCleaned: Boolean,
    defaultLevel: CleanLevel,
    defaultConvertTraditional: Boolean,
    preview: BookshelfViewModel.CleanPreview?,
    onPreview: (CleanLevel?, Boolean) -> Unit,
    onConfirm: (level: CleanLevel?, convertTraditional: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var level by remember { mutableStateOf<CleanLevel?>(defaultLevel) }
    var convertTraditional by remember { mutableStateOf(defaultConvertTraditional) }
    LaunchedEffect(level, convertTraditional) { onPreview(level, convertTraditional) }
    val report = preview?.report

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("智能整理") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = if (alreadyCleaned) {
                        "当前读的是清洗副本。可以在这里换一套规则重洗——每次都从原始源文件重跑，" +
                            "结果只取决于「原文 + 本次规则」，不会在上一版上叠加。"
                    } else {
                        "当前直接读原文件。选一套规则后会在库内生成清洗副本，原文件不受影响。"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "注意：正文内容会变，进度、书签与标注的字符位置可能随之偏移。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                ImportLevelOption("不清理（撤销清理）", "删掉副本、恢复原始编码，回到直接读原文件", level == null) {
                    level = null
                }
                CleanLevel.entries.forEach { candidate ->
                    if (candidate == CleanLevel.CUSTOM) return@forEach
                    ImportLevelOption(
                        label = importLevelLabel(candidate),
                        hint = importLevelHint(candidate),
                        selected = level == candidate,
                        onSelect = { level = candidate },
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = convertTraditional, onCheckedChange = { convertTraditional = it })
                    Text("繁体转简体")
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                when {
                    preview == null || preview.loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("正在采样预览…", style = MaterialTheme.typography.labelSmall)
                    }

                    report == null -> Text("预览不可用，仍可继续。", style = MaterialTheme.typography.labelSmall)

                    level == null -> Text("将撤销清理。", style = MaterialTheme.typography.labelSmall)

                    !report.changed -> Text(
                        text = "与当前内容一致，无需改动。",
                        style = MaterialTheme.typography.labelSmall,
                    )

                    else -> Column {
                        Text("预计改动：${report.summary()}", style = MaterialTheme.typography.labelSmall)
                        report.samples.take(3).forEach { sample ->
                            Text(
                                text = "${sample.before} → ${sample.after}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(level, convertTraditional) }, enabled = preview?.loading != true) {
                Text(if (level == null) "撤销清理" else "开始整理")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun DetailRow(label: String, value: String, onClick: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(vertical = 3.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.35f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = if (onClick != null) MaterialTheme.colorScheme.primary else Color.Unspecified,
            modifier = Modifier.weight(0.65f),
        )
    }
}

/** 非法文件名字符（各平台通用的一套）与导出默认名。 */
private val ILLEGAL_FILE_NAME_CHARS = Regex("[\\\\/:*?\"<>|]")

/** 导出的默认文件名：书名里可能有路径分隔符，清掉再当文件名用。 */
private fun exportFileName(title: String, cleaned: Boolean): String {
    val safe = title.replace(ILLEGAL_FILE_NAME_CHARS, "_").trim().take(80).ifBlank { "book" }
    return if (cleaned) "$safe-清洗后.txt" else "$safe-原文.txt"
}

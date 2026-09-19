package com.llzx373.foldreader.tools.cleaner

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.llzx373.foldreader.core.format.clean.CleanLevel
import com.llzx373.foldreader.core.format.clean.CleanReport
import com.llzx373.foldreader.core.format.clean.CleanToggles
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

@Composable
fun CleanerApp(model: CleanerModel) {
    MaterialTheme {
        Column(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TopControls(model)
            RulesPanel(model)
            Panes(model, Modifier.weight(1f))
            StatusBar(model)
        }
    }
}

// ────────────────────────────── 顶部：文件 / 编码 / 档位 / 动作 ──────────────────────────────

@Composable
private fun TopControls(model: CleanerModel) {
    var encodingOpen by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { chooseFile("打开 TXT", load = true)?.let(model::open) }) { Text("打开 TXT…") }
            Text(
                text = model.file?.absolutePath ?: "（尚未选择文件）",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("编码", style = MaterialTheme.typography.bodySmall)
            Box {
                OutlinedButton(onClick = { encodingOpen = true }) { Text(model.encodingChoice) }
                DropdownMenu(expanded = encodingOpen, onDismissRequest = { encodingOpen = false }) {
                    ENCODING_CHOICES.forEach { choice ->
                        DropdownMenuItem(
                            text = { Text(choice) },
                            onClick = {
                                model.encodingChoice = choice
                                encodingOpen = false
                                model.reDecode()
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.width(12.dp))
            Text("档位", style = MaterialTheme.typography.bodySmall)
            listOf(
                CleanLevel.CONSERVATIVE to "保守",
                CleanLevel.STANDARD to "标准",
                CleanLevel.AGGRESSIVE to "激进",
            ).forEach { (level, label) ->
                FilterChip(
                    selected = model.level == level,
                    onClick = { model.applyLevel(level) },
                    label = { Text(label) },
                )
            }
            if (model.level == CleanLevel.CUSTOM) {
                Text("（已逐项调整，档位=自定义）", style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.weight(1f))
            Button(onClick = model::clean) { Text("清洗") }
            OutlinedButton(
                enabled = model.resultText.isNotEmpty() && model.file != null,
                onClick = {
                    val base = model.file?.nameWithoutExtension ?: "cleaned"
                    chooseFile("保存清洗结果", load = false, suggested = "${base}-cleaned.txt")
                        ?.let(model::saveTo)
                },
            ) { Text("结果另存为…") }
        }

        if (model.encodingNote.isNotEmpty()) {
            Text(
                text = model.encodingNote,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ────────────────────────────── 规则明细（逐项开关） ──────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RulesPanel(model: CleanerModel) {
    var expanded by remember { mutableStateOf(false) }
    val enabled = CleanToggles.ENTRIES.count { it.get(model.toggles) }

    Column {
        TextButton(onClick = { expanded = !expanded }) {
            Text(
                if (expanded) "▾ 收起规则明细"
                else "▸ 规则明细（$enabled / ${CleanToggles.ENTRIES.size} 项开启，可逐项勾选）",
            )
        }
        if (expanded) {
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(start = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                CleanToggles.ENTRIES.forEach { entry ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = entry.get(model.toggles),
                            onCheckedChange = { model.setToggle(entry.set(model.toggles, it)) },
                        )
                        Text(entry.label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (model.toggles.traditionalToSimplified) {
                Text(
                    text = "繁简字表：${model.tsMapSource}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

// ────────────────────────────── 两栏对照 ──────────────────────────────

@Composable
private fun Panes(model: CleanerModel, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = model.filter,
            onValueChange = { model.filter = it },
            label = { Text("过滤：只显示包含该文本的行（留空显示全部）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.weight(1f)) {
            LinePane("原文", model.rawText, model.filter, Modifier.weight(1f))
            VerticalDivider(Modifier.padding(horizontal = 8.dp))
            LinePane("结果", model.resultText, model.filter, Modifier.weight(1f))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LinePane(title: String, text: String, filter: String, modifier: Modifier) {
    val lines = remember(text) {
        if (text.isEmpty()) emptyList() else text.trimEnd('\n').split('\n')
    }
    val needle = filter.trim()
    val shown = remember(lines, needle) {
        if (needle.isEmpty()) lines.withIndex().toList()
        else lines.withIndex().filter { it.value.contains(needle) }.toList()
    }

    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (needle.isEmpty()) "${lines.size} 行" else "命中 ${shown.size} / ${lines.size} 行",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider()
        if (shown.isEmpty()) {
            Text(
                text = if (lines.isEmpty()) "（还没有内容）" else "（没有匹配的行）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(8.dp),
            )
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(items = shown, key = { it.index }) { indexed ->
                    LineRow(indexed.index, indexed.value)
                }
            }
        }
    }
}

@Composable
private fun LineRow(number: Int, line: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(
            text = (number + 1).toString(),
            modifier = Modifier.width(52.dp).padding(end = 6.dp),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.End,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SelectionContainer(Modifier.weight(1f)) {
            Text(
                text = line.ifEmpty { " " },
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

// ────────────────────────────── 底部：报告与改动样例 ──────────────────────────────

@Composable
private fun StatusBar(model: CleanerModel) {
    var samplesOpen by remember { mutableStateOf(false) }
    val report = model.report

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        HorizontalDivider()
        Text(
            text = model.status,
            style = MaterialTheme.typography.bodyMedium,
            color = if (model.hasError) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
        if (report != null) {
            Text(
                text = "行 ${report.linesIn} → ${report.linesOut}" +
                    "；字符减少 ${report.charsRemoved}；耗时 ${model.elapsedMs} ms" +
                    "；幂等性可用「清洗」跑两遍对照（第二遍结果应不变）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (report.samples.isNotEmpty()) {
                TextButton(onClick = { samplesOpen = !samplesOpen }) {
                    Text(if (samplesOpen) "▾ 收起改动样例" else "▸ 改动样例（前 ${report.samples.size} 条）")
                }
                if (samplesOpen) {
                    Column(
                        Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState()),
                    ) {
                        report.samples.forEach { sample ->
                            Text(
                                text = "[${sample.kind.label()}] ${sample.before}  →  ${sample.after}",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun CleanReport.Sample.Kind.label(): String = when (this) {
    CleanReport.Sample.Kind.REMOVED -> "删除"
    CleanReport.Sample.Kind.MERGED -> "合并"
    CleanReport.Sample.Kind.CHAPTER -> "章节"
    CleanReport.Sample.Kind.CHAR -> "字符"
    CleanReport.Sample.Kind.INLINE -> "行内"
}

private fun chooseFile(title: String, load: Boolean, suggested: String? = null): File? {
    val dialog = FileDialog(
        null as Frame?,
        title,
        if (load) FileDialog.LOAD else FileDialog.SAVE,
    )
    suggested?.let { dialog.file = it }
    dialog.isVisible = true
    val directory = dialog.directory ?: return null
    val name = dialog.file ?: return null
    return File(directory, name)
}

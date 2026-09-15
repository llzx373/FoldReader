package com.llzx373.foldreader.feature.reader

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.llzx373.foldreader.core.data.db.AnnotationEntity
import com.llzx373.foldreader.core.format.Chapter

/** 划线可选颜色（存 ARGB Long）。 */
val annotationColorPalette: List<Color> = listOf(
    Color(0xFFFFF176), // 黄
    Color(0xFFA5D6A7), // 绿
    Color(0xFF90CAF9), // 蓝
    Color(0xFFF48FB1), // 粉
    Color(0xFFCE93D8), // 紫
)

@Composable
private fun ColorDots(
    selectedArgb: Long,
    onPick: (Long) -> Unit,
) {
    Row {
        annotationColorPalette.forEach { color ->
            val argb = color.toArgb().toLong() and 0xFFFFFFFFL
            val selected = selectedArgb == argb
            Spacer(
                modifier = Modifier
                    .padding(4.dp)
                    .size(if (selected) 30.dp else 26.dp)
                    .clip(CircleShape)
                    .background(color)
                    .border(
                        width = if (selected) 2.dp else 1.dp,
                        color = if (selected) MaterialTheme.colorScheme.primary else Color(0x33000000),
                        shape = CircleShape,
                    )
                    .clickable { onPick(argb) },
            )
        }
    }
}

/** 选区操作条：多色划线（点色即存）+ 笔记 + 书签 + 复制 + 取消。 */
@Composable
fun SelectionActionBar(
    colors: ReaderColors,
    onPickColor: (Long) -> Unit,
    onNote: () -> Unit,
    onBookmark: () -> Unit,
    onCopy: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = colors.background,
        contentColor = colors.text,
        tonalElevation = 6.dp,
        shadowElevation = 10.dp,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            ColorDots(selectedArgb = -1L, onPick = onPickColor)
            TextButton(onClick = onNote) { Text("笔记") }
            TextButton(onClick = onBookmark) { Text("书签") }
            TextButton(onClick = onCopy) { Text("复制") }
            TextButton(onClick = onCancel) { Text("取消") }
        }
    }
}

/** 新建带笔记的划线，或编辑已有划线（改色/改样式/改笔记/删除）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnnotationEditDialog(
    selectedText: String,
    initialColorArgb: Long,
    initialNote: String?,
    initialStyle: String = AnnotationEntity.STYLE_HIGHLIGHT,
    shifted: Boolean,
    colors: ReaderColors,
    onSave: (colorArgb: Long, note: String?, style: String) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var colorArgb by remember { mutableLongStateOf(initialColorArgb) }
    var note by remember { mutableStateOf(initialNote.orEmpty()) }
    var style by remember { mutableStateOf(initialStyle) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (onDelete == null) "划线笔记" else "编辑划线") },
        text = {
            Column {
                Text(
                    text = selectedText,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    color = colors.text,
                )
                if (shifted) {
                    Text(
                        text = "原书内容已变化，此划线可能错位",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                ColorDots(selectedArgb = colorArgb, onPick = { colorArgb = it })
                Spacer(modifier = Modifier.height(6.dp))
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    listOf(
                        AnnotationEntity.STYLE_HIGHLIGHT to "底色",
                        AnnotationEntity.STYLE_UNDERLINE to "下划线",
                    ).forEachIndexed { index, (value, label) ->
                        SegmentedButton(
                            selected = style == value,
                            onClick = { style = value },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = 2,
                            ),
                        ) { Text(label) }
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    placeholder = { Text("写点想法…（可留空）") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(colorArgb, note, style) }) { Text("保存") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) { Text("删除") }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

/** 标注列表：按章节分组；点击跳转，长按编辑。shifted 的灰显并提示。 */
@Composable
fun AnnotationListDialog(
    annotations: List<AnnotationEntity>,
    chapters: List<Chapter>,
    shiftedIds: Set<Long>,
    colors: ReaderColors,
    onJump: (AnnotationEntity) -> Unit,
    onEdit: (AnnotationEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    val sorted = remember(annotations) { annotations.sortedBy { it.startCharOffset } }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
        title = { Text("标注") },
        text = {
            if (sorted.isEmpty()) {
                Text(
                    text = "还没有划线，长按正文选中文字即可划线",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.height(380.dp)) {
                    var lastChapter = -1
                    sorted.forEach { ann ->
                        val chapterIndex = chapters.indexOfLast { ann.startCharOffset >= it.charStart }
                            .coerceAtLeast(0)
                        if (chapterIndex != lastChapter) {
                            lastChapter = chapterIndex
                            item(key = "header-$chapterIndex-${ann.id}") {
                                Text(
                                    text = chapters.getOrNull(chapterIndex)?.title.orEmpty()
                                        .ifEmpty { "正文" },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = colors.accent,
                                    modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                                )
                            }
                        }
                        item(key = ann.id) {
                            AnnotationRow(
                                annotation = ann,
                                shifted = ann.id in shiftedIds,
                                onClick = { onJump(ann) },
                                onLongClick = { onEdit(ann) },
                            )
                        }
                    }
                }
            }
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AnnotationRow(
    annotation: AnnotationEntity,
    shifted: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val alpha = if (shifted) 0.45f else 1f
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = 8.dp),
    ) {
        Spacer(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(Color(annotation.color.toInt()).copy(alpha = alpha)),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = annotation.selectedText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val note = annotation.note
            if (!note.isNullOrEmpty()) {
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (shifted) {
                Text(
                    text = "原书内容已变化，可能错位",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                )
            }
        }
    }
}

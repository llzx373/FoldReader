package com.llzx373.foldreader.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.llzx373.foldreader.FoldReaderApplication
import com.llzx373.foldreader.core.comic.comicSeriesStem
import com.llzx373.foldreader.core.data.db.BookFormat
import com.llzx373.foldreader.core.data.db.GlossaryTermEntity
import kotlinx.coroutines.launch

/**
 * 术语表对话框（M20 + M22-2.5，R6）：四个分区 ——
 *
 * - 候选：人物索引 TopN 与模型回填的自动候选，确认后（空译法须补填）才参与注入；
 * - 全局表：对所有书生效的手动词条，可添加/删除；
 * - 系列表（M22-2.5）：漫画系列共享词条（scope=series，ownerKey=漫画主干 seriesKey），
 *   漫画翻译注入时跨卷生效；[seriesKey] 非空时锁定该系列，否则从书架漫画主干 +
 *   已有系列行汇总出可选清单，两者皆空时该 Tab 不显示；
 * - 单书表：按书查看与维护本书词条；选中书时顺带触发人物候选生成
 *   （[FoldReaderApplication.container] 的 seedGlossaryCandidatesFromPersons）。
 *
 * 自治组件：直接从容器取 DAO/仓库，不进 SettingsViewModel。
 */
@Composable
fun GlossaryDialog(onDismiss: () -> Unit, seriesKey: String? = null) {
    val context = LocalContext.current
    val container = remember(context) {
        (context.applicationContext as? FoldReaderApplication)?.container
    } ?: return
    val dao = container.database.glossaryTermDao()
    val scope = rememberCoroutineScope()

    var tab by remember { mutableStateOf(GlossaryTab.CANDIDATES) }
    val unconfirmed by dao.observeUnconfirmed().collectAsState(initial = emptyList())
    val globalTerms by dao.observeFor(GlossaryTermEntity.SCOPE_GLOBAL, "")
        .collectAsState(initial = emptyList())
    val allTerms by dao.observeAll().collectAsState(initial = emptyList())
    val shelfBooks by container.bookshelfRepository.observeBookshelf()
        .collectAsState(initial = emptyList())

    // 单书表：从全表取 book 行的 ownerKey 去重作为选书清单
    val bookOwnerKeys = remember(allTerms) {
        allTerms.filter { it.scope == GlossaryTermEntity.SCOPE_BOOK }
            .map { it.ownerKey }.distinct()
    }
    var selectedBookKey by remember { mutableStateOf<String?>(null) }
    val effectiveBookKey = selectedBookKey ?: bookOwnerKeys.firstOrNull()
    val bookTerms = remember(allTerms, effectiveBookKey) {
        allTerms.filter {
            it.scope == GlossaryTermEntity.SCOPE_BOOK && it.ownerKey == effectiveBookKey
        }
    }
    // bookId → 书名缓存（候选分组与选书清单显示用）
    var bookTitles by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(unconfirmed, bookOwnerKeys) {
        val keys = (unconfirmed.filter { it.scope == GlossaryTermEntity.SCOPE_BOOK }
            .map { it.ownerKey } + bookOwnerKeys).distinct()
        val resolved = HashMap(bookTitles)
        for (key in keys) {
            if (resolved.containsKey(key)) continue
            val title = key.toLongOrNull()?.let { container.bookshelfRepository.getBook(it)?.title }
            resolved[key] = title ?: "书 #$key"
        }
        bookTitles = resolved
    }
    // 选中某书时生成人物候选（幂等 upsert，重复打开无副作用）
    LaunchedEffect(effectiveBookKey) {
        effectiveBookKey?.toLongOrNull()?.let { container.seedGlossaryCandidatesFromPersons(it) }
    }

    // 系列表（M22-2.5）：锁定传入的 seriesKey，否则汇总书架漫画主干与已有系列行；
    // 清单为空时该 Tab 不显示（没有任何可归属的系列）
    val seriesKeys = remember(shelfBooks, allTerms, seriesKey) {
        val fromShelf = shelfBooks.filter { it.format == BookFormat.COMIC }
            .map { comicSeriesStem(it.title) }.filter { it.isNotEmpty() }
        val fromRows = allTerms.filter { it.scope == GlossaryTermEntity.SCOPE_SERIES }
            .map { it.ownerKey }
        (listOfNotNull(seriesKey) + fromShelf + fromRows).distinct()
    }
    val visibleTabs = remember(seriesKeys) {
        if (seriesKeys.isEmpty()) GlossaryTab.entries.filter { it != GlossaryTab.SERIES }
        else GlossaryTab.entries
    }
    var selectedSeriesKey by remember { mutableStateOf<String?>(null) }
    val effectiveSeriesKey = seriesKey ?: selectedSeriesKey ?: seriesKeys.firstOrNull()
    val seriesTerms = remember(allTerms, effectiveSeriesKey) {
        allTerms.filter {
            it.scope == GlossaryTermEntity.SCOPE_SERIES && it.ownerKey == effectiveSeriesKey
        }
    }
    val effectiveTab = if (tab in visibleTabs) tab else visibleTabs.first()

    var editingTarget by remember { mutableStateOf<GlossaryTermEntity?>(null) }
    var targetInput by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("术语表") },
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    visibleTabs.forEachIndexed { index, entry ->
                        SegmentedButton(
                            selected = effectiveTab == entry,
                            onClick = { tab = entry },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = visibleTabs.size,
                            ),
                        ) { Text(entry.label) }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                when (effectiveTab) {
                    GlossaryTab.CANDIDATES -> CandidatesPane(
                        unconfirmed = unconfirmed,
                        bookTitles = bookTitles,
                        onConfirm = { term ->
                            if (term.target.isBlank()) {
                                targetInput = ""
                                editingTarget = term
                            } else {
                                scope.launch { dao.setConfirmed(term.id, confirmed = true) }
                            }
                        },
                        onReject = { term -> scope.launch { dao.delete(term.id) } },
                    )

                    GlossaryTab.GLOBAL -> TermListPane(
                        terms = globalTerms,
                        onDelete = { term -> scope.launch { dao.delete(term.id) } },
                        onAdd = { source, target ->
                            scope.launch {
                                dao.upsert(
                                    GlossaryTermEntity(
                                        scope = GlossaryTermEntity.SCOPE_GLOBAL,
                                        ownerKey = "",
                                        source = source.trim(),
                                        target = target.trim(),
                                        origin = GlossaryTermEntity.ORIGIN_USER,
                                        confirmed = true,
                                    ),
                                )
                            }
                        },
                    )

                    GlossaryTab.SERIES -> SeriesPane(
                        seriesKeys = seriesKeys,
                        pinned = seriesKey != null,
                        selectedKey = effectiveSeriesKey,
                        onSelect = { selectedSeriesKey = it },
                        terms = seriesTerms,
                        onDelete = { term -> scope.launch { dao.delete(term.id) } },
                        onAdd = add@{ source, target ->
                            val key = effectiveSeriesKey ?: return@add
                            scope.launch {
                                dao.upsert(
                                    GlossaryTermEntity(
                                        scope = GlossaryTermEntity.SCOPE_SERIES,
                                        ownerKey = key,
                                        source = source.trim(),
                                        target = target.trim(),
                                        origin = GlossaryTermEntity.ORIGIN_USER,
                                        confirmed = true,
                                    ),
                                )
                            }
                        },
                    )

                    GlossaryTab.BOOK -> BookPane(
                        bookOwnerKeys = bookOwnerKeys,
                        bookTitles = bookTitles,
                        selectedKey = effectiveBookKey,
                        onSelect = { selectedBookKey = it },
                        terms = bookTerms,
                        onDelete = { term -> scope.launch { dao.delete(term.id) } },
                        onAdd = add@{ source, target ->
                            val key = effectiveBookKey ?: return@add
                            scope.launch {
                                dao.upsert(
                                    GlossaryTermEntity(
                                        scope = GlossaryTermEntity.SCOPE_BOOK,
                                        ownerKey = key,
                                        source = source.trim(),
                                        target = target.trim(),
                                        origin = GlossaryTermEntity.ORIGIN_USER,
                                        confirmed = true,
                                    ),
                                )
                            }
                        },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )

    // 空译法候选确认前补填译法
    editingTarget?.let { term ->
        AlertDialog(
            onDismissRequest = { editingTarget = null },
            title = { Text("补填译法") },
            text = {
                Column {
                    Text(term.source, style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = targetInput,
                        onValueChange = { targetInput = it },
                        label = { Text("约定译法") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val target = targetInput.trim()
                        if (target.isNotEmpty()) {
                            scope.launch {
                                dao.setConfirmed(term.id, confirmed = true, target = target)
                            }
                            editingTarget = null
                        }
                    },
                ) { Text("确认") }
            },
            dismissButton = {
                TextButton(onClick = { editingTarget = null }) { Text("取消") }
            },
        )
    }
}

private enum class GlossaryTab(val label: String) {
    CANDIDATES("候选"),
    GLOBAL("全局表"),
    SERIES("系列表"),
    BOOK("单书表"),
}

/** 候选分区：按 (scope, ownerKey) 分组列出，逐条确认 / 否决。 */
@Composable
private fun CandidatesPane(
    unconfirmed: List<GlossaryTermEntity>,
    bookTitles: Map<String, String>,
    onConfirm: (GlossaryTermEntity) -> Unit,
    onReject: (GlossaryTermEntity) -> Unit,
) {
    if (unconfirmed.isEmpty()) {
        Text(
            text = "暂无候选。翻译前几章后模型会自动回填专名对照；" +
                "打开单书表也会从本书人物索引生成候选。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val groups = unconfirmed.groupBy { it.scope to it.ownerKey }
    LazyColumn {
        groups.forEach { (key, terms) ->
            item(key = "group-${key.first}-${key.second}") {
                Text(
                    text = groupLabel(key.first, key.second, bookTitles),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                )
            }
            items(terms, key = { it.id }) { term ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(term.source, style = MaterialTheme.typography.bodySmall)
                        if (term.target.isNotBlank()) {
                            Text(
                                text = "→ ${term.target}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    TextButton(onClick = { onConfirm(term) }) { Text("确认") }
                    TextButton(onClick = { onReject(term) }) { Text("否决") }
                }
                HorizontalDivider()
            }
        }
    }
}

private fun groupLabel(scope: String, ownerKey: String, bookTitles: Map<String, String>): String =
    when (scope) {
        GlossaryTermEntity.SCOPE_BOOK -> "本书（${bookTitles[ownerKey] ?: "书 #$ownerKey"}）"
        GlossaryTermEntity.SCOPE_SERIES -> "系列（$ownerKey）"
        else -> "全局"
    }

/** 词条列表 + 底部手动添加（全局 / 单书共用）。 */
@Composable
private fun TermListPane(
    terms: List<GlossaryTermEntity>,
    onDelete: (GlossaryTermEntity) -> Unit,
    onAdd: (String, String) -> Unit,
) {
    var source by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("") }
    Column {
        LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
            if (terms.isEmpty()) {
                item {
                    Text(
                        text = "暂无词条，可在下方手动添加。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(terms, key = { it.id }) { term ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                ) {
                    Text(
                        text = "${term.source} → ${term.target}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { onDelete(term) }) { Text("删除") }
                }
                HorizontalDivider()
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = source,
                onValueChange = { source = it },
                label = { Text("原文词") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = target,
                onValueChange = { target = it },
                label = { Text("译法") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        TextButton(
            onClick = {
                if (source.isNotBlank() && target.isNotBlank()) {
                    onAdd(source, target)
                    source = ""
                    target = ""
                }
            },
            modifier = Modifier.align(Alignment.End),
        ) { Text("添加") }
    }
}

/** 单书分区：选书 + 词条维护；选书清单来自已有 book 行（含候选）。 */
@Composable
private fun BookPane(
    bookOwnerKeys: List<String>,
    bookTitles: Map<String, String>,
    selectedKey: String?,
    onSelect: (String) -> Unit,
    terms: List<GlossaryTermEntity>,
    onDelete: (GlossaryTermEntity) -> Unit,
    onAdd: (String, String) -> Unit,
) {
    if (bookOwnerKeys.isEmpty()) {
        Text(
            text = "还没有任何书的词条。开始全书翻译或打开候选页生成人物候选后，这里会出现对应的书。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Column {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        ) {
            bookOwnerKeys.forEach { key ->
                val label = bookTitles[key] ?: "书 #$key"
                TextButton(onClick = { onSelect(key) }) {
                    Text(
                        text = if (key == selectedKey) "【$label】" else label,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                    )
                }
            }
        }
        TermListPane(terms = terms, onDelete = onDelete, onAdd = onAdd)
    }
}

/**
 * 系列分区（M22-2.5）：选系列 + 词条维护（scope=series，ownerKey=seriesKey）。
 * [pinned] = true 时系列由调用方锁定，不显示选择行。
 */
@Composable
private fun SeriesPane(
    seriesKeys: List<String>,
    pinned: Boolean,
    selectedKey: String?,
    onSelect: (String) -> Unit,
    terms: List<GlossaryTermEntity>,
    onDelete: (GlossaryTermEntity) -> Unit,
    onAdd: (String, String) -> Unit,
) {
    if (seriesKeys.isEmpty()) {
        Text(
            text = "书架上还没有漫画，导入漫画后这里会出现对应的系列。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Column {
        if (!pinned && seriesKeys.size > 1) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            ) {
                seriesKeys.forEach { key ->
                    TextButton(onClick = { onSelect(key) }) {
                        Text(
                            text = if (key == selectedKey) "【$key】" else key,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
        TermListPane(terms = terms, onDelete = onDelete, onAdd = onAdd)
    }
}

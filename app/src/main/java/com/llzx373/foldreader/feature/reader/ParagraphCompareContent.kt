package com.llzx373.foldreader.feature.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.llzx373.foldreader.core.translate.UNTRANSLATED_PLACEHOLDER

/**
 * 视角 3（M20）：滚动模式的段落对照内容区。
 *
 * LazyColumn 按翻译单位发 item，每项内部是「原文段 → 其下译文段卡片」的逐段对照；
 * 段对段映射直接取译本落盘的段落数组（5.2 段落结构化落盘），不做运行时对齐——
 * 译文段数不足时多余的原文段下方没有译文卡片，按未译处理。
 *
 * 性能：单位粒度懒加载 + VM 侧 LRU（[ReaderViewModel.paragraphCompareUnit]），
 * 大书不会一次性读全文。选字翻译 / 标注写入在本模式不挂（与译文模式同口径），
 * 复制由 [SelectionContainer] 保留。
 */
@Composable
fun ParagraphCompareContent(
    viewModel: ReaderViewModel,
    listState: LazyListState,
    colors: ReaderColors,
    modifier: Modifier = Modifier,
) {
    val units by viewModel.translationUnits.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val config = uiState.layoutConfig
    val bodyStyle = MaterialTheme.typography.bodyLarge.copy(
        fontSize = config.fontSizeSp.sp,
        lineHeight = (config.fontSizeSp * config.lineSpacingMultiplier).sp,
        color = colors.text,
    )
    val translatedBackground = colors.text.copy(alpha = 0.05f)

    // 首帧定位到进入对照时的锚点单位
    var positioned by remember { mutableStateOf(false) }
    LaunchedEffect(units) {
        if (!positioned && units.isNotEmpty()) {
            positioned = true
            listState.scrollToItem(
                viewModel.paragraphCompareAnchorIndex.coerceIn(0, units.lastIndex),
            )
        }
    }

    // 对照期间的跳转（进度条 / 目录 / 书签 / 搜索命中）：滚到目标单位
    LaunchedEffect(listState, units) {
        viewModel.paragraphCompareJumps.collect { index ->
            if (units.isNotEmpty()) listState.scrollToItem(index.coerceIn(0, units.lastIndex))
        }
    }

    // 自动翻页（SCROLL 档）：与 ScrollContent 同口径
    LaunchedEffect(listState) {
        viewModel.autoScrollTicks.collect { delta -> listState.scroll { scrollBy(delta) } }
    }

    // 首个可见项变化 = 滚动停止换单位：把该单位原文起点写回锚点（沿用 scrollAnchorTo 口径）。
    // 首次发射是初始定位（锚点已在该单位内部），跳过不回写，保住单位内精度
    LaunchedEffect(listState, units) {
        if (units.isEmpty()) return@LaunchedEffect
        var skipInitial = true
        snapshotFlow { listState.firstVisibleItemIndex }.collect { index ->
            if (skipInitial) {
                skipInitial = false
                return@collect
            }
            units.getOrNull(index)?.let { viewModel.scrollAnchorTo(it.charStart) }
        }
    }

    SelectionContainer(modifier = modifier.fillMaxSize()) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(units, key = { it.index }) { unit ->
                ParagraphCompareUnitItem(
                    viewModel = viewModel,
                    unitIndex = unit.index,
                    unitTitle = unit.title,
                    bodyStyle = bodyStyle,
                    colors = colors,
                    translatedBackground = translatedBackground,
                )
            }
        }
    }
}

/** 一个对照单位：标题 + 逐段「原文段 / 译文段卡片」；内容按单位懒加载。 */
@Composable
private fun ParagraphCompareUnitItem(
    viewModel: ReaderViewModel,
    unitIndex: Int,
    unitTitle: String,
    bodyStyle: androidx.compose.ui.text.TextStyle,
    colors: ReaderColors,
    translatedBackground: androidx.compose.ui.graphics.Color,
) {
    val content by produceState<ReaderViewModel.ParagraphCompareUnit?>(
        initialValue = null,
        unitIndex,
    ) {
        value = viewModel.paragraphCompareUnit(unitIndex)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Text(
            text = unitTitle,
            style = MaterialTheme.typography.labelMedium,
            color = colors.text.copy(alpha = 0.55f),
            maxLines = 1,
        )
        Spacer(modifier = Modifier.height(6.dp))
        val loaded = content
        if (loaded == null) {
            // 加载中占位：高度极低，加载完成后 LazyColumn 自动撑开
            return@Column
        }
        val translated = loaded.translatedParagraphs
        loaded.sourceParagraphs.forEachIndexed { i, sourceParagraph ->
            Text(text = sourceParagraph, style = bodyStyle)
            val translatedParagraph = translated?.getOrNull(i)
            if (translatedParagraph != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .background(translatedBackground, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = translatedParagraph,
                        style = bodyStyle.copy(color = colors.text.copy(alpha = 0.8f)),
                    )
                }
            } else {
                Spacer(modifier = Modifier.height(6.dp))
            }
        }
        if (translated == null && loaded.sourceParagraphs.isNotEmpty()) {
            // 未译单位：原文照常显示，尾部给占位样式明示
            Text(
                text = UNTRANSLATED_PLACEHOLDER,
                style = MaterialTheme.typography.labelMedium,
                color = colors.text.copy(alpha = 0.4f),
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
    }
}

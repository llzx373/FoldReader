package com.llzx373.foldreader.feature.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.llzx373.foldreader.core.data.settings.ReadingPreferences

@Composable
fun TabletopDivider(colors: ReaderColors, modifier: Modifier = Modifier) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(14.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            ),
    ) {
        drawRect(
            brush = Brush.verticalGradient(
                listOf(Color.Black.copy(alpha = 0.12f), Color.Transparent),
                startY = 0f,
                endY = size.height,
            ),
            topLeft = Offset.Zero,
            size = Size(size.width, size.height),
        )
        drawLine(
            color = colors.text.copy(alpha = 0.20f),
            start = Offset(0f, 0.5f),
            end = Offset(size.width, 0.5f),
            strokeWidth = 1.dp.toPx(),
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TabletopPanel(
    prefs: ReadingPreferences,
    progressFraction: Float,
    colors: ReaderColors,
    autoPageStatus: AutoPageStatus,
    onPrevPage: () -> Unit,
    onNextPage: () -> Unit,
    onSeekFraction: (Float) -> Unit,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onSetBrightness: (Float) -> Unit,
    onFontSizeDelta: (Float) -> Unit,
    onToggleAutoPage: (Boolean) -> Unit,
    onCycleAutoPageMode: () -> Unit,
    onCycleAutoPageSpeed: () -> Unit,
    onTogglePanelOff: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val consumeTap = remember { MutableInteractionSource() }
    if (prefs.panelScreenOff) {
        Box(
            modifier = modifier
                .background(Color.Black)
                .clickable(
                    interactionSource = consumeTap,
                    indication = null,
                    onClick = { onTogglePanelOff(false) },
                ),
            contentAlignment = Alignment.TopCenter,
        ) {
            Text(
                text = "面板已熄屏 · 点按唤醒",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0x66FFFFFF),
                modifier = Modifier.padding(top = 10.dp),
            )
        }
        return
    }
    Surface(
        modifier = modifier.clickable(
            interactionSource = consumeTap,
            indication = null,
            onClick = {},
        ),
        color = colors.background,
        contentColor = colors.text,
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
            var sliderFraction by remember { mutableStateOf(progressFraction) }
            var sliderDragging by remember { mutableStateOf(false) }
            LaunchedEffect(progressFraction) {
                if (!sliderDragging) sliderFraction = progressFraction
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(
                    value = sliderFraction,
                    onValueChange = {
                        sliderDragging = true
                        sliderFraction = it
                    },
                    onValueChangeFinished = {
                        sliderDragging = false
                        onSeekFraction(sliderFraction)
                    },
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = formatPercent(sliderFraction),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
            ButtonGroup(
                overflowIndicator = {},
                modifier = Modifier.fillMaxWidth(),
            ) {
                clickableItem(onClick = onPrevChapter, label = "上一章")
                clickableItem(onClick = onPrevPage, label = "上一页", weight = 1f)
                clickableItem(onClick = onNextPage, label = "下一页", weight = 1f)
                clickableItem(onClick = onNextChapter, label = "下一章")
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("字号", style = MaterialTheme.typography.labelMedium)
                TextButton(onClick = { onFontSizeDelta(-1f) }) { Text("A−") }
                TextButton(onClick = { onFontSizeDelta(1f) }) { Text("A＋") }
                Text(
                    "亮度",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(start = 8.dp),
                )
                Slider(
                    value = if (prefs.readerBrightness < 0f) 0.5f else prefs.readerBrightness,
                    onValueChange = onSetBrightness,
                    valueRange = 0.05f..1f,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("自动翻页", style = MaterialTheme.typography.labelMedium)
                    Switch(
                        checked = prefs.autoPageEnabled,
                        onCheckedChange = onToggleAutoPage,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                    if (autoPageStatus.enabled) {
                        Text(
                            text = if (autoPageStatus.paused) "已暂停" else "运行中",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.accent,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("面板熄屏", style = MaterialTheme.typography.labelMedium)
                    Switch(
                        checked = prefs.panelScreenOff,
                        onCheckedChange = onTogglePanelOff,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
            if (prefs.autoPageEnabled) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    TextButton(onClick = onCycleAutoPageMode) {
                        Text("模式：${autoPageModeLabel(prefs.autoPageMode)}")
                    }
                    TextButton(onClick = onCycleAutoPageSpeed) {
                        Text("速度：${autoPageSpeedLabel(prefs)}")
                    }
                }
            }
        }
    }
}

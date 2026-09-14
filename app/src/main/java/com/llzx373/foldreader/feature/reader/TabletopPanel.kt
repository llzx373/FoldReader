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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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

@Composable
fun TabletopPanel(
    prefs: ReadingPreferences,
    progressFraction: Float,
    colors: ReaderColors,
    onPrevPage: () -> Unit,
    onNextPage: () -> Unit,
    onSeekFraction: (Float) -> Unit,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onSetBrightness: (Float) -> Unit,
    onFontSizeDelta: (Float) -> Unit,
    onToggleAutoPage: (Boolean) -> Unit,
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(
                    value = sliderFraction,
                    onValueChange = { sliderFraction = it },
                    onValueChangeFinished = { onSeekFraction(sliderFraction) },
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = formatPercent(sliderFraction),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onPrevChapter) { Text("上一章") }
                Button(onClick = onPrevPage, modifier = Modifier.weight(1f)) { Text("上一页") }
                Button(onClick = onNextPage, modifier = Modifier.weight(1f)) { Text("下一页") }
                TextButton(onClick = onNextChapter) { Text("下一章") }
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
        }
    }
}

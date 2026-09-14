package com.llzx373.foldreader.feature.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.llzx373.foldreader.core.data.settings.ReadingPreferences
import com.llzx373.foldreader.core.data.settings.ReadingTheme

val themePresets: List<Pair<ReadingTheme, String>> = listOf(
    ReadingTheme.GREEN to "护眼绿",
    ReadingTheme.PARCHMENT to "羊皮纸",
    ReadingTheme.GRAY_WHITE to "灰白",
    ReadingTheme.NIGHT to "夜间",
    ReadingTheme.AMOLED to "纯黑",
    ReadingTheme.CUSTOM to "自定义",
)

val backgroundPalette: List<Color> = listOf(
    Color(0xFFCCE8CF), Color(0xFFF5EBD7), Color(0xFFF5F5F5), Color(0xFFE3F2FD),
    Color(0xFFFFF3E0), Color(0xFFFCE4EC), Color(0xFF2A2A2A), Color(0xFF000000),
)

val textPalette: List<Color> = listOf(
    Color(0xFF1B2A1B), Color(0xFF3A2F1B), Color(0xFF212121), Color(0xFF37474F),
    Color(0xFF4E342E), Color(0xFF880E4F), Color(0xFFB0B0B0), Color(0xFFECEFF1),
)

@Composable
fun ThemePicker(
    prefs: ReadingPreferences,
    onSelectTheme: (ReadingTheme) -> Unit,
    onPickCustomBackground: (Int) -> Unit,
    onPickCustomText: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 编辑器底板直接套用所选配色，选择即时可见（实时预览）
    val previewColors = readerColors(prefs.themeId, prefs.customBackgroundArgb, prefs.customTextArgb)
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = previewColors.background,
        contentColor = previewColors.text,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(previewColors.background)
                    .border(1.dp, previewColors.text.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Column {
                    Text(
                        "纸上得来终觉浅，绝知此事要躬行。",
                        color = previewColors.text,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "实时预览 · Aa · 42%",
                        color = previewColors.accent,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(top = 10.dp),
            ) {
                themePresets.forEach { (theme, label) ->
                    val colors = readerColors(theme, prefs.customBackgroundArgb, prefs.customTextArgb)
                    ThemeCard(
                        colors = colors,
                        label = label,
                        selected = prefs.themeId == theme,
                        onClick = { onSelectTheme(theme) },
                    )
                }
            }
            if (prefs.themeId == ReadingTheme.CUSTOM) {
                Text("背景色", style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
                PaletteRow(
                    palette = backgroundPalette,
                    selectedArgb = prefs.customBackgroundArgb,
                    onPick = onPickCustomBackground,
                )
                Text("文字色", style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                PaletteRow(
                    palette = textPalette,
                    selectedArgb = prefs.customTextArgb,
                    onPick = onPickCustomText,
                )
            }
        }
    }
}

@Composable
private fun ThemeCard(
    colors: ReaderColors,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(colors.background)
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) colors.accent else Color(0x33000000),
                    shape = RoundedCornerShape(8.dp),
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Text("文", color = colors.text, style = MaterialTheme.typography.titleSmall)
        }
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun PaletteRow(
    palette: List<Color>,
    selectedArgb: Int?,
    onPick: (Int) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        palette.forEach { color ->
            val argb = color.toArgb()
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(color)
                    .border(
                        width = if (selectedArgb == argb) 2.dp else 1.dp,
                        color = if (selectedArgb == argb) MaterialTheme.colorScheme.primary
                        else Color(0x33000000),
                        shape = CircleShape,
                    )
                    .clickable { onPick(argb) },
            )
        }
    }
}

package com.llzx373.foldreader.feature.reader

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import com.llzx373.foldreader.core.data.settings.ReadingTheme

@Immutable
data class ReaderColors(
    val background: Color,
    val text: Color,
    val accent: Color,
)

fun readerColors(
    theme: ReadingTheme,
    customBackgroundArgb: Int? = null,
    customTextArgb: Int? = null,
): ReaderColors = when (theme) {
    ReadingTheme.GREEN -> ReaderColors(
        background = Color(0xFFCCE8CF),
        text = Color(0xFF1B2A1B),
        accent = Color(0xFF33691E),
    )
    ReadingTheme.PARCHMENT -> ReaderColors(
        background = Color(0xFFF5EBD7),
        text = Color(0xFF3A2F1B),
        accent = Color(0xFF8D6E63),
    )
    ReadingTheme.GRAY_WHITE -> ReaderColors(
        background = Color(0xFFF5F5F5),
        text = Color(0xFF212121),
        accent = Color(0xFF616161),
    )
    ReadingTheme.NIGHT -> ReaderColors(
        background = Color(0xFF2A2A2A),
        text = Color(0xFFB0B0B0),
        accent = Color(0xFF90CAF9),
    )
    ReadingTheme.AMOLED -> ReaderColors(
        background = Color(0xFF000000),
        text = Color(0xFFB0B0B0),
        accent = Color(0xFF90CAF9),
    )
    ReadingTheme.CUSTOM -> ReaderColors(
        background = customBackgroundArgb?.let { Color(it) } ?: Color(0xFFF5F5F5),
        text = customTextArgb?.let { Color(it) } ?: Color(0xFF212121),
        accent = Color(0xFF616161),
    )
}


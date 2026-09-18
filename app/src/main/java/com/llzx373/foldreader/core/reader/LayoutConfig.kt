package com.llzx373.foldreader.core.reader

import android.graphics.Typeface
import androidx.compose.runtime.Immutable

enum class PageTextAlignment { JUSTIFY, LEFT }

/** 不可变排版参数；持有 [Typeface] 使编译器无法自行判定，需显式声明。 */
@Immutable
data class LayoutConfig(
    val fontSizeSp: Float = 18f,
    val lineSpacingMultiplier: Float = 1.5f,
    val letterSpacingEm: Float = 0f,
    val paragraphSpacingEm: Float = 0.4f,
    val marginLeftDp: Float = 16f,
    val marginTopDp: Float = 24f,
    val marginRightDp: Float = 16f,
    val marginBottomDp: Float = 24f,
    val firstLineIndentChars: Int = 2,
    val autoIndentEnabled: Boolean = true,
    /** 空白归一化：折叠段首/行尾/行内多余空白与不可见字符，段首改由标准首行缩进对齐。 */
    val normalizeWhitespaceEnabled: Boolean = false,
    val maxLineChars: Int = 40,
    val alignment: PageTextAlignment = PageTextAlignment.JUSTIFY,
    val fontKey: String? = null,
    val typeface: Typeface? = null,
)

/**
 * 段首是否已带空白缩进（半角/全角空格、制表符、NBSP 等各类 Unicode 空格）。
 * 用 [Char.isWhitespace]（JVM 上等价于 `Character.isWhitespace || isSpaceChar`）而非枚举字符：
 * 只列 `' '`/`'\t'`/`'　'` 会漏掉 NBSP（U+00A0）、EN/EM SPACE 等，这类段首就会被叠加自动缩进。
 * 测量与渲染两侧共用，保证断行与绘制一致。
 */
internal fun hasLeadingIndent(text: CharSequence): Boolean =
    text.isNotEmpty() && text[0].isWhitespace()

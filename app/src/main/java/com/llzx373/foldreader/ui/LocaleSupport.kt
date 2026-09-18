package com.llzx373.foldreader.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import java.util.Locale

/**
 * 取当前界面语言，并且是**可观察**的。
 *
 * 在 Composable 里直接写 `Locale.getDefault()` 拿不到配置变化：用户在应用存活期间切换系统语言后，
 * 已经算好的日期 / 时间文案不会重算。必须经由配置对象读取，重组才会被触发。
 */
@Composable
fun rememberLocale(): Locale = LocalConfiguration.current.locales[0]

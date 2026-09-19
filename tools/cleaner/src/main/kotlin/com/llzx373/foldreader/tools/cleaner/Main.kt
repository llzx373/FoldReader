package com.llzx373.foldreader.tools.cleaner

import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.io.File

/**
 * FoldReader 智能清理 · 桌面验证工具。
 *
 * 清洗逻辑直接复用 `app` 的 `core/format/clean/`（同一批源码，见 `build.gradle.kts`），
 * 所以这里看到的结果就是 App 导入 / 智能整理时的结果。
 *
 * 用法：
 * ```
 * ./gradlew :tools:cleaner:run                                  # 空窗口，手动打开文件
 * ./gradlew :tools:cleaner:run --args="某个.txt"                 # 启动时直接载入
 * ```
 */
fun main(args: Array<String>) {
    val initial = args.firstOrNull()?.let(::File)?.takeIf { it.isFile }

    application {
        val model = remember {
            CleanerModel().also { m -> initial?.let(m::open) }
        }
        Window(
            onCloseRequest = ::exitApplication,
            // 标题带上当前文件名：任务栏一眼能看出开的是哪本
            title = buildString {
                append("FoldReader 智能清理 · 桌面验证工具")
                model.file?.name?.let { append(" — ").append(it) }
            },
            state = rememberWindowState(width = 1400.dp, height = 900.dp),
        ) {
            CleanerApp(model)
        }
    }
}

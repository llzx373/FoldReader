package com.llzx373.foldreader.baselineprofile

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 核心路径性能基准（M35）：冷启动 / 开书 / 翻页，防回归用。
 *
 * 运行（需连接真机或模拟器，见 docs/构建与打包.md）：
 * `./gradlew :baselineprofile:connectedLiteBenchmarkReleaseAndroidTest`——
 * 跑的是 app 的 liteBenchmark 变体（= release 同一份 R8 产物 + debug 签名 + profileable）。
 *
 * 指标解读：StartupTimingMetric 看 timeToInitialDisplay；FrameTimingMetric
 * 看 frameDurationCpuMs 的 P50/P90 与 frameOverrunMs（掉帧）。
 * 数值随设备走，防回归靠同一台设备前后对比，不建绝对阈值。
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class ReadingBenchmarks {

    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun coldStartupToBookshelf() = rule.measureRepeated(
        packageName = "com.llzx373.foldreader",
        metrics = listOf(StartupTimingMetric()),
        compilationMode = CompilationMode.Partial(),
        startupMode = StartupMode.COLD,
        iterations = 8,
    ) {
        pressHome()
        startActivityAndWait()
        device.wait(Until.hasObject(By.pkg("com.llzx373.foldreader")), 5_000)
    }

    @Test
    fun openTextBook() = rule.measureRepeated(
        packageName = "com.llzx373.foldreader",
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(),
        startupMode = null,
        iterations = 8,
        setupBlock = {
            pressHome()
            startActivityAndWait()
            seedBookAndWait()
        },
    ) {
        openSeedBook()
        // 回到书架还原初态，下一轮从同一入口出发
        device.pressBack()
        device.wait(Until.hasObject(By.text(SEED_BOOK_TITLE)), 5_000)
    }

    @Test
    fun pageTurnFrameTiming() = rule.measureRepeated(
        packageName = "com.llzx373.foldreader",
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(),
        startupMode = null,
        iterations = 5,
        setupBlock = {
            startActivityAndWait()
            seedBookAndWait()
            openSeedBook()
        },
    ) {
        // 种子书 30 章 × 60 段，连翻几十页不会到书尾
        repeat(10) { turnPageForward() }
    }
}

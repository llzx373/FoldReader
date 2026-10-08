package com.llzx373.foldreader.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Baseline Profile 生成器（M35）：覆盖三条核心旅程——
 * 冷启动到书架、开书（文本书）、翻页。生成的 baseline-prof.txt 由
 * `androidx.baselineprofile` 消费端插件合并进 release/benchmark 变体的 APK，
 * 安装时 ART 按它预编译热点方法，直接作用于冷启动与首帧。
 *
 * 运行方式（需连接真机或模拟器，见 docs/构建与打包.md）：
 * `./gradlew :app:generateLiteReleaseBaselineProfile`
 *
 * 种子数据：全新安装的书架是空的，生成器先经 benchmark 变体特有的
 * `BenchmarkSeedReceiver` 广播造一本确定性 TXT（标题 [SEED_BOOK_TITLE]），
 * 后续旅程全部基于这本书。
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(
        packageName = "com.llzx373.foldreader",
        includeInStartupProfile = true,
        maxIterations = 15,
    ) {
        pressHome()
        startActivityAndWait()
        seedBookAndWait()
        openSeedBook()
        repeat(6) { turnPageForward() }
    }

    @Test
    fun generateStartupOnly() = rule.collect(
        packageName = "com.llzx373.foldreader",
        includeInStartupProfile = true,
        maxIterations = 15,
    ) {
        pressHome()
        startActivityAndWait()
        device.wait(Until.hasObject(By.pkg("com.llzx373.foldreader")), 5_000)
    }
}

/** 与 app/src/benchmark 的 BenchmarkSeedReceiver 同一口径。 */
const val SEED_BOOK_TITLE = "基准测试样书"
private const val SEED_ACTION = "com.llzx373.foldreader.benchmark.SEED_BOOK"
private const val WAIT_TIMEOUT_MS = 10_000L

/** 广播造书（幂等）并等书架条目出现。 */
fun MacrobenchmarkScope.seedBookAndWait() {
    device.executeShellCommand("am broadcast -a $SEED_ACTION")
    device.wait(Until.hasObject(By.text(SEED_BOOK_TITLE)), WAIT_TIMEOUT_MS)
        ?: error("种子书未在 ${WAIT_TIMEOUT_MS}ms 内出现在书架")
}

/** 从书架点开种子书并等阅读器上屏（章名可见 = 分页完成首屏已绘）。 */
fun MacrobenchmarkScope.openSeedBook() {
    device.findObject(By.text(SEED_BOOK_TITLE))?.click()
    device.wait(Until.hasObject(By.textContains("第1章")), WAIT_TIMEOUT_MS)
        ?: error("开书后 ${WAIT_TIMEOUT_MS}ms 内未见正文")
}

/** 翻一页：点屏幕右侧 1/4 区域（阅读器默认点按热区翻下一页）。 */
fun MacrobenchmarkScope.turnPageForward() {
    val width = device.displayWidth
    val height = device.displayHeight
    device.click(width * 3 / 4, height / 2)
    device.waitForIdle()
}

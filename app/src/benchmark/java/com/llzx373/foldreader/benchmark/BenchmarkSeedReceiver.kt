package com.llzx373.foldreader.benchmark

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.llzx373.foldreader.FoldReaderApplication
import com.llzx373.foldreader.core.data.db.BookSource
import com.llzx373.foldreader.core.format.clean.CleanProfile
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * M35 基准测试的种子数据入口（只存在于 benchmark 构建类型，正式包不含）。
 *
 * 场景：Baseline Profile 生成与 Macrobenchmark 都跑在全新安装的应用上，书架是空的，
 * 「开书 / 翻页」这两条核心旅程无从谈起；而真实导入要走 SAF 系统选择器，UiAutomator
 * 难以稳定驱动。因此开一个 shell 可达的广播入口，收到即生成一本确定性 TXT
 * （[TITLE]，30 章 × 固定段落）并走与真实导入完全相同的 [importBookUseCase] 链路入库：
 *
 * ```
 * adb shell am broadcast -a com.llzx373.foldreader.benchmark.SEED_BOOK
 * ```
 *
 * 重复发送安全：内容哈希不变，第二次起走 DuplicateSameHash 直接返回已有书。
 */
class BenchmarkSeedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val app = context.applicationContext as? FoldReaderApplication ?: return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                seed(app)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun seed(app: FoldReaderApplication) {
        val container = app.container
        val file = File(app.filesDir, "benchmark_seed.txt")
        // 存在即复用（内容哈希不变，重复种子走 DuplicateSameHash 直接返回已有书）
        if (!file.isFile || file.length() == 0L) {
            file.writeText(buildBookText(), Charsets.UTF_8)
        }
        container.importBookUseCase.import(
            uri = Uri.fromFile(file),
            profile = CleanProfile.NONE,
            source = BookSource.IMPORT,
        )
    }

    private fun buildBookText(): String = buildString {
        append(TITLE).append('\n')
        for (chapter in 1..CHAPTERS) {
            append("\n第").append(chapter).append("章 基准章节\n\n")
            repeat(PARAGRAPHS_PER_CHAPTER) { paragraph ->
                append(PARAGRAPH_TEMPLATE.replace("%d", (chapter * 100 + paragraph).toString()))
                    .append('\n')
            }
        }
    }

    companion object {
        const val ACTION = "com.llzx373.foldreader.benchmark.SEED_BOOK"

        /** 种子书标题——基准测试用 UiAutomator 按它定位书架条目。 */
        const val TITLE = "基准测试样书"

        private const val CHAPTERS = 30
        private const val PARAGRAPHS_PER_CHAPTER = 60
        private const val PARAGRAPH_TEMPLATE =
            "　　这是第%d段基准文本。晨光落在旧城的屋檐上，风里带着潮湿的石板气息。" +
                "他沿着河岸慢慢走，数着桥洞下荡开的涟漪，一圈，两圈，直到暮色四合。" +
                "远处传来钟声，惊起一群灰色的鸽子，掠过图书馆尖顶，消失在山的后面。\n"
    }
}

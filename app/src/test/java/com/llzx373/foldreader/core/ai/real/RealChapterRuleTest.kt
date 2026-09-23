package com.llzx373.foldreader.core.ai.real

import com.llzx373.foldreader.core.ai.AiConfig
import com.llzx373.foldreader.core.ai.AiProviderFactory
import com.llzx373.foldreader.core.ai.prompt.ChapterRulePrompt
import com.llzx373.foldreader.core.format.ChapterRulePreview
import com.llzx373.foldreader.core.format.ChapterScanner
import com.llzx373.foldreader.core.format.ChapterTitleSampler
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * M15 章节规则生成全链路真实调用冒烟：采样 → 真实 AI → 解析 → 本地试切。
 * 命中 .env 配置的服务商，会产生极小费用；无 AI_API_KEY 时整组跳过。
 *
 * 测试文本是合成的「数字 + 空格」标题（内置规则库识别不了的那一类），
 * 前提本身有断言锁定：内置规则只能切出「全文」一章。
 */
class RealChapterRuleTest {

    @Test(timeout = 300_000)
    fun `章节规则全链路真实调用`() = runBlocking {
        assumeTrue("无 AI_API_KEY，跳过真实 API 测试", RealApiConfig.apiKey.isNotBlank())
        val model = RealApiConfig.props["AI_MODEL_GENERAL"].orEmpty()
        assumeTrue("无 AI_MODEL_GENERAL，跳过真实 API 测试", model.isNotBlank())

        // 前提：内置规则库对这本书识别失败
        val builtinScan = ChapterScanner()
        builtinScan.feed(BOOK_TEXT)
        assertEquals("前提：内置规则应识别失败", 1, builtinScan.finish().size)

        // 1. 采样
        val sample = ChapterTitleSampler.sample(BOOK_TEXT)
        assertTrue("采样不应为空", sample.isNotEmpty())
        println("[RealChapterRuleTest] 采样 ${sample.size} 行，前 5 行：${sample.take(5)}")

        // 2. 真实 AI 调用
        val config = AiConfig(
            protocol = RealApiConfig.protocol,
            baseUrl = RealApiConfig.baseUrl,
            apiKey = RealApiConfig.apiKey,
        )
        val provider = AiProviderFactory.create(config, AiProviderFactory.defaultClient(60))
        val chunks = provider.chat(ChapterRulePrompt.buildMessages(sample), model).toList()
        val raw = chunks.joinToString("")
        println("[RealChapterRuleTest] 原始响应：$raw")

        // 3. 解析候选
        val candidates = ChapterRulePrompt.parseChapterRuleCandidates(raw)
        candidates.forEach { candidate ->
            val preview = ChapterRulePreview.preview(BOOK_TEXT, candidate.regex)
            println(
                "[RealChapterRuleTest] 候选：${candidate.regex}\n" +
                    "  说明：${candidate.explanation}\n" +
                    "  试切 ${preview.chapterCount} 章，异常：${preview.anomalies}，" +
                    "前几个标题：${preview.titles.take(5)}",
            )
        }
        assertTrue("AI 应给出至少一条可用候选", candidates.isNotEmpty())

        // 4. 本地试切：至少一条候选能切出多章可用目录
        val usable = candidates.map { ChapterRulePreview.preview(BOOK_TEXT, it.regex) }
        assertTrue(
            "至少一条候选应切出多章",
            usable.any { it.chapterCount > 1 },
        )
    }

    private companion object {
        const val CHAPTER_TOTAL = 40

        /** 「001 标题」式章节：内置规则（要求数字后接 、.．）识别不了。 */
        val BOOK_TEXT: String = buildString {
            appendLine("这是一本章节标题不走寻常路的书。开场白先随便聊几句，铺垫一下气氛。")
            appendLine("作者的话：感谢每一位读者。")
            for (i in 1..CHAPTER_TOTAL) {
                appendLine("%03d 第%d段旅程".format(i, i))
                repeat(4) { paragraph ->
                    appendLine(
                        "夜色渐深，旅人们围着篝火低声交谈，谁也没有注意到远方山脊上闪过的微光。" +
                            "这是第 ${i} 章的第 ${paragraph + 1} 段，风从谷口吹进来，带着草木的气息。",
                    )
                }
            }
        }
    }
}

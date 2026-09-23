package com.llzx373.foldreader.feature.bookshelf

import com.llzx373.foldreader.core.ai.AiException
import com.llzx373.foldreader.core.ai.AiMessage
import com.llzx373.foldreader.core.ai.AiProvider
import com.llzx373.foldreader.core.data.settings.ReadingPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * M15「AI 章节规则生成」状态机单测：fake AiProvider + 全 lambda 注入，纯 JVM。
 * 覆盖：首次确认闸门、候选 → 预览组装、空候选降级、AiException 文案透传、选定保存重建。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChapterRuleAiViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeProvider(
        var reply: String = """{"candidates":[{"regex":"^第[0-9]+节.*$","explanation":"第N节标题"}]}""",
        var error: Exception? = null,
    ) : AiProvider {
        val requests = mutableListOf<Pair<List<AiMessage>, String>>()

        override fun chat(messages: List<AiMessage>, model: String): Flow<String> {
            requests += messages to model
            return flow {
                error?.let { throw it }
                emit(reply)
            }
        }
    }

    private class Harness(
        initialPrefs: ReadingPreferences = ReadingPreferences(
            aiBaseUrl = "https://api.test/v1",
            aiModelGeneral = "test-model",
        ),
        fullText: String? = BOOK_TEXT,
    ) {
        val provider = FakeProvider()
        var prefs = initialPrefs
            private set
        val outbound = mutableListOf<Triple<String, String, Int>>()
        val savedRules = mutableListOf<Pair<Long, List<String>>>()
        val rebuilt = mutableListOf<Long>()
        var confirmedMarked = false
            private set

        val viewModel = ChapterRuleAiViewModel(
            aiProvider = { provider },
            loadFullText = { fullText },
            bookTitle = { "测试之书" },
            preferences = { prefs },
            markChapterRuleConfirmed = {
                confirmedMarked = true
                prefs = prefs.copy(aiChapterRuleConfirmed = true)
            },
            recordOutbound = { feature, scope, tokens -> outbound += Triple(feature, scope, tokens) },
            saveChapterRules = { bookId, rules -> savedRules += bookId to rules },
            rebuildChapters = { bookId -> rebuilt += bookId },
            chapterCount = { 42 },
        )
    }

    @Test
    fun `首次使用先停在一次性确认,确认后生成并给出候选预览`() = runTest(dispatcher) {
        val harness = Harness()
        harness.viewModel.start(1L)
        advanceUntilIdle()

        // 停在确认：尚未外发、尚未调用 AI
        val confirm = harness.viewModel.state.value as ChapterRuleAiViewModel.UiState.AwaitConfirmation
        assertEquals("https://api.test/v1", confirm.baseUrl)
        assertEquals(CHAPTER_TOTAL, confirm.sampleLines)
        assertTrue(harness.outbound.isEmpty())
        assertTrue(harness.provider.requests.isEmpty())

        harness.viewModel.confirmAndGenerate()
        advanceUntilIdle()

        assertTrue(harness.confirmedMarked)
        val preview = harness.viewModel.state.value as ChapterRuleAiViewModel.UiState.Preview
        assertEquals(1, preview.candidates.size)
        val candidate = preview.candidates[0]
        assertEquals("^第[0-9]+节.*$", candidate.regex)
        assertEquals("第N节标题", candidate.explanation)
        // 本地真实试切：30 节切出 30 章，无异常
        assertEquals(CHAPTER_TOTAL, candidate.preview.chapterCount)
        assertTrue(candidate.preview.anomalies.isEmpty())
        assertEquals(CHAPTER_PREVIEW_TITLES, candidate.preview.titles.size)
        // 台账：一次外发一条记录，feature/scope/估算 token
        assertEquals(1, harness.outbound.size)
        val (feature, scope, tokens) = harness.outbound[0]
        assertEquals("章节规则", feature)
        assertEquals("测试之书", scope)
        assertTrue(tokens > 0)
        // 模型取通用模型配置
        assertEquals("test-model", harness.provider.requests[0].second)
    }

    @Test
    fun `已确认时跳过确认直接生成`() = runTest(dispatcher) {
        val harness = Harness(
            initialPrefs = ReadingPreferences(
                aiBaseUrl = "https://api.test/v1",
                aiModelGeneral = "test-model",
                aiChapterRuleConfirmed = true,
            ),
        )
        harness.viewModel.start(7L)
        advanceUntilIdle()

        assertTrue(harness.viewModel.state.value is ChapterRuleAiViewModel.UiState.Preview)
        assertFalse(harness.confirmedMarked)
        assertEquals(1, harness.provider.requests.size)
    }

    @Test
    fun `AI 返回空候选时提示失败,修复响应后可重试`() = runTest(dispatcher) {
        val harness = Harness(
            initialPrefs = ReadingPreferences(
                aiBaseUrl = "https://api.test/v1",
                aiModelGeneral = "test-model",
                aiChapterRuleConfirmed = true,
            ),
        )
        harness.provider.reply = "看不出任何规律"
        harness.viewModel.start(1L)
        advanceUntilIdle()

        val failure = harness.viewModel.state.value as ChapterRuleAiViewModel.UiState.Failure
        assertTrue(failure.message.contains("未能给出可用规则"))
        // 空候选也是一次真实外发，台账照记
        assertEquals(1, harness.outbound.size)

        harness.provider.reply =
            """{"candidates":[{"regex":"^第[0-9]+节.*$","explanation":"第N节标题"}]}"""
        harness.viewModel.retry()
        advanceUntilIdle()
        assertTrue(harness.viewModel.state.value is ChapterRuleAiViewModel.UiState.Preview)
        assertEquals(2, harness.outbound.size)
    }

    @Test
    fun `AiException 文案透传且可重试`() = runTest(dispatcher) {
        val harness = Harness(
            initialPrefs = ReadingPreferences(
                aiBaseUrl = "https://api.test/v1",
                aiModelGeneral = "test-model",
                aiChapterRuleConfirmed = true,
            ),
        )
        harness.provider.error = AiException(AiException.Kind.QUOTA, "账户余额不足或额度已用尽")
        harness.viewModel.start(1L)
        advanceUntilIdle()

        val failure = harness.viewModel.state.value as ChapterRuleAiViewModel.UiState.Failure
        assertEquals("账户余额不足或额度已用尽", failure.message)

        harness.provider.error = null
        harness.viewModel.retry()
        advanceUntilIdle()
        assertTrue(harness.viewModel.state.value is ChapterRuleAiViewModel.UiState.Preview)
    }

    @Test
    fun `选定候选后保存本书规则并重建目录`() = runTest(dispatcher) {
        val harness = Harness(
            initialPrefs = ReadingPreferences(
                aiBaseUrl = "https://api.test/v1",
                aiModelGeneral = "test-model",
                aiChapterRuleConfirmed = true,
            ),
        )
        harness.viewModel.start(9L)
        advanceUntilIdle()
        val preview = harness.viewModel.state.value as ChapterRuleAiViewModel.UiState.Preview

        harness.viewModel.select(preview.candidates[0])
        advanceUntilIdle()

        assertEquals(listOf(9L to listOf("^第[0-9]+节.*$")), harness.savedRules)
        assertEquals(listOf(9L), harness.rebuilt)
        val done = harness.viewModel.state.value as ChapterRuleAiViewModel.UiState.Done
        assertEquals(42, done.chapterCount)
    }

    @Test
    fun `正文读不出来时按不支持处理`() = runTest(dispatcher) {
        val harness = Harness(fullText = null)
        harness.viewModel.start(1L)
        advanceUntilIdle()

        val failure = harness.viewModel.state.value as ChapterRuleAiViewModel.UiState.Failure
        assertTrue(failure.message.contains("仅支持 TXT"))
        assertTrue(harness.outbound.isEmpty())
        assertTrue(harness.provider.requests.isEmpty())
    }

    private companion object {
        const val CHAPTER_TOTAL = 30
        const val CHAPTER_PREVIEW_TITLES = 20

        /** 内置规则识别不了的标题样式（数字 + 空格，无顿号/点），采样器会全部保留。 */
        val BOOK_TEXT: String = buildString {
            appendLine("这是一本标题不走寻常路的书，开头有些杂项。")
            for (i in 1..CHAPTER_TOTAL) {
                appendLine("第${i}节 故事 $i")
                appendLine("正文内容 $i。".repeat(20))
            }
        }
    }
}

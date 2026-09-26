package com.llzx373.foldreader.feature.bookshelf

import com.llzx373.foldreader.core.ai.AiException
import com.llzx373.foldreader.core.ai.AiMessage
import com.llzx373.foldreader.core.ai.AiProvider
import com.llzx373.foldreader.core.data.settings.ReadingPreferences
import com.llzx373.foldreader.core.format.clean.CleanLevel
import com.llzx373.foldreader.core.format.clean.CleanToggles
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
 * M16「AI 清洗配方推荐」状态机单测：fake AiProvider + 全 lambda 注入，纯 JVM。
 * 覆盖：首次确认闸门、配方组装（标准档基线 + 覆盖 + 全局广告正则并入）、
 * 无效响应降级、AiException 文案透传、台账记录。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CleanRecipeAiViewModelTest {

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
        var reply: String = """
            {"toggles": {"normalizeRepeatedPunctuation": true},
             "adPatterns": ["^更多精彩.*$"],
             "explanation": "尾部有推广行，重复标点多"}
        """.trimIndent(),
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
        var confirmedMarked = false
            private set

        val viewModel = CleanRecipeAiViewModel(
            aiProvider = { provider },
            loadFullText = { fullText },
            bookTitle = { "测试之书" },
            preferences = { prefs },
            markCleanRecipeConfirmed = {
                confirmedMarked = true
                prefs = prefs.copy(aiCleanRecipeConfirmed = true)
            },
            recordOutbound = { feature, scope, tokens -> outbound += Triple(feature, scope, tokens) },
        )
    }

    @Test
    fun `首次使用先停在一次性确认,确认后生成并给出配方`() = runTest(dispatcher) {
        val harness = Harness()
        harness.viewModel.start(1L)
        advanceUntilIdle()

        // 停在确认：尚未外发、尚未调用 AI
        val confirm = harness.viewModel.state.value as CleanRecipeAiViewModel.UiState.AwaitConfirmation
        assertEquals("https://api.test/v1", confirm.baseUrl)
        assertTrue(confirm.sampleChars > 0)
        assertTrue(harness.outbound.isEmpty())
        assertTrue(harness.provider.requests.isEmpty())

        harness.viewModel.confirmAndGenerate()
        advanceUntilIdle()

        assertTrue(harness.confirmedMarked)
        val ready = harness.viewModel.state.value as CleanRecipeAiViewModel.UiState.Ready
        // 配方：CUSTOM 档、标准档基线 + 建议覆盖、广告正则编译进配方
        assertEquals(CleanLevel.CUSTOM, ready.profile.level)
        assertTrue(ready.profile.toggles.normalizeRepeatedPunctuation)
        assertTrue(ready.profile.toggles.filterNoise)
        assertEquals(listOf("^更多精彩.*$"), ready.profile.adPatterns.map { it.pattern })
        assertEquals("尾部有推广行，重复标点多", ready.explanation)
        assertTrue(ready.summaryLines.any { it.contains("重复标点折叠") })
        assertTrue(ready.summaryLines.any { it.contains("广告正则") })
        // 台账：一次外发一条记录
        assertEquals(1, harness.outbound.size)
        val (feature, scope, tokens) = harness.outbound[0]
        assertEquals("清洗配方", feature)
        assertEquals("测试之书", scope)
        assertTrue(tokens > 0)
        // 模型取通用模型配置；外发的是采样而非全文
        assertEquals("test-model", harness.provider.requests[0].second)
    }

    @Test
    fun `已确认时跳过确认直接生成`() = runTest(dispatcher) {
        val harness = Harness(
            initialPrefs = ReadingPreferences(
                aiBaseUrl = "https://api.test/v1",
                aiModelGeneral = "test-model",
                aiCleanRecipeConfirmed = true,
            ),
        )
        harness.viewModel.start(7L)
        advanceUntilIdle()

        assertTrue(harness.viewModel.state.value is CleanRecipeAiViewModel.UiState.Ready)
        assertFalse(harness.confirmedMarked)
        assertEquals(1, harness.provider.requests.size)
    }

    @Test
    fun `全局自定义广告正则并入配方`() = runTest(dispatcher) {
        val harness = Harness(
            initialPrefs = ReadingPreferences(
                aiBaseUrl = "https://api.test/v1",
                aiModelGeneral = "test-model",
                aiCleanRecipeConfirmed = true,
                adCleanRules = listOf("^全局广告$", "[坏掉的"),
            ),
        )
        harness.viewModel.start(1L)
        advanceUntilIdle()

        val ready = harness.viewModel.state.value as CleanRecipeAiViewModel.UiState.Ready
        assertEquals(listOf("^更多精彩.*$", "^全局广告$"), ready.profile.adPatterns.map { it.pattern })
    }

    @Test
    fun `AI 响应无效时提示失败,修复响应后可重试`() = runTest(dispatcher) {
        val harness = Harness(
            initialPrefs = ReadingPreferences(
                aiBaseUrl = "https://api.test/v1",
                aiModelGeneral = "test-model",
                aiCleanRecipeConfirmed = true,
            ),
        )
        harness.provider.reply = "这本书很干净，不用洗"
        harness.viewModel.start(1L)
        advanceUntilIdle()

        val failure = harness.viewModel.state.value as CleanRecipeAiViewModel.UiState.Failure
        assertTrue(failure.message.contains("未能给出可用建议"))
        // 无效响应也是一次真实外发，台账照记
        assertEquals(1, harness.outbound.size)

        harness.provider.reply = """{"explanation": "样本规整，标准档即可"}"""
        harness.viewModel.retry()
        advanceUntilIdle()
        val ready = harness.viewModel.state.value as CleanRecipeAiViewModel.UiState.Ready
        // 仅说明、无覆盖：配方即标准档基线
        assertEquals(
            CleanToggles.preset(CleanLevel.STANDARD),
            ready.profile.toggles,
        )
        assertEquals(2, harness.outbound.size)
    }

    @Test
    fun `AiException 文案透传且可重试`() = runTest(dispatcher) {
        val harness = Harness(
            initialPrefs = ReadingPreferences(
                aiBaseUrl = "https://api.test/v1",
                aiModelGeneral = "test-model",
                aiCleanRecipeConfirmed = true,
            ),
        )
        harness.provider.error = AiException(AiException.Kind.QUOTA, "账户余额不足或额度已用尽")
        harness.viewModel.start(1L)
        advanceUntilIdle()

        val failure = harness.viewModel.state.value as CleanRecipeAiViewModel.UiState.Failure
        assertEquals("账户余额不足或额度已用尽", failure.message)

        harness.provider.error = null
        harness.viewModel.retry()
        advanceUntilIdle()
        assertTrue(harness.viewModel.state.value is CleanRecipeAiViewModel.UiState.Ready)
    }

    @Test
    fun `正文读不出来时按不支持处理`() = runTest(dispatcher) {
        val harness = Harness(fullText = null)
        harness.viewModel.start(1L)
        advanceUntilIdle()

        val failure = harness.viewModel.state.value as CleanRecipeAiViewModel.UiState.Failure
        assertTrue(failure.message.contains("仅支持 TXT"))
        assertTrue(harness.outbound.isEmpty())
        assertTrue(harness.provider.requests.isEmpty())
    }

    private companion object {
        /** 含头尾广告行的脏文本，采样器必采到头尾两段。 */
        val BOOK_TEXT: String = buildString {
            appendLine("本站网址 www.example.com 请收藏")
            repeat(300) { appendLine("正文段落 $it，".repeat(30)) }
            appendLine("全书完 更多精彩小说尽在 example")
        }
    }
}

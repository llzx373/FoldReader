package com.llzx373.foldreader.feature.bookshelf

import com.llzx373.foldreader.core.ai.AiMessage
import com.llzx373.foldreader.core.ai.AiProvider
import com.llzx373.foldreader.core.ai.prompt.ProofreadIssue
import com.llzx373.foldreader.core.data.db.AnnotationEntity
import com.llzx373.foldreader.core.data.settings.ReadingPreferences
import com.llzx373.foldreader.core.format.Chapter
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
 * M30「AI 校对」执行链路单测：fake AiProvider + 全 lambda 注入，纯 JVM。
 * 覆盖：首次确认闸门、按单位送校与批注落库（全书坐标换算）、只标不改、
 * 畸形输出重试后记失败单位继续、未配置 AI 零外发零台账、重跑清旧批注。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProofreadAiViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** 按 USER 消息里的正文内容回不同答复的假 Provider。 */
    private class FakeProvider : AiProvider {
        val requests = mutableListOf<List<AiMessage>>()
        var replies: (String) -> String = { "[]" }

        override fun chat(messages: List<AiMessage>, model: String): Flow<String> {
            requests += messages
            val user = messages.last().content
                .filterIsInstance<com.llzx373.foldreader.core.ai.AiContent.Text>()
                .joinToString("\n") { it.text }
            return flow { emit(replies(user)) }
        }
    }

    private class Harness(
        initialPrefs: ReadingPreferences = ReadingPreferences(
            aiBaseUrl = "https://api.test/v1",
            aiModelGeneral = "test-model",
        ),
        fullText: String? = TEXT,
        chapters: List<Chapter> = emptyList(),
        hasProvider: Boolean = true,
    ) {
        val provider = FakeProvider()
        var prefs = initialPrefs
            private set
        val outbound = mutableListOf<Triple<String, String, Int>>()
        val annotations = mutableListOf<AnnotationEntity>()
        var confirmedMarked = false
            private set
        var clearedForBook: Long? = null
            private set

        val viewModel = ProofreadAiViewModel(
            aiProvider = { if (hasProvider) provider else null },
            loadFullText = { fullText },
            chaptersFor = { chapters },
            bookTitle = { "测试之书" },
            preferences = { prefs },
            markProofreadConfirmed = {
                confirmedMarked = true
                prefs = prefs.copy(aiProofreadConfirmed = true)
            },
            recordOutbound = { feature, scope, tokens -> outbound += Triple(feature, scope, tokens) },
            clearProofreadAnnotations = { clearedForBook = it },
            addAnnotation = { annotations += it },
        )
    }

    @Test
    fun `首次使用先停在一次性确认,确认后按单位送校并落批注`() = runTest(dispatcher) {
        val harness = Harness()
        harness.provider.replies = { user ->
            val offset = user.indexOf("的")
            """[{"offset": $offset, "length": 1, "original": "的", "suggestion": "得", "type": "typo"}]"""
        }
        harness.viewModel.start(1L)
        advanceUntilIdle()

        // 停在确认：尚未外发、尚未写批注
        val confirm = harness.viewModel.state.value as ProofreadAiViewModel.UiState.AwaitConfirmation
        assertEquals("https://api.test/v1", confirm.baseUrl)
        assertTrue(confirm.totalChars > 0)
        assertTrue(harness.outbound.isEmpty())
        assertTrue(harness.provider.requests.isEmpty())
        assertTrue(harness.annotations.isEmpty())

        harness.viewModel.confirmAndRun()
        advanceUntilIdle()

        assertTrue(harness.confirmedMarked)
        val done = harness.viewModel.state.value as ProofreadAiViewModel.UiState.Done
        assertEquals(1, done.issues.size)
        assertEquals(0, done.failedUnits)
        // 批注：全书坐标 = 单位起点 + 单位内偏移（这里全书一个单位，起点 0）
        assertEquals(1, harness.annotations.size)
        val annotation = harness.annotations[0]
        assertEquals(TEXT.indexOf("的").toLong(), annotation.startCharOffset)
        assertEquals(annotation.startCharOffset + 1, annotation.endCharOffset)
        assertEquals("的", annotation.selectedText)
        assertTrue(annotation.note!!.contains("得"))
        assertTrue(annotation.note!!.startsWith(ProofreadAiViewModel.NOTE_PREFIX))
        // 台账按单位记 feature=AI 校对
        assertEquals(1, harness.outbound.size)
        assertEquals("AI 校对", harness.outbound[0].first)
        assertTrue(harness.outbound[0].second.contains("测试之书"))
    }

    @Test
    fun `多章书按章送校,批注偏移换算到全书坐标`() = runTest(dispatcher) {
        val chapterText = "他走的很快。"
        val text = chapterText + chapterText
        val chapters = listOf(
            Chapter("第一章", 0L, chapterText.length.toLong()),
            Chapter("第二章", chapterText.length.toLong(), text.length.toLong()),
        )
        val harness = Harness(
            initialPrefs = ReadingPreferences(
                aiBaseUrl = "https://api.test/v1",
                aiModelGeneral = "test-model",
                aiProofreadConfirmed = true,
            ),
            fullText = text,
            chapters = chapters,
        )
        harness.provider.replies = { user ->
            val offset = user.indexOf("的")
            """[{"offset": $offset, "length": 1, "original": "的", "suggestion": "得", "type": "typo"}]"""
        }
        harness.viewModel.start(1L)
        advanceUntilIdle()

        val done = harness.viewModel.state.value as ProofreadAiViewModel.UiState.Done
        assertEquals(2, done.issues.size)
        assertEquals(2, harness.provider.requests.size)
        assertEquals(2, harness.outbound.size)
        // 第二章的批注落在全书坐标（加上第一章长度）
        assertEquals(
            listOf(
                chapterText.indexOf("的").toLong(),
                chapterText.length + chapterText.indexOf("的").toLong(),
            ),
            harness.annotations.map { it.startCharOffset },
        )
    }

    @Test
    fun `未配置 AI 时直接失败,零外发零台账零批注`() = runTest(dispatcher) {
        val harness = Harness(
            initialPrefs = ReadingPreferences(
                aiBaseUrl = "https://api.test/v1",
                aiModelGeneral = "test-model",
                aiProofreadConfirmed = true,
            ),
            hasProvider = false,
        )
        harness.viewModel.start(1L)
        advanceUntilIdle()

        val failure = harness.viewModel.state.value as ProofreadAiViewModel.UiState.Failure
        assertTrue(failure.message.contains("AI 服务配置"))
        assertTrue(harness.outbound.isEmpty())
        assertTrue(harness.annotations.isEmpty())
    }

    @Test
    fun `畸形输出重试一次后记失败单位,不影响后续单位`() = runTest(dispatcher) {
        val chapterText = "他走的很快。"
        val text = chapterText + chapterText
        val chapters = listOf(
            Chapter("第一章", 0L, chapterText.length.toLong()),
            Chapter("第二章", chapterText.length.toLong(), text.length.toLong()),
        )
        val harness = Harness(
            initialPrefs = ReadingPreferences(
                aiBaseUrl = "https://api.test/v1",
                aiModelGeneral = "test-model",
                aiProofreadConfirmed = true,
            ),
            fullText = text,
            chapters = chapters,
        )
        harness.provider.replies = { user ->
            if (user.startsWith(chapterText)) {
                // 两章正文相同，用请求序号区分：第一章两次都畸形（首次+重试），第二章正常
                if (harness.provider.requests.size <= 2) "看不出哪里错了" else "[]"
            } else {
                "[]"
            }
        }
        harness.viewModel.start(1L)
        advanceUntilIdle()

        val done = harness.viewModel.state.value as ProofreadAiViewModel.UiState.Done
        assertEquals(1, done.failedUnits)
        assertEquals(0, done.issues.size)
        // 第一章 2 次（重试一次），第二章 1 次；台账按单位记（单位内重试不重复记，与摘要口径一致）
        assertEquals(3, harness.provider.requests.size)
        assertEquals(2, harness.outbound.size)
    }

    @Test
    fun `重跑先清上一轮的校对批注`() = runTest(dispatcher) {
        val harness = Harness(
            initialPrefs = ReadingPreferences(
                aiBaseUrl = "https://api.test/v1",
                aiModelGeneral = "test-model",
                aiProofreadConfirmed = true,
            ),
        )
        harness.viewModel.start(42L)
        advanceUntilIdle()

        assertEquals(42L, harness.clearedForBook)
    }

    @Test
    fun `正文读不出来时按不支持处理`() = runTest(dispatcher) {
        val harness = Harness(fullText = null)
        harness.viewModel.start(1L)
        advanceUntilIdle()

        val failure = harness.viewModel.state.value as ProofreadAiViewModel.UiState.Failure
        assertTrue(failure.message.contains("仅支持 TXT"))
        assertTrue(harness.outbound.isEmpty())
        assertTrue(harness.provider.requests.isEmpty())
    }

    @Test
    fun `确认列表按原文与建议合并计数`() {
        val issues = listOf(
            ProofreadAiViewModel.IssueEntry(1, 1, "的", "得", ProofreadIssue.Type.TYPO, "第一章"),
            ProofreadAiViewModel.IssueEntry(9, 1, "的", "得", ProofreadIssue.Type.TYPO, "第二章"),
            ProofreadAiViewModel.IssueEntry(20, 2, "即", "既", ProofreadIssue.Type.TYPO, "第三章"),
        )
        val confirmations = ProofreadAiViewModel.confirmationsOf(issues)

        assertEquals(2, confirmations.size)
        val first = confirmations.first { it.original == "的" }
        assertEquals(2, first.occurrences)
        assertTrue(first.checked)
    }

    @Test
    fun `逐条勾选生成清洗配方,纯替换规则不加清洗开关`() = runTest(dispatcher) {
        val harness = Harness(
            initialPrefs = ReadingPreferences(
                aiBaseUrl = "https://api.test/v1",
                aiModelGeneral = "test-model",
                aiProofreadConfirmed = true,
            ),
        )
        harness.provider.replies = { user ->
            val a = user.indexOf("的")
            val b = user.indexOf("即")
            """[
                {"offset": $a, "length": 1, "original": "的", "suggestion": "得", "type": "typo"},
                {"offset": $b, "length": 1, "original": "即", "suggestion": "既", "type": "typo"}
            ]"""
        }
        harness.viewModel.start(1L)
        advanceUntilIdle()

        // 取消第一条（的→得），只留 即→既
        harness.viewModel.toggleConfirmation(0)
        val profile = harness.viewModel.buildRecipeProfile()!!

        assertEquals(CleanLevel.CUSTOM, profile.level)
        assertEquals(CleanToggles.NONE, profile.toggles)
        assertTrue(profile.adPatterns.isEmpty())
        assertEquals(1, profile.replacements.size)
        assertEquals(Regex.escape("即"), profile.replacements[0].first.pattern)
        assertEquals("既", profile.replacements[0].second)
        assertFalse(profile.isNoop)

        // 全部取消 → 没有配方
        harness.viewModel.toggleConfirmation(1)
        assertTrue(harness.viewModel.buildRecipeProfile() == null)
    }

    @Test
    fun `配方过滤不幂等的规则,建议包含原文的规则丢弃`() = runTest(dispatcher) {
        val harness = Harness(
            initialPrefs = ReadingPreferences(
                aiBaseUrl = "https://api.test/v1",
                aiModelGeneral = "test-model",
                aiProofreadConfirmed = true,
            ),
        )
        // 第二条建议「的得」包含原文「的」——再跑一遍会变成「得得」，不幂等，应被过滤
        harness.provider.replies = { user ->
            val a = user.indexOf("的")
            val b = user.indexOf("即")
            """[
                {"offset": $a, "length": 1, "original": "的", "suggestion": "的得", "type": "typo"},
                {"offset": $b, "length": 1, "original": "即", "suggestion": "既", "type": "typo"}
            ]"""
        }
        harness.viewModel.start(1L)
        advanceUntilIdle()

        val profile = harness.viewModel.buildRecipeProfile()!!
        assertEquals(1, profile.replacements.size)
        assertEquals(Regex.escape("即"), profile.replacements[0].first.pattern)
    }

    @Test
    fun `未发现问题的书正常完成且无批注`() = runTest(dispatcher) {
        val harness = Harness(
            initialPrefs = ReadingPreferences(
                aiBaseUrl = "https://api.test/v1",
                aiModelGeneral = "test-model",
                aiProofreadConfirmed = true,
            ),
        )
        harness.provider.replies = { "[]" }
        harness.viewModel.start(1L)
        advanceUntilIdle()

        val done = harness.viewModel.state.value as ProofreadAiViewModel.UiState.Done
        assertTrue(done.issues.isEmpty())
        assertTrue(harness.annotations.isEmpty())
        assertFalse(harness.outbound.isEmpty()) // 外发过就有台账，哪怕结果为空
    }

    private companion object {
        const val TEXT = "他走的很快，心里即高兴又紧张。明天还要赶路。"
    }
}

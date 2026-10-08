package com.llzx373.foldreader.feature.reader

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 「选中行生成章节规则」状态机单测：全 lambda 注入，纯 JVM。
 * 覆盖：吸附行 + 候选试切组装、选定保存重建、正文读不出 / 行不合法的失败路径。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChapterRuleFromSelectionViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class Harness(
        fullText: String? = BOOK_TEXT,
    ) {
        val savedRules = mutableListOf<Pair<Long, List<String>>>()
        val rebuilt = mutableListOf<Long>()

        val viewModel = ChapterRuleFromSelectionViewModel(
            loadFullText = { fullText },
            saveChapterRules = { bookId, rules -> savedRules += bookId to rules },
            rebuildChapters = { bookId -> rebuilt += bookId },
            chapterCount = { 42 },
        )
    }

    @Test
    fun `选中标题行后给出泛化与精确候选并附试切报告`() = runTest(dispatcher) {
        val harness = Harness()
        harness.viewModel.start(1L, BOOK_TEXT.indexOf("第5节").toLong())
        advanceUntilIdle()

        val preview = harness.viewModel.state.value as ChapterRuleFromSelectionViewModel.UiState.Preview
        assertEquals("第5节 故事 5", preview.sourceLine)
        assertEquals(2, preview.candidates.size)

        // 泛化候选（推荐，排前）：30 节全部切出，无异常
        val generalized = preview.candidates[0]
        assertTrue(generalized.explanation.contains("泛化"))
        assertEquals(CHAPTER_TOTAL, generalized.preview.chapterCount)
        assertTrue(generalized.preview.anomalies.isEmpty())
        assertTrue(Regex(generalized.regex).matches("第6节 故事 6"))

        // 精确候选：只命中第 5 节一行，试切报告带"仅切出 1 章"异常
        val exact = preview.candidates[1]
        assertTrue(exact.explanation.contains("精确"))
        assertEquals(1, exact.preview.chapterCount)
        assertTrue(exact.preview.anomalies.isNotEmpty())
        assertTrue(Regex(exact.regex).matches("第5节 故事 5"))
        assertTrue(!Regex(exact.regex).matches("第6节 故事 6"))
    }

    @Test
    fun `选定候选后保存本书规则并重建目录`() = runTest(dispatcher) {
        val harness = Harness()
        harness.viewModel.start(9L, BOOK_TEXT.indexOf("第5节").toLong())
        advanceUntilIdle()
        val preview = harness.viewModel.state.value as ChapterRuleFromSelectionViewModel.UiState.Preview

        harness.viewModel.select(preview.candidates[0])
        advanceUntilIdle()

        assertEquals(listOf(9L to listOf(preview.candidates[0].regex)), harness.savedRules)
        assertEquals(listOf(9L), harness.rebuilt)
        val done = harness.viewModel.state.value as ChapterRuleFromSelectionViewModel.UiState.Done
        assertEquals(42, done.chapterCount)
    }

    @Test
    fun `无数字的行只给出精确候选`() = runTest(dispatcher) {
        val harness = Harness()
        harness.viewModel.start(1L, BOOK_TEXT.indexOf("这是一本").toLong())
        advanceUntilIdle()

        val preview = harness.viewModel.state.value as ChapterRuleFromSelectionViewModel.UiState.Preview
        assertEquals("这是一本标题不走寻常路的书，开头有些杂项。", preview.sourceLine)
        assertEquals(1, preview.candidates.size)
        assertTrue(preview.candidates[0].explanation.contains("精确"))
    }

    @Test
    fun `正文读不出来时按不支持处理`() = runTest(dispatcher) {
        val harness = Harness(fullText = null)
        harness.viewModel.start(1L, 0L)
        advanceUntilIdle()

        val failure = harness.viewModel.state.value as ChapterRuleFromSelectionViewModel.UiState.Failure
        assertTrue(failure.message.contains("仅支持 TXT"))
        assertTrue(harness.savedRules.isEmpty())
    }

    @Test
    fun `选中空白行时提示定位不到有效行`() = runTest(dispatcher) {
        val harness = Harness(fullText = "abc\n\ndef")
        harness.viewModel.start(1L, 4L)
        advanceUntilIdle()

        val failure = harness.viewModel.state.value as ChapterRuleFromSelectionViewModel.UiState.Failure
        assertTrue(failure.message.contains("定位不到有效行"))
    }

    @Test
    fun `选中超长行时提示不像章节标题`() = runTest(dispatcher) {
        val longLine = "正文内容。".repeat(20)
        val harness = Harness(fullText = "$longLine\n第1节 故事 1")
        harness.viewModel.start(1L, 5L)
        advanceUntilIdle()

        val failure = harness.viewModel.state.value as ChapterRuleFromSelectionViewModel.UiState.Failure
        assertTrue(failure.message.contains("过长"))
    }

    private companion object {
        const val CHAPTER_TOTAL = 30

        /** 内置规则识别不了的标题样式（数字 + 空格，无顿号/点）。 */
        val BOOK_TEXT: String = buildString {
            appendLine("这是一本标题不走寻常路的书，开头有些杂项。")
            for (i in 1..CHAPTER_TOTAL) {
                appendLine("第${i}节 故事 $i")
                appendLine("正文内容 $i。".repeat(20))
            }
        }
    }
}

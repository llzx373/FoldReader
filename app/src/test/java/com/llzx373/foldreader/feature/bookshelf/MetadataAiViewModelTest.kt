package com.llzx373.foldreader.feature.bookshelf

import com.llzx373.foldreader.core.ai.AiException
import com.llzx373.foldreader.core.ai.AiMessage
import com.llzx373.foldreader.core.ai.AiProvider
import com.llzx373.foldreader.core.data.settings.ReadingPreferences
import com.llzx373.foldreader.core.metadata.AiMetadataWrite
import com.llzx373.foldreader.core.metadata.BookMetaSnapshot
import com.llzx373.foldreader.core.metadata.BookMetaSources
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * M17「AI 元数据补全」状态机单测：fake AiProvider + 全 lambda 注入，纯 JVM。
 * 覆盖：首次确认闸门、只补空且未锁定字段、已完整不打 AI、非 TXT 降级、
 * 无效响应重试、批量串行汇总与中途取消、台账 feature=元数据补全。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MetadataAiViewModelTest {

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
        var reply: String = """{"author": "AI 作者", "synopsis": "AI 简介。", "genreTag": "科幻"}""",
        var error: Exception? = null,
        /** 命中该书名（USER 消息含「文件名：<title>」）时抛错，批量里模拟单本失败。 */
        var failOnTitle: String? = null,
        /** 非空时 chat 挂起在此闸门上（取消测试用）。 */
        var gate: CompletableDeferred<Unit>? = null,
    ) : AiProvider {
        val requests = mutableListOf<Pair<List<AiMessage>, String>>()

        override fun chat(messages: List<AiMessage>, model: String): Flow<String> {
            requests += messages to model
            return flow {
                gate?.await()
                error?.let { throw it }
                val userText = messages.last().content.toString()
                if (failOnTitle != null && userText.contains(failOnTitle!!)) {
                    throw AiException(AiException.Kind.NETWORK, "模拟失败")
                }
                emit(reply)
            }
        }
    }

    private class Harness(
        initialPrefs: ReadingPreferences = ReadingPreferences(
            aiBaseUrl = "https://api.test/v1",
            aiModelGeneral = "test-model",
        ),
        samples: Map<Long, String?> = mapOf(1L to HEAD_TEXT, 2L to HEAD_TEXT, 3L to null),
        metas: Map<Long, BookMetaSnapshot> = mapOf(
            1L to BookMetaSnapshot(null, null, null, ""),
            2L to BookMetaSnapshot(null, null, null, ""),
            3L to BookMetaSnapshot(null, null, null, ""),
        ),
    ) {
        val provider = FakeProvider()
        var prefs = initialPrefs
            private set
        val outbound = mutableListOf<Triple<String, String, Int>>()
        var confirmedMarked = false
            private set
        val writes = mutableListOf<Pair<Long, AiMetadataWrite>>()
        val currentMetas = metas.toMutableMap()

        val viewModel = MetadataAiViewModel(
            aiProvider = { provider },
            sampleHead = { id -> samples[id] },
            bookMeta = { id -> currentMetas[id] },
            bookTitle = { id -> "书$id" },
            preferences = { prefs },
            markMetadataConfirmed = {
                confirmedMarked = true
                prefs = prefs.copy(aiMetadataConfirmed = true)
            },
            recordOutbound = { feature, scope, tokens -> outbound += Triple(feature, scope, tokens) },
            applyWrite = { id, write ->
                writes += id to write
                val cur = currentMetas.getValue(id)
                currentMetas[id] = BookMetaSnapshot(
                    author = write.author ?: cur.author,
                    synopsis = write.synopsis ?: cur.synopsis,
                    genreTag = write.genreTag ?: cur.genreTag,
                    metaSource = write.metaSource,
                )
            },
        )
    }

    @Test
    fun `首次使用先停在一次性确认,确认后补全并打台账`() = runTest(dispatcher) {
        val harness = Harness()
        harness.viewModel.start(1L)
        advanceUntilIdle()

        // 停在确认：尚未外发、尚未调用 AI；范围明示「开头采样」
        val confirm = harness.viewModel.state.value as MetadataAiViewModel.UiState.AwaitConfirmation
        assertEquals("https://api.test/v1", confirm.baseUrl)
        assertTrue(confirm.scopeText.contains("开头"))
        assertTrue(harness.outbound.isEmpty())
        assertTrue(harness.provider.requests.isEmpty())

        harness.viewModel.confirmAndGenerate()
        advanceUntilIdle()

        assertTrue(harness.confirmedMarked)
        val done = harness.viewModel.state.value as MetadataAiViewModel.UiState.Done
        assertEquals(
            listOf(
                BookMetaSources.FIELD_AUTHOR,
                BookMetaSources.FIELD_SYNOPSIS,
                BookMetaSources.FIELD_GENRE,
            ),
            done.filledFields,
        )
        val (id, write) = harness.writes.single()
        assertEquals(1L, id)
        assertEquals("AI 作者", write.author)
        assertEquals("科幻", write.genreTag)
        assertTrue(BookMetaSources.isAiGenerated(write.metaSource, BookMetaSources.FIELD_AUTHOR))
        // 台账：一次外发一条记录，feature=元数据补全
        assertEquals(1, harness.outbound.size)
        val (feature, scope, tokens) = harness.outbound[0]
        assertEquals("元数据补全", feature)
        assertEquals("书1", scope)
        assertTrue(tokens > 0)
        assertEquals("test-model", harness.provider.requests[0].second)
    }

    @Test
    fun `已确认时跳过确认直接生成`() = runTest(dispatcher) {
        val harness = Harness(
            initialPrefs = ReadingPreferences(
                aiBaseUrl = "https://api.test/v1",
                aiModelGeneral = "test-model",
                aiMetadataConfirmed = true,
            ),
        )
        harness.viewModel.start(1L)
        advanceUntilIdle()

        assertTrue(harness.viewModel.state.value is MetadataAiViewModel.UiState.Done)
        assertFalse(harness.confirmedMarked)
        assertEquals(1, harness.provider.requests.size)
    }

    @Test
    fun `用户锁定与已有字段不被覆盖,其余照补`() = runTest(dispatcher) {
        val harness = Harness(
            initialPrefs = ReadingPreferences(
                aiBaseUrl = "https://api.test/v1",
                aiModelGeneral = "test-model",
                aiMetadataConfirmed = true,
            ),
            metas = mapOf(
                // 作者被用户清空锁定；简介已有值；题材空
                1L to BookMetaSnapshot(null, "已有简介", null, "author:user"),
            ),
        )
        harness.viewModel.start(1L)
        advanceUntilIdle()

        val done = harness.viewModel.state.value as MetadataAiViewModel.UiState.Done
        assertEquals(listOf(BookMetaSources.FIELD_GENRE), done.filledFields)
        val write = harness.writes.single().second
        assertNull(write.author) // 锁定的空字段不补
        assertNull(write.synopsis) // 已有值不动
        assertEquals("科幻", write.genreTag)
        assertTrue(BookMetaSources.isUserOwned(write.metaSource, BookMetaSources.FIELD_AUTHOR))
    }

    @Test
    fun `字段已完整时不打 AI 直接完成`() = runTest(dispatcher) {
        val harness = Harness(
            initialPrefs = ReadingPreferences(
                aiBaseUrl = "https://api.test/v1",
                aiModelGeneral = "test-model",
                aiMetadataConfirmed = true,
            ),
            metas = mapOf(1L to BookMetaSnapshot("作者", "简介", "玄幻", "")),
        )
        harness.viewModel.start(1L)
        advanceUntilIdle()

        val done = harness.viewModel.state.value as MetadataAiViewModel.UiState.Done
        assertTrue(done.filledFields.isEmpty())
        assertTrue(harness.provider.requests.isEmpty())
        assertTrue(harness.outbound.isEmpty())
        assertTrue(harness.writes.isEmpty())
    }

    @Test
    fun `读不出开头文本时按仅支持 TXT 处理`() = runTest(dispatcher) {
        val harness = Harness(samples = mapOf(1L to null))
        harness.viewModel.start(1L)
        advanceUntilIdle()

        val failure = harness.viewModel.state.value as MetadataAiViewModel.UiState.Failure
        assertTrue(failure.message.contains("仅支持 TXT"))
        assertTrue(harness.outbound.isEmpty())
        assertTrue(harness.provider.requests.isEmpty())
    }

    @Test
    fun `AI 响应无效时可重试`() = runTest(dispatcher) {
        val harness = Harness(
            initialPrefs = ReadingPreferences(
                aiBaseUrl = "https://api.test/v1",
                aiModelGeneral = "test-model",
                aiMetadataConfirmed = true,
            ),
        )
        harness.provider.reply = "这本书我看不出来"
        harness.viewModel.start(1L)
        advanceUntilIdle()

        val failure = harness.viewModel.state.value as MetadataAiViewModel.UiState.Failure
        assertTrue(failure.message.contains("未能给出可用信息"))
        assertEquals(1, harness.outbound.size) // 无效响应也是一次真实外发

        harness.provider.reply = """{"genreTag": "悬疑"}"""
        harness.viewModel.retry()
        advanceUntilIdle()
        val done = harness.viewModel.state.value as MetadataAiViewModel.UiState.Done
        assertEquals(listOf(BookMetaSources.FIELD_GENRE), done.filledFields)
        assertEquals(2, harness.outbound.size)
    }

    @Test
    fun `批量串行补全,单本失败与非 TXT 跳过不中断`() = runTest(dispatcher) {
        val harness = Harness(
            initialPrefs = ReadingPreferences(
                aiBaseUrl = "https://api.test/v1",
                aiModelGeneral = "test-model",
                aiMetadataConfirmed = true,
            ),
        )
        harness.provider.failOnTitle = "书2"
        harness.viewModel.startBatch(listOf(1L, 2L, 3L))
        advanceUntilIdle()

        val done = harness.viewModel.state.value as MetadataAiViewModel.UiState.BatchDone
        assertEquals(3, done.processed)
        assertEquals(1, done.filled)
        assertEquals(1, done.failed) // 书2 AI 报错
        assertEquals(1, done.skipped) // 书3 读不出开头（非 TXT）
        assertFalse(done.cancelled)
        assertEquals(listOf(1L), harness.writes.map { it.first })
        // 每本真实外发各记一条台账（失败的那本也记）
        assertEquals(2, harness.outbound.size)
    }

    @Test
    fun `批量首次也需一次性确认`() = runTest(dispatcher) {
        val harness = Harness()
        harness.viewModel.startBatch(listOf(1L, 2L))
        advanceUntilIdle()

        val confirm = harness.viewModel.state.value as MetadataAiViewModel.UiState.AwaitConfirmation
        assertTrue(confirm.scopeText.contains("2 本"))
        assertTrue(harness.provider.requests.isEmpty())

        harness.viewModel.confirmAndGenerate()
        advanceUntilIdle()
        val done = harness.viewModel.state.value as MetadataAiViewModel.UiState.BatchDone
        assertEquals(2, done.filled)
        assertTrue(harness.confirmedMarked)
    }

    @Test
    fun `批量中途取消,当前书之后不再继续`() = runTest(dispatcher) {
        val harness = Harness(
            initialPrefs = ReadingPreferences(
                aiBaseUrl = "https://api.test/v1",
                aiModelGeneral = "test-model",
                aiMetadataConfirmed = true,
            ),
        )
        harness.provider.gate = CompletableDeferred()
        harness.viewModel.startBatch(listOf(1L, 2L))
        runCurrent() // 跑到第一本的 AI 调用挂起处

        harness.viewModel.cancelBatch()
        advanceUntilIdle()

        val done = harness.viewModel.state.value as MetadataAiViewModel.UiState.BatchDone
        assertTrue(done.cancelled)
        assertEquals(0, done.processed)
        assertTrue(harness.writes.isEmpty())
    }

    private companion object {
        const val HEAD_TEXT = "书名：测试之书\n作者：某人\n\n第一章 开始\n正文内容……"
    }
}

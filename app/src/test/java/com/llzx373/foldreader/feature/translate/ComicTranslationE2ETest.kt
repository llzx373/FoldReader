package com.llzx373.foldreader.feature.translate

import androidx.room.Room
import com.llzx373.foldreader.core.ai.AiConfig
import com.llzx373.foldreader.core.ai.AiProtocol
import com.llzx373.foldreader.core.ai.AiProvider
import com.llzx373.foldreader.core.ai.AiTargetLang
import com.llzx373.foldreader.core.ai.gate.AiContentGate
import com.llzx373.foldreader.core.ai.openai.ChatCompletionsProvider
import com.llzx373.foldreader.core.data.db.BookEntity
import com.llzx373.foldreader.core.data.db.BookFormat
import com.llzx373.foldreader.core.data.db.ComicPageTranslationEntity
import com.llzx373.foldreader.core.data.db.FoldReaderDatabase
import com.llzx373.foldreader.core.data.db.GlossaryTermEntity
import com.llzx373.foldreader.core.data.settings.ReadingPreferences
import com.llzx373.foldreader.core.ocr.OcrBubble
import com.llzx373.foldreader.core.ocr.OcrRect
import com.llzx373.foldreader.core.ocr.OcrTextLine
import com.llzx373.foldreader.core.translate.ComicTranslationStore
import com.llzx373.foldreader.core.translate.GlossaryRepository
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * M22 漫画翻译的 JVM 端到端链路：
 * 真 Room（内存库）+ 真 ComicTranslationStore（临时目录）+ 假 OCR（合成气泡注入）
 * + 真 ChatCompletionsProvider 打 MockWebServer（SSE 分块）+ 真 ComicTranslationQueue
 * + 真 ComicTranslateEngine。只替掉 OCR 与真实网络，其余全是生产组件。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ComicTranslationE2ETest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var db: FoldReaderDatabase
    private lateinit var store: ComicTranslationStore
    private lateinit var gate: AiContentGate
    private lateinit var provider: ChatCompletionsProvider

    /** 页序号 → 合成气泡（假 OCR 的数据源）。 */
    private val bubbles = mutableMapOf<Int, List<OcrBubble>>()

    @Before
    fun setUp() = runBlocking {
        server = MockWebServer()
        server.start()
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            FoldReaderDatabase::class.java,
        ).build()
        // comic_page_translations 有到 books 的外键，先插一行书
        db.bookDao().upsert(book(1))
        store = ComicTranslationStore(File(tempFolder.root, "comic_translate"))
        gate = AiContentGate(File(tempFolder.root, "ai_content_gate.json"))
        provider = ChatCompletionsProvider(
            AiConfig(
                protocol = AiProtocol.OPENAI_CHAT,
                baseUrl = server.url("/").toString(),
                apiKey = "test-key",
            ),
            OkHttpClient(),
        )
    }

    @After
    fun tearDown() {
        db.close()
        server.shutdown()
    }

    // ---------------------------------------------------------------- 装配

    private fun book(id: Long) = BookEntity(
        id = id,
        title = "测试漫画",
        author = null,
        fileUri = "content://book/$id",
        contentHash = "hash$id",
        format = BookFormat.COMIC,
        totalChars = 1000,
        encoding = "UTF-8",
        importedAt = id,
        lastReadAt = null,
        groupName = null,
        genreTag = null,
    )

    private fun bubble(index: Int, text: String): OcrBubble {
        val rect = OcrRect.of(0.05f, 0.2f * index, 0.4f, 0.15f)
        return OcrBubble(
            index = index,
            rect = rect,
            lines = listOf(OcrTextLine(text, rect, 0.9f)),
            confidence = 0.9f,
        )
    }

    private fun newEngine(provider: AiProvider? = this.provider) = ComicTranslateEngine(
        provider = provider,
        contentGate = gate,
        store = store,
        pageDao = db.comicPageTranslationDao(),
        preferences = {
            ReadingPreferences(
                aiModelTranslation = "test-model",
                aiTargetLang = AiTargetLang.ZH_HANS,
            )
        },
        glossaryRepository = GlossaryRepository(db.glossaryTermDao()),
        seriesKeyFor = { "test-series" },
        bubblesFor = { _, page -> bubbles[page].orEmpty() },
    )

    // ---------------------------------------------------------------- SSE 道具

    /** 把正文增量包成 `data: {"choices":[{"delta":{"content":...}}]}` 事件流，末尾补 [DONE]。 */
    private fun sseBody(vararg deltas: String): String = buildString {
        for (d in deltas) {
            val data = JSONObject()
                .put("choices", JSONArray().put(JSONObject().put("delta", JSONObject().put("content", d))))
                .toString()
            append("data: ").append(data).append("\n\n")
        }
        append("data: [DONE]\n\n")
    }

    private fun sse(vararg deltas: String) = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(sseBody(*deltas))

    private fun okJson(vararg translations: String): String =
        translations.joinToString(",", prefix = "{\"translations\":[", postfix = "]}") {
            JSONObject.quote(it)
        }

    /** 取一次请求并解析请求体；5 秒不到视为「没发出来」。 */
    private fun takeRequestJson(): JSONObject {
        val request = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull("服务器未收到请求", request)
        return JSONObject(request!!.body.readUtf8())
    }

    private fun JSONObject.systemContent(): String =
        getJSONArray("messages").getJSONObject(0).getString("content")

    private fun JSONObject.userContent(): String =
        getJSONArray("messages").getJSONObject(1).getString("content")

    private fun glossaryTerm(
        scope: String,
        ownerKey: String,
        source: String,
        target: String,
        confirmed: Boolean,
    ) = GlossaryTermEntity(
        scope = scope,
        ownerKey = ownerKey,
        source = source,
        target = target,
        origin = GlossaryTermEntity.ORIGIN_USER,
        confirmed = confirmed,
    )

    // ---------------------------------------------------------------- 用例

    @Test
    fun `全链路单页翻译落盘且台账 done`() = runTest {
        db.glossaryTermDao().upsert(glossaryTerm(GlossaryTermEntity.SCOPE_GLOBAL, "", "勇者", "Brave", true))
        bubbles[0] = listOf(bubble(0, "原文一"), bubble(1, "原文二"), bubble(2, "原文三"))
        val json = okJson("译一", "译二", "译三")
        // SSE 分 3 块吐出整个 JSON
        server.enqueue(sse(json.substring(0, 10), json.substring(10, 22), json.substring(22)))

        val result = newEngine().translatePage(1, "测试漫画", 0, AiTargetLang.ZH_HANS)

        assertEquals(3, result.getOrThrow())
        assertEquals(1, server.requestCount)
        // 请求体：编号气泡 + 术语注入
        val body = takeRequestJson()
        assertEquals("test-model", body.getString("model"))
        val user = body.userContent()
        assertTrue(user.contains("<1>"))
        assertTrue(user.contains("<2>"))
        assertTrue(user.contains("<3>"))
        assertTrue(user.contains("原文二"))
        assertTrue("术语应注入 system", body.systemContent().contains("勇者 → Brave"))
        // 落盘往返一致
        assertEquals(
            listOf("译一", "译二", "译三"),
            store.loadTranslation(1, "ZH_HANS", 0)?.texts,
        )
        // 台账 done + 气泡数
        val row = db.comicPageTranslationDao().getForBook(1, "ZH_HANS").single()
        assertEquals(ComicPageTranslationEntity.STATUS_DONE, row.status)
        assertEquals(3, row.bubbleCount)
        assertEquals("test-model", row.model)
    }

    @Test
    fun `流式逐气泡按序回调且不等整包闭合`() = runTest {
        bubbles[0] = listOf(bubble(0, "一"), bubble(1, "二"), bubble(2, "三"))
        // 每个 delta 闭合一个气泡译文；用计数装饰器记录每个 onBubble 触发时
        // 已到达的 delta 数——气泡 N 在 delta N+1 到达时就回调，即「不等整包闭合」。
        val counting = CountingProvider(provider)
        server.enqueue(
            sse(
                "{\"translations\":[\"译一\",",
                "\"译二\",",
                "\"译三\"]}",
            ),
        )
        val events = mutableListOf<Pair<Int, String>>()
        val emittedAtBubble = mutableListOf<Int>()

        val result = newEngine(provider = counting)
            .translatePage(1, "测试漫画", 0, AiTargetLang.ZH_HANS) { index, text ->
                events += index to text
                emittedAtBubble += counting.emitted
            }

        assertTrue("翻译失败：${result.exceptionOrNull()}", result.isSuccess)
        assertEquals(listOf(0 to "译一", 1 to "译二", 2 to "译三"), events)
        assertEquals(
            "气泡 0/1/2 应分别在第 1/2/3 个 delta 到达时回调（不等整个 JSON 闭合）",
            listOf(1, 2, 3),
            emittedAtBubble,
        )
    }

    /** 统计已发出 delta 数的 provider 装饰器（委托给真 ChatCompletionsProvider）。 */
    private class CountingProvider(private val delegate: AiProvider) : AiProvider {
        @Volatile var emitted = 0
        override fun chat(messages: List<com.llzx373.foldreader.core.ai.AiMessage>, model: String) =
            delegate.chat(messages, model).onEach { emitted++ }
    }

    @Test
    fun `数量校验失败整页重试第二次成功`() = runTest {
        bubbles[0] = listOf(bubble(0, "一"), bubble(1, "二"))
        server.enqueue(sse(okJson("只有一个")))
        server.enqueue(sse(okJson("译一", "译二")))

        val result = newEngine().translatePage(1, "测试漫画", 0, AiTargetLang.ZH_HANS)

        assertEquals(2, result.getOrThrow())
        assertEquals(2, server.requestCount)
        val row = db.comicPageTranslationDao().getForBook(1, "ZH_HANS").single()
        assertEquals(ComicPageTranslationEntity.STATUS_DONE, row.status)
        assertEquals(listOf("译一", "译二"), store.loadTranslation(1, "ZH_HANS", 0)?.texts)
    }

    @Test
    fun `数量校验两次都错则置 failed`() = runTest {
        bubbles[0] = listOf(bubble(0, "一"), bubble(1, "二"))
        server.enqueue(sse(okJson("只有一个")))
        server.enqueue(sse(okJson("还是只有一个")))

        val result = newEngine().translatePage(1, "测试漫画", 0, AiTargetLang.ZH_HANS)

        assertTrue(result.isFailure)
        assertEquals(2, server.requestCount)
        val row = db.comicPageTranslationDao().getForBook(1, "ZH_HANS").single()
        assertEquals(ComicPageTranslationEntity.STATUS_FAILED, row.status)
        assertEquals(0, row.bubbleCount)
        assertTrue(store.loadTranslation(1, "ZH_HANS", 0) == null)
    }

    @Test
    fun `队列断点续译跳过 done 页只重译 failed 页`() = runBlocking {
        bubbles[0] = listOf(bubble(0, "A一"), bubble(1, "A二"))
        bubbles[1] = listOf(bubble(0, "B一"), bubble(1, "B二"))
        bubbles[2] = listOf(bubble(0, "C一"), bubble(1, "C二"))
        val engine = newEngine()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val queue = ComicTranslationQueue(
            scope = scope,
            bookFor = { id -> if (id == 1L) "测试漫画" to 3 else null },
            pageDao = db.comicPageTranslationDao(),
            translatePageCall = { bookId, title, page, lang ->
                engine.translatePage(bookId, title, page, lang)
            },
            betweenPagesDelayMs = 5,
            maxPageRetries = 0, // 队列级不重试，只看引擎内部的首次 + 整体重试
            retryBaseDelayMs = 5,
            pausePollMs = 5,
        )
        try {
            // 第一轮：第 0/2 页成功，第 1 页引擎两次尝试都打 503 → failed
            server.enqueue(sse(okJson("译A一", "译A二")))
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(sse(okJson("译C一", "译C二")))
            queue.enqueueBook(1, AiTargetLang.ZH_HANS)
            awaitQueueDone(queue, 1)

            assertEquals(4, server.requestCount)
            assertEquals(1, queue.progress.value[1]?.failedPages)
            var rows = db.comicPageTranslationDao().getForBook(1, "ZH_HANS")
            assertEquals(
                setOf(0, 2),
                rows.filter { it.status == ComicPageTranslationEntity.STATUS_DONE }
                    .mapTo(HashSet()) { it.pageIndex },
            )
            assertEquals(
                listOf(1),
                rows.filter { it.status == ComicPageTranslationEntity.STATUS_FAILED }
                    .map { it.pageIndex },
            )

            // 重新入队 = 断点续译：done 页不再请求，failed 页重译成功
            server.enqueue(sse(okJson("译B一", "译B二")))
            queue.enqueueBook(1, AiTargetLang.ZH_HANS)
            awaitQueueDone(queue, 1)

            assertEquals("续译只应补发 failed 页的 1 次请求", 5, server.requestCount)
            rows = db.comicPageTranslationDao().getForBook(1, "ZH_HANS")
            assertTrue(rows.all { it.status == ComicPageTranslationEntity.STATUS_DONE })
            assertEquals(listOf("译B一", "译B二"), store.loadTranslation(1, "ZH_HANS", 1)?.texts)
            assertEquals(0, queue.progress.value[1]?.failedPages)
        } finally {
            scope.cancel()
        }
    }

    private suspend fun awaitQueueDone(queue: ComicTranslationQueue, bookId: Long) =
        withTimeout(15_000) {
            while (queue.progress.value[bookId]?.status != ComicTranslationQueue.Status.DONE) {
                delay(20)
            }
        }

    @Test
    fun `临时提示词只当次生效下一次恢复内置模板`() = runTest {
        bubbles[0] = listOf(bubble(0, "一"), bubble(1, "二"))
        val engine = newEngine()

        server.enqueue(sse(okJson("译一", "译二")))
        engine.translatePage(1, "测试漫画", 0, AiTargetLang.ZH_HANS, systemOverride = "自定义系统提示词X")
        assertEquals("自定义系统提示词X", takeRequestJson().systemContent())

        server.enqueue(sse(okJson("译一", "译二")))
        engine.translatePage(1, "测试漫画", 0, AiTargetLang.ZH_HANS)
        val second = takeRequestJson().systemContent()
        assertTrue("不带 override 应恢复内置模板", second.contains("你是漫画翻译器"))
        assertFalse(second.contains("自定义系统提示词X"))
    }

    @Test
    fun `术语三级合并书级覆盖系列级且未确认不注入`() = runTest {
        db.glossaryTermDao().upsertAll(
            listOf(
                glossaryTerm(GlossaryTermEntity.SCOPE_GLOBAL, "", "猫", "Cat", true),
                glossaryTerm(GlossaryTermEntity.SCOPE_SERIES, "test-series", "刀", "系列刀译", true),
                glossaryTerm(GlossaryTermEntity.SCOPE_SERIES, "test-series", "剑", "Saber", true),
                glossaryTerm(GlossaryTermEntity.SCOPE_BOOK, "1", "刀", "书级刀译", true),
                glossaryTerm(GlossaryTermEntity.SCOPE_BOOK, "1", "鬼", "Ghost", false), // 未确认
                glossaryTerm(GlossaryTermEntity.SCOPE_BOOK, "1", "空译", "", true), // target 空
            ),
        )
        bubbles[0] = listOf(bubble(0, "一"), bubble(1, "二"))
        server.enqueue(sse(okJson("译一", "译二")))

        newEngine().translatePage(1, "测试漫画", 0, AiTargetLang.ZH_HANS)

        val system = takeRequestJson().systemContent()
        assertTrue("书级覆盖系列级", system.contains("刀 → 书级刀译"))
        assertFalse("被覆盖的系列译法不应出现", system.contains("系列刀译"))
        assertTrue("系列级应注入", system.contains("剑 → Saber"))
        assertTrue("全局级应注入", system.contains("猫 → Cat"))
        assertFalse("未确认术语不应注入", system.contains("鬼"))
        assertFalse("空译法术语不应注入", system.contains("空译"))
    }

    @Test
    fun `未配置 AI 服务直接失败且不触网`() = runTest {
        bubbles[0] = listOf(bubble(0, "一"), bubble(1, "二"))

        val result = newEngine(provider = null).translatePage(1, "测试漫画", 0, AiTargetLang.ZH_HANS)

        assertTrue(result.isFailure)
        assertEquals(0, server.requestCount)
        assertNotNull(result.exceptionOrNull())
    }
}

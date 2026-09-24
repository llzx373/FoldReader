package com.llzx373.foldreader.feature.translate

import com.llzx373.foldreader.core.ai.AiContent
import com.llzx373.foldreader.core.ai.AiMessage
import com.llzx373.foldreader.core.ai.AiProvider
import com.llzx373.foldreader.core.ai.AiTargetLang
import com.llzx373.foldreader.core.ai.gate.AiContentGate
import com.llzx373.foldreader.core.data.db.GlossaryTermDao
import com.llzx373.foldreader.core.data.db.GlossaryTermEntity
import com.llzx373.foldreader.core.data.db.TranslationDao
import com.llzx373.foldreader.core.data.db.TranslationEntity
import com.llzx373.foldreader.core.data.settings.ReadingPreferences
import com.llzx373.foldreader.core.translate.GlossaryRepository
import com.llzx373.foldreader.core.translate.TranslationStore
import com.llzx373.foldreader.core.translate.TranslationUnit
import com.llzx373.foldreader.core.translate.UnitKind
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 翻译引擎链路：台账 → translating → 段落结构化校验（失败整体重试一次）→ done / failed。
 */
class TranslateEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    /** 手写假 DAO（接口很小，比起 Room 库更直接）。 */
    private class FakeTranslationDao : TranslationDao {
        val rows = LinkedHashMap<Triple<Long, String, Int>, TranslationEntity>()

        override fun observeForBook(bookId: Long, lang: String): Flow<List<TranslationEntity>> =
            flowOf(rows.values.filter { it.bookId == bookId && it.lang == lang })

        override suspend fun getForBook(bookId: Long, lang: String): List<TranslationEntity> =
            rows.values.filter { it.bookId == bookId && it.lang == lang }

        override suspend fun countByStatus(bookId: Long, lang: String, status: String): Int =
            rows.values.count { it.bookId == bookId && it.lang == lang && it.status == status }

        override suspend fun upsert(unit: TranslationEntity) {
            rows[Triple(unit.bookId, unit.lang, unit.unitIndex)] = unit
        }

        override suspend fun updateStatus(
            bookId: Long,
            lang: String,
            unitIndex: Int,
            status: String,
            model: String,
            paragraphCount: Int,
            updatedAt: Long,
        ) {
            val key = Triple(bookId, lang, unitIndex)
            rows[key] = rows.getValue(key).copy(
                status = status, model = model, paragraphCount = paragraphCount, updatedAt = updatedAt,
            )
        }

        override suspend fun deleteForBook(bookId: Long) {
            rows.keys.removeAll { it.first == bookId }
        }

        override suspend fun deleteUnit(bookId: Long, lang: String, unitIndex: Int) {
            rows.remove(Triple(bookId, lang, unitIndex))
        }

        override suspend fun deleteAll() {
            rows.clear()
        }
    }

    /** 手写假术语 DAO：只实现仓库与回填用到的口径，其余按内存表直译。 */
    private class FakeGlossaryTermDao : GlossaryTermDao {
        val rows = mutableListOf<GlossaryTermEntity>()
        private var nextId = 1L

        override fun observeFor(scope: String, ownerKey: String): Flow<List<GlossaryTermEntity>> =
            flowOf(rows.filter { it.scope == scope && it.ownerKey == ownerKey })

        override fun observeUnconfirmed(): Flow<List<GlossaryTermEntity>> =
            flowOf(rows.filter { !it.confirmed })

        override suspend fun getAll(): List<GlossaryTermEntity> = rows.toList()

        override fun observeAll(): Flow<List<GlossaryTermEntity>> = flowOf(rows.toList())

        override suspend fun upsert(term: GlossaryTermEntity): Long {
            upsertAll(listOf(term))
            return rows.last().id
        }

        override suspend fun upsertAll(terms: List<GlossaryTermEntity>) {
            terms.forEach { term ->
                rows.removeAll {
                    it.scope == term.scope && it.ownerKey == term.ownerKey && it.source == term.source
                }
                rows += term.copy(id = nextId++)
            }
        }

        override suspend fun setConfirmed(id: Long, confirmed: Boolean, target: String?) {
            val index = rows.indexOfFirst { it.id == id }
            if (index >= 0) {
                rows[index] = rows[index].copy(confirmed = confirmed, target = target ?: rows[index].target)
            }
        }

        override suspend fun delete(id: Long) {
            rows.removeAll { it.id == id }
        }

        override suspend fun deleteFor(scope: String, ownerKey: String) {
            rows.removeAll { it.scope == scope && it.ownerKey == ownerKey }
        }

        override suspend fun confirmedFor(scope: String, ownerKey: String): List<GlossaryTermEntity> =
            rows.filter { it.scope == scope && it.ownerKey == ownerKey && it.confirmed }

        override suspend fun deleteAll() {
            rows.clear()
        }
    }

    /** 可编排的 Provider：每次 chat 弹出 responses 队首；队列为空抛异常。 */
    private class FakeProvider(vararg responses: String) : AiProvider {
        val queue = ArrayDeque(responses.toList())
        var callCount = 0
            private set
        var lastMessages: List<AiMessage>? = null
            private set
        /** 全部调用的消息历史（回填会覆盖 lastMessages，断言首次调用用它）。 */
        val messagesLog = mutableListOf<List<AiMessage>>()

        override fun chat(messages: List<AiMessage>, model: String): Flow<String> = flow {
            callCount += 1
            lastMessages = messages
            messagesLog += messages
            val body = queue.removeFirstOrNull() ?: throw IllegalStateException("无可用响应")
            // 模拟 SSE 增量：两瓣吐出
            emit(body.substring(0, body.length / 2))
            emit(body.substring(body.length / 2))
        }
    }

    private lateinit var dao: FakeTranslationDao
    private lateinit var gate: AiContentGate
    private lateinit var store: TranslationStore

    private val unit = TranslationUnit(0, UnitKind.CHAPTER, "第一章", 0, 100)
    private val unitText = "第一段原文\n\n第二段原文"

    @Before
    fun setUp() {
        dao = FakeTranslationDao()
        gate = AiContentGate(File(tempFolder.newFolder(), "history.json"))
        store = TranslationStore(tempFolder.newFolder("translations"))
        store.saveUnits(7L, "ZH_HANS", listOf(unit))
    }

    private fun engine(provider: AiProvider?, prefs: ReadingPreferences = ReadingPreferences()) =
        TranslateEngine(
            provider = provider,
            contentGate = gate,
            store = store,
            translationDao = dao,
            preferences = { prefs },
        )

    @Test
    fun `未配置 AI 服务直接失败且不碰台账`() = runBlocking {
        val result = engine(null).translateUnit(7L, "书名", unit, unitText, AiTargetLang.ZH_HANS)

        assertTrue(result.isFailure)
        assertTrue(dao.rows.isEmpty())
        assertTrue(gate.history().isEmpty())
    }

    @Test
    fun `成功路径落盘并置 done 且记台账`() = runBlocking {
        val provider = FakeProvider("""{"paragraphs":["译文一","译文二"]}""")
        val prefs = ReadingPreferences(aiModelGeneral = "general-model")

        val result = engine(provider, prefs)
            .translateUnit(7L, "书名", unit, unitText, AiTargetLang.ZH_HANS)

        assertEquals(2, result.getOrNull())
        // 台账：feature=章节翻译、scope=书名、字符数 / 2
        val record = gate.history().single()
        assertEquals("章节翻译", record.feature)
        assertEquals("书名", record.scope)
        assertEquals(unitText.length / 2, record.estimatedTokens)
        // DAO 置 done，翻译模型空回落通用模型
        val row = dao.rows.values.single()
        assertEquals(TranslationEntity.STATUS_DONE, row.status)
        assertEquals("general-model", row.model)
        assertEquals(2, row.paragraphCount)
        assertEquals("chapter", row.unitKind)
        // 段落结构化落盘
        assertEquals(listOf("译文一", "译文二"), store.loadUnit(7L, "ZH_HANS", 0)?.paragraphs)
    }

    @Test
    fun `翻译模型已配时不回落通用模型`() = runBlocking {
        val provider = FakeProvider("""{"paragraphs":["a","b"]}""")
        val prefs = ReadingPreferences(aiModelGeneral = "g", aiModelTranslation = "t-model")

        engine(provider, prefs).translateUnit(7L, "书名", unit, unitText, AiTargetLang.ZH_HANS)

        assertEquals("t-model", dao.rows.values.single().model)
    }

    @Test
    fun `段落数校验失败整体重试一次后成功`() = runBlocking {
        val provider = FakeProvider(
            """{"paragraphs":["只有一段"]}""", // 数量不符 → 重试
            """{"paragraphs":["译文一","译文二"]}""",
        )

        val result = engine(provider).translateUnit(7L, "书名", unit, unitText, AiTargetLang.ZH_HANS)

        assertEquals(2, result.getOrNull())
        assertEquals(2, provider.callCount)
        assertEquals(TranslationEntity.STATUS_DONE, dao.rows.values.single().status)
    }

    @Test
    fun `两次都失败置 failed 并返回失败`() = runBlocking {
        val provider = FakeProvider("不是 JSON", """{"paragraphs":["一"]}""")

        val result = engine(provider).translateUnit(7L, "书名", unit, unitText, AiTargetLang.ZH_HANS)

        assertTrue(result.isFailure)
        assertEquals(2, provider.callCount)
        assertEquals(TranslationEntity.STATUS_FAILED, dao.rows.values.single().status)
        assertNull(store.loadUnit(7L, "ZH_HANS", 0))
    }

    @Test
    fun `空文本直接失败不发起请求`() = runBlocking {
        val provider = FakeProvider("""{"paragraphs":[]}""")

        val result = engine(provider).translateUnit(7L, "书名", unit, "  \n \n", AiTargetLang.ZH_HANS)

        assertTrue(result.isFailure)
        assertEquals(0, provider.callCount)
        assertTrue(gate.history().isEmpty())
    }

    @Test
    fun `translateTextStream 透传 provider 增量流`() = runBlocking {
        val provider = FakeProvider("""{"paragraphs":["译文一","译文二"]}""")

        val deltas = engine(provider)
            .translateTextStream(unitText, AiTargetLang.ZH_HANS)
            .toList()

        assertEquals("""{"paragraphs":["译文一","译文二"]}""", deltas.joinToString(""))
        assertTrue(gate.history().isEmpty()) // 按页翻译台账由调用方记
    }

    @Test
    fun `translateTextStream 未配置时收集即失败`() = runBlocking {
        val error = runCatching {
            engine(null).translateTextStream(unitText, AiTargetLang.ZH_HANS).toList()
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
    }

    // ---------- M20 术语注入与回填 ----------

    private fun glossaryRepositoryOf(vararg terms: GlossaryTermEntity): Pair<FakeGlossaryTermDao, GlossaryRepository> {
        val glossaryDao = FakeGlossaryTermDao()
        runBlocking { glossaryDao.upsertAll(terms.toList()) }
        return glossaryDao to GlossaryRepository(glossaryDao)
    }

    @Test
    fun `已确认术语注入 prompt 且书覆盖全局`() = runBlocking {
        val (_, repository) = glossaryRepositoryOf(
            GlossaryTermEntity(
                scope = GlossaryTermEntity.SCOPE_BOOK, ownerKey = "7",
                source = "张三", target = "Zhang San",
                origin = GlossaryTermEntity.ORIGIN_USER, confirmed = true,
            ),
            GlossaryTermEntity(
                scope = GlossaryTermEntity.SCOPE_GLOBAL, ownerKey = "",
                source = "张三", target = "Sam",
                origin = GlossaryTermEntity.ORIGIN_USER, confirmed = true,
            ),
            // 未确认候选不参与注入
            GlossaryTermEntity(
                scope = GlossaryTermEntity.SCOPE_BOOK, ownerKey = "7",
                source = "李四", target = "Li Si",
                origin = GlossaryTermEntity.ORIGIN_AUTO, confirmed = false,
            ),
        )
        val provider = FakeProvider(
            """{"paragraphs":["译文一","译文二"]}""",
            """{"terms": []}""", // 回填响应（前 3 个完成单位会触发一次）
        )
        val engine = TranslateEngine(
            provider = provider,
            contentGate = gate,
            store = store,
            translationDao = dao,
            preferences = { ReadingPreferences() },
            glossaryRepository = repository,
        )

        engine.translateUnit(7L, "书名", unit, unitText, AiTargetLang.ZH_HANS)

        // 首次调用是单位翻译（第二次是回填），术语注入断言取首次
        val system = (provider.messagesLog.first().first().content.single() as AiContent.Text).text
        assertTrue(system.contains("张三 → Zhang San"))
        assertTrue(!system.contains("张三 → Sam"))
        assertTrue(!system.contains("李四"))
    }

    @Test
    fun `前 3 个完成单位触发一次回填且候选未确认落表`() = runBlocking {
        val (glossaryDao, repository) = glossaryRepositoryOf()
        val provider = FakeProvider(
            """{"paragraphs":["译文一","译文二"]}""",
            """{"terms": [{"source": "第一段", "target": "First"}]}""",
        )
        val engine = TranslateEngine(
            provider = provider,
            contentGate = gate,
            store = store,
            translationDao = dao,
            preferences = { ReadingPreferences() },
            glossaryRepository = repository,
        )

        engine.translateUnit(7L, "书名", unit, unitText, AiTargetLang.ZH_HANS)

        assertEquals(2, provider.callCount) // 单位翻译 + 回填各一次
        val candidate = glossaryDao.rows.single()
        assertEquals(GlossaryTermEntity.SCOPE_BOOK, candidate.scope)
        assertEquals("7", candidate.ownerKey)
        assertEquals("第一段", candidate.source)
        assertEquals(GlossaryTermEntity.ORIGIN_AUTO, candidate.origin)
        assertEquals(false, candidate.confirmed)
        // 回填外发也进台账
        assertEquals(
            listOf("章节翻译", "术语回填"),
            gate.history().map { it.feature },
        )
    }

    @Test
    fun `第 4 个完成单位不再回填`() = runBlocking {
        // 预置 3 个 done 单位：本次完成是第 4 个
        repeat(3) { index ->
            dao.upsert(
                TranslationEntity(
                    bookId = 7L, lang = "ZH_HANS", unitKind = "chapter", unitIndex = index + 1,
                    status = TranslationEntity.STATUS_DONE, model = "m", paragraphCount = 2,
                    updatedAt = 1L,
                ),
            )
        }
        val (_, repository) = glossaryRepositoryOf()
        val provider = FakeProvider("""{"paragraphs":["译文一","译文二"]}""")
        val engine = TranslateEngine(
            provider = provider,
            contentGate = gate,
            store = store,
            translationDao = dao,
            preferences = { ReadingPreferences() },
            glossaryRepository = repository,
        )

        engine.translateUnit(7L, "书名", unit, unitText, AiTargetLang.ZH_HANS)

        assertEquals(1, provider.callCount) // 只有单位翻译，无回填
    }

    @Test
    fun `回填失败不影响单位翻译结果`() = runBlocking {
        val (_, repository) = glossaryRepositoryOf()
        // 第二个响应（回填）直接缺席 → FakeProvider 抛异常 → 静默吞掉
        val provider = FakeProvider("""{"paragraphs":["译文一","译文二"]}""")
        val engine = TranslateEngine(
            provider = provider,
            contentGate = gate,
            store = store,
            translationDao = dao,
            preferences = { ReadingPreferences() },
            glossaryRepository = repository,
        )

        val result = engine.translateUnit(7L, "书名", unit, unitText, AiTargetLang.ZH_HANS)

        assertEquals(2, result.getOrNull())
        assertEquals(TranslationEntity.STATUS_DONE, dao.rows.values.single().status)
    }
}
